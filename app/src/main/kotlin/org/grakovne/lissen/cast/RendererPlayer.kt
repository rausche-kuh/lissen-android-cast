package org.grakovne.lissen.cast

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.os.BundleCompat
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.PlayerMessage
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import org.grakovne.lissen.playback.service.FileClip
import org.grakovne.lissen.playback.service.LissenMediaSourceFactory
import org.grakovne.lissen.playback.service.PlaybackService.Companion.FILE_SEGMENTS
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A [Player] over a cast device. It shows the same chapter queue as the ExoPlayer, so every
 * consumer of chapter indices and positions keeps working, and plays the files behind it.
 */
@OptIn(UnstableApi::class)
class RendererPlayer(
  transport: Transport,
  volumeControl: VolumeControl?,
  streams: StreamSource,
  private val onFailure: (Exception) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()),
  PlayerMessage.Sender {
  private val controller = RendererController(transport, streams, SystemClock::elapsedRealtime)
  private val volume = RendererVolume(volumeControl)
  private val executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor())
  private val handler = Handler(Looper.getMainLooper())

  private var playlist: List<MediaItemData> = emptyList()
  private var playlistGeneration = 0
  private var reportedAutoTransitions = 0
  private var failureReported = false
  private var released = false

  // the renderer reports its position once per poll: a message is due when the position moves past it
  private val messages = mutableListOf<PlayerMessage>()
  private var lastTick: QueuePosition? = null

  private val polling =
    executor.scheduleWithFixedDelay(
      {
        controller.poll()
        volume.poll()
        handler.post(::refresh)
      },
      RendererController.POLL_INTERVAL_MS,
      RendererController.POLL_INTERVAL_MS,
      TimeUnit.MILLISECONDS,
    )

  val mediaItems: List<MediaItem>
    get() = playlist.map { it.mediaItem }

  override fun getState(): State {
    val rendererState = controller.state

    val playbackState =
      when {
        playlist.isEmpty() || rendererState.prepared.not() -> STATE_IDLE
        rendererState.ended -> STATE_ENDED
        rendererState.loading -> STATE_BUFFERING
        else -> STATE_READY
      }

    // volume keys pressed while the renderer loads would queue behind the load and land all at once
    val commands =
      when {
        volume.available.not() -> COMMANDS
        playbackState == STATE_READY -> COMMANDS_WITH_VOLUME
        else -> COMMANDS_WITH_FADE
      }

    val builder =
      State
        .Builder()
        .setAvailableCommands(commands)
        .setDeviceInfo(if (volume.available) REMOTE_WITH_VOLUME else REMOTE)
        .setVolume(volume.scale)
        .setDeviceVolume(volume.volume)
        .setIsDeviceMuted(volume.muted)
        .setPlaylist(playlist)
        .setPlayWhenReady(rendererState.playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setIsLoading(rendererState.loading)
        .setPlaybackState(playbackState)

    if (playlist.isNotEmpty()) {
      val index = rendererState.index.coerceIn(playlist.indices)
      val durationMs = playlist[index].durationUs / 1000

      builder
        .setCurrentMediaItemIndex(index)
        .setContentPositionMs {
          when (rendererState.playing) {
            true -> (rendererState.positionMs + SystemClock.elapsedRealtime() - rendererState.positionAt).coerceAtMost(durationMs)
            false -> rendererState.positionMs
          }
        }
    }

    if (rendererState.autoTransitions != reportedAutoTransitions) {
      reportedAutoTransitions = rendererState.autoTransitions
      builder.setPositionDiscontinuity(DISCONTINUITY_REASON_AUTO_TRANSITION, rendererState.positionMs)
    }

    return builder.build()
  }

  override fun handleSetMediaItems(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<*> {
    playlistGeneration++
    // as in the ExoPlayer, a new playlist is announced and its messages are planted again
    messages.clear()
    lastTick = null
    playlist = mediaItems.mapIndexed { index, item -> item.toItemData(index) }

    val chapters = mediaItems.map { it.toQueueChapter() }
    val index = if (startIndex == C.INDEX_UNSET) 0 else startIndex
    val positionMs = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs

    return submit { controller.setQueue(chapters, index, positionMs) }
  }

  override fun handleAddMediaItems(
    index: Int,
    mediaItems: List<MediaItem>,
  ): ListenableFuture<*> = replacePlaylist(this.mediaItems.toMutableList().apply { addAll(index, mediaItems) })

  override fun handleRemoveMediaItems(
    fromIndex: Int,
    toIndex: Int,
  ): ListenableFuture<*> = replacePlaylist(mediaItems.filterIndexed { index, _ -> index !in fromIndex until toIndex })

  override fun handleMoveMediaItems(
    fromIndex: Int,
    toIndex: Int,
    newIndex: Int,
  ): ListenableFuture<*> {
    val items = mediaItems.toMutableList()
    val moved = items.subList(fromIndex, toIndex).toList()
    items.subList(fromIndex, toIndex).clear()
    items.addAll(newIndex, moved)
    return replacePlaylist(items)
  }

  override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = submit { controller.setPlayWhenReady(playWhenReady) }

  override fun handlePrepare(): ListenableFuture<*> = submit { controller.prepare() }

  override fun handleStop(): ListenableFuture<*> = submit { controller.stop() }

  override fun handleSeek(
    mediaItemIndex: Int,
    positionMs: Long,
    seekCommand: Int,
  ): ListenableFuture<*> {
    if (mediaItemIndex == C.INDEX_UNSET) return Futures.immediateVoidFuture()
    lastTick = null
    val position = if (positionMs == C.TIME_UNSET) 0 else positionMs

    return submit { controller.seek(mediaItemIndex, position) }
  }

  /** The sleep timer fades through this: the player volume scales the renderer volume. */
  override fun handleSetVolume(
    volume: Float,
    volumeOperationType: Int,
  ): ListenableFuture<*> = submit { this.volume.scale(volume) }

  override fun handleSetDeviceVolume(
    deviceVolume: Int,
    flags: Int,
  ): ListenableFuture<*> = submit { volume.set(deviceVolume) }

  override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> = submit { volume.adjust(1) }

  override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> = submit { volume.adjust(-1) }

  override fun handleSetDeviceMuted(
    muted: Boolean,
    flags: Int,
  ): ListenableFuture<*> = submit { volume.setMuted(muted) }

  fun createMessage(target: PlayerMessage.Target): PlayerMessage =
    PlayerMessage(this, target, currentTimeline, currentMediaItemIndex, Clock.DEFAULT, Looper.getMainLooper())

  override fun sendMessage(message: PlayerMessage) {
    messages += message
  }

  override fun handleRelease(): ListenableFuture<*> {
    released = true
    messages.clear()
    polling.cancel(false)
    handler.removeCallbacksAndMessages(null)

    return submit { controller.release() }.also { executor.shutdown() }
  }

  /** The current chapter keeps playing, found by identity, when the queue around it changes. */
  private fun replacePlaylist(items: List<MediaItem>): ListenableFuture<*> {
    val rendererState = controller.state
    val current = playlist.getOrNull(rendererState.index)?.mediaItem

    return when (val index = items.indexOfFirst { it === current }) {
      -1 -> handleSetMediaItems(items, 0, 0)
      else -> handleSetMediaItems(items, index, rendererState.positionMs)
    }
  }

  private fun submit(block: () -> Unit): ListenableFuture<*> = executor.submit(block)

  private fun refresh() {
    if (released) return
    invalidateState()
    deliverMessages()

    val failure = controller.state.failure ?: return
    if (failureReported) return
    failureReported = true
    onFailure(failure)
  }

  private fun deliverMessages() {
    val tick = QueuePosition(currentMediaItemIndex, currentPosition)
    val last = lastTick
    lastTick = tick.takeIf { isPlaying }
    if (last == null || isPlaying.not()) return

    messages.removeAll { it.isCanceled }
    messages
      .filter { message -> crossed(last, tick, message) }
      .forEach { message ->
        runCatching { message.target.handleMessage(message.type, message.payload) }
        if (message.deleteAfterDelivery) {
          messages.remove(message)
          message.markAsProcessed(true)
        }
      }
  }

  /** A jump larger than a poll is a seek, which passes a message without delivering it, as in the ExoPlayer. */
  private fun crossed(
    last: QueuePosition,
    tick: QueuePosition,
    message: PlayerMessage,
  ): Boolean {
    val index = message.mediaItemIndex
    val positionMs = message.positionMs

    return when (tick.index) {
      last.index -> {
        index == tick.index && positionMs > last.positionMs && positionMs <= tick.positionMs &&
          tick.positionMs - last.positionMs <= MAX_TICK_MS
      }

      last.index + 1 -> {
        index == last.index && positionMs > last.positionMs && tick.positionMs <= MAX_TICK_MS
      }

      else -> {
        false
      }
    }
  }

  private fun MediaItem.toItemData(index: Int): MediaItemData =
    MediaItemData
      .Builder("$playlistGeneration:$index:$mediaId")
      .setMediaItem(this)
      .setDurationUs(toQueueChapter().durationMs * 1000)
      .setIsSeekable(true)
      .build()

  private fun MediaItem.toQueueChapter(): QueueChapter =
    QueueChapter(
      bookId =
        LissenMediaSourceFactory.MediaId
          .fromString(mediaId)
          ?.bookId
          .orEmpty(),
      title = mediaMetadata.title?.toString().orEmpty(),
      album = mediaMetadata.albumTitle?.toString(),
      clips =
        requestMetadata.extras
          ?.let { BundleCompat.getParcelableArrayList(it, FILE_SEGMENTS, FileClip::class.java) }
          .orEmpty(),
    )

  private companion object {
    const val MAX_TICK_MS = 3 * RendererController.POLL_INTERVAL_MS

    val COMMANDS: Player.Commands =
      Player.Commands
        .Builder()
        .addAll(
          COMMAND_PLAY_PAUSE,
          COMMAND_PREPARE,
          COMMAND_STOP,
          COMMAND_SEEK_TO_DEFAULT_POSITION,
          COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
          COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
          COMMAND_SEEK_TO_PREVIOUS,
          COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
          COMMAND_SEEK_TO_NEXT,
          COMMAND_SEEK_TO_MEDIA_ITEM,
          COMMAND_SEEK_BACK,
          COMMAND_SEEK_FORWARD,
          COMMAND_SET_MEDIA_ITEM,
          COMMAND_CHANGE_MEDIA_ITEMS,
          COMMAND_GET_CURRENT_MEDIA_ITEM,
          COMMAND_GET_TIMELINE,
          COMMAND_GET_METADATA,
          COMMAND_RELEASE,
        ).build()

    /** The sleep timer fades and the volume shows, but the device volume can't be changed. */
    val COMMANDS_WITH_FADE: Player.Commands =
      COMMANDS
        .buildUpon()
        .addAll(
          COMMAND_GET_VOLUME,
          COMMAND_SET_VOLUME,
          COMMAND_GET_DEVICE_VOLUME,
        ).build()

    val COMMANDS_WITH_VOLUME: Player.Commands =
      COMMANDS_WITH_FADE
        .buildUpon()
        .addAll(
          COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
          COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        ).build()

    val REMOTE: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).build()

    val REMOTE_WITH_VOLUME: DeviceInfo =
      DeviceInfo
        .Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
        .setMaxVolume(VolumeControl.MAX_VOLUME)
        .build()
  }
}
