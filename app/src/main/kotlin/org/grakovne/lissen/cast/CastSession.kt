package org.grakovne.lissen.cast

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.grakovne.lissen.R
import org.grakovne.lissen.domain.DetailedItem
import org.grakovne.lissen.playback.service.PlaybackService
import org.grakovne.lissen.playback.service.SyncStateStore
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Process-wide: moves playback between the phone and a cast device, with the queue and the position. */
@Singleton
@OptIn(UnstableApi::class)
class CastSession
  @Inject
  constructor(
    @param:ApplicationContext private val context: Context,
    private val activePlayer: ActivePlayer,
    private val exoPlayer: ExoPlayer,
    private val protocols: Set<@JvmSuppressWildcards CastProtocol>,
    private val streams: CastStreamSource,
    private val syncState: SyncStateStore,
  ) {
    private val _device = MutableStateFlow<CastDevice?>(null)
    val device: StateFlow<CastDevice?> = _device.asStateFlow()

    /** The device was asked to play and hasn't started yet. */
    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    private var player: RendererPlayer? = null
    private val scope = MainScope()
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Suppress("DEPRECATION")
    private val wifiLock =
      wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lissen:cast").apply { setReferenceCounted(false) }
    private val wakeLock =
      powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lissen:cast").apply { setReferenceCounted(false) }

    // the phone keeps polling the renderer while it plays, also with the screen off; not after the end of the queue
    private val lockListener =
      object : Player.Listener {
        override fun onEvents(
          player: Player,
          events: Player.Events,
        ) = follow(player)
      }

    init {
      // a reset from elsewhere, such as the playback service going away, ends the cast
      scope.launch {
        activePlayer.player.collect { if (it !== player) release() }
      }
    }

    fun connect(device: CastDevice) {
      if (_device.value?.id == device.id) return
      val link = protocols.firstNotNullOfOrNull { it.open(device) } ?: return Timber.w("No protocol plays on ${device.id}")
      Timber.d("Casting to ${device.name} over ${device.protocol}")

      val previous = player
      val remote = RendererPlayer(link.transport, link.volume, streams, ::onFailure)
      player = remote
      _device.value = device

      handOver(from = activePlayer.current, to = remote)
      previous?.let(::release)
      remote.addListener(lockListener)
      follow(remote)
    }

    fun disconnect() = disconnect(resume = null)

    /** A scan of every protocol every few seconds. A device missing from two scans in a row drops out. */
    fun scan(): Flow<List<CastDevice>> =
      flow {
        val multicastLock = wifiManager.createMulticastLock("lissen:cast-discovery").apply { setReferenceCounted(false) }
        val missedScans = mutableMapOf<String, Int>()
        val known = mutableMapOf<String, CastDevice>()

        try {
          multicastLock.acquire()

          while (true) {
            val found = searchAll()

            found.forEach {
              known[it.id] = it
              missedScans[it.id] = 0
            }
            known.keys
              .filter { id -> found.none { it.id == id } }
              .forEach { id ->
                val missed = (missedScans[id] ?: 0) + 1
                missedScans[id] = missed
                if (missed >= MAX_MISSED_SCANS) known.remove(id)
              }

            emit(known.values.sortedWith(castDeviceOrder))
            delay(SCAN_PAUSE_MS)
          }
        } finally {
          multicastLock.release()
        }
      }

    private suspend fun searchAll(): List<CastDevice> =
      coroutineScope {
        protocols
          .map { protocol ->
            async(Dispatchers.IO) {
              runCatching { protocol.search() }
                .onFailure { Timber.w(it, "Discovery failed in ${protocol::class.simpleName}") }
                .getOrDefault(emptyList())
            }
          }.awaitAll()
          .flatten()
      }

    private fun disconnect(resume: Boolean?) {
      val remote = player ?: return
      Timber.d("Casting ended")

      player = null
      _device.value = null

      handOver(from = remote, to = exoPlayer, playWhenReady = resume ?: remote.playWhenReady)
      release(remote)
    }

    private fun onFailure(error: Exception) {
      Timber.w(error, "Lost the renderer, back to this device")
      Toast.makeText(context, R.string.cast_connection_lost, Toast.LENGTH_SHORT).show()
      disconnect(resume = false)
    }

    private fun handOver(
      from: Player,
      to: Player,
      playWhenReady: Boolean = from.playWhenReady,
    ) {
      val items = queueOf(from)

      Timber.d("Handing over ${items.size} items at ${from.currentMediaItemIndex}:${from.currentPosition}ms, playing=$playWhenReady")
      if (items.isNotEmpty()) {
        to.setMediaItems(items, from.currentMediaItemIndex, from.currentPosition)
        to.playWhenReady = playWhenReady
        if (from.playbackState != Player.STATE_IDLE) to.prepare()
      }

      activePlayer.switch(to)
      from.stop()
    }

    /**
     * The ExoPlayer hands back the items its media sources built, which lost the file segments,
     * so its queue is rebuilt from the book it plays. The items keep the book as their tag; the
     * synced item is only a fallback, since a cancelled synchronization forgets it.
     */
    private fun queueOf(player: Player): List<MediaItem> {
      val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
      if (player !== exoPlayer) return items

      val book =
        items.firstNotNullOfOrNull { it.localConfiguration?.tag as? DetailedItem }
          ?: syncState.value.item
          ?: return items
      val rebuilt = PlaybackService.bookToChapterMediaItems(book).mediaItems

      return when (rebuilt.size == items.size) {
        true -> rebuilt
        false -> items.also { Timber.w("Can't rebuild the queue of ${book.id}: ${rebuilt.size} chapters, ${items.size} items") }
      }
    }

    private fun release() {
      val remote = player ?: return
      player = null
      _device.value = null
      release(remote)
    }

    private fun release(remote: RendererPlayer) {
      remote.removeListener(lockListener)
      remote.release()
      holdLocks(false)
      _connecting.value = false
    }

    private fun follow(player: Player) {
      holdLocks(player.keepsPolling)
      _connecting.value = (player as? RendererPlayer)?.connecting == true
    }

    @SuppressLint("WakelockTimeout")
    private fun holdLocks(hold: Boolean) {
      when (hold) {
        true -> {
          wifiLock.acquire()
          wakeLock.acquire()
        }

        false -> {
          if (wifiLock.isHeld) wifiLock.release()
          if (wakeLock.isHeld) wakeLock.release()
        }
      }
    }

    private val Player.keepsPolling: Boolean
      get() = playWhenReady && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)

    private companion object {
      const val MAX_MISSED_SCANS = 2
      const val SCAN_PAUSE_MS = 2000L
    }
  }
