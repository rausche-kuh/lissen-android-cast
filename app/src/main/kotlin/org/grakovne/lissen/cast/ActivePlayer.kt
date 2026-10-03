package org.grakovne.lissen.cast

import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.FlagSet
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The player the media session plays through: the ExoPlayer, or a renderer while casting. */
@Singleton
@OptIn(UnstableApi::class)
class ActivePlayer
  @Inject
  constructor(
    private val exoPlayer: ExoPlayer,
  ) {
    private val _player = MutableStateFlow<Player>(exoPlayer)
    val player: StateFlow<Player> = _player.asStateFlow()

    val current: Player
      get() = _player.value

    val isLocal: Boolean
      get() = current === exoPlayer

    private val listeners = LinkedHashSet<Player.Listener>()
    private val messages = mutableListOf<PlayerMessage>()

    // FlagSet needs the Android runtime
    @VisibleForTesting
    internal var eventsOf: (IntArray) -> Player.Events = { Player.Events(FlagSet.Builder().addAll(*it).build()) }

    /** The listener moves along with every switch, so it always hears the active player. */
    fun addListener(listener: Player.Listener) {
      if (listeners.add(listener)) current.addListener(listener)
    }

    fun removeListener(listener: Player.Listener) {
      listeners.remove(listener)
      current.removeListener(listener)
    }

    /** A message on the active player. A switch cancels it, and announces a new playlist to plant it again. */
    fun createMessage(target: PlayerMessage.Target): PlayerMessage =
      ((current as? RendererPlayer)?.createMessage(target) ?: exoPlayer.createMessage(target))
        .also { messages += it }

    fun switch(player: Player) {
      val previous = current
      if (previous === player) return

      listeners.forEach {
        previous.removeListener(it)
        player.addListener(it)
      }
      _player.value = player

      messages.forEach { it.cancel() }
      messages.clear()
      announce(previous, player)
    }

    fun reset() = switch(exoPlayer)

    /** The listeners heard the previous player, so they learn what is different about the new one. */
    private fun announce(
      previous: Player,
      player: Player,
    ) {
      val playWhenReady = player.playWhenReady.takeIf { it != previous.playWhenReady }
      val playbackState = player.playbackState.takeIf { it != previous.playbackState }
      val isPlaying = player.isPlaying.takeIf { it != previous.isPlaying }
      val changed =
        eventsOf(
          listOfNotNull(
            Player.EVENT_TIMELINE_CHANGED,
            playWhenReady?.let { Player.EVENT_PLAY_WHEN_READY_CHANGED },
            playbackState?.let { Player.EVENT_PLAYBACK_STATE_CHANGED },
            isPlaying?.let { Player.EVENT_IS_PLAYING_CHANGED },
          ).toIntArray(),
        )

      listeners.toList().forEach {
        it.onTimelineChanged(player.currentTimeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
        playWhenReady?.let { value -> it.onPlayWhenReadyChanged(value, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
        playbackState?.let { value -> it.onPlaybackStateChanged(value) }
        isPlaying?.let { value -> it.onIsPlayingChanged(value) }
        it.onEvents(player, changed)
      }
    }
  }
