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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.grakovne.lissen.R
import org.grakovne.lissen.cast.upnp.AvTransport
import org.grakovne.lissen.cast.upnp.Renderer
import org.grakovne.lissen.cast.upnp.RenderingControl
import org.grakovne.lissen.cast.upnp.SsdpDiscovery
import org.grakovne.lissen.domain.DetailedItem
import org.grakovne.lissen.playback.service.PlaybackService
import org.grakovne.lissen.playback.service.SyncStateStore
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Process-wide: moves playback between the phone and a renderer, with the queue and the position. */
@Singleton
@OptIn(UnstableApi::class)
class CastSession
  @Inject
  constructor(
    @param:ApplicationContext private val context: Context,
    private val activePlayer: ActivePlayer,
    private val exoPlayer: ExoPlayer,
    private val discovery: SsdpDiscovery,
    @param:RendererHttpClient private val httpClient: OkHttpClient,
    private val streams: CastStreamSource,
    private val syncState: SyncStateStore,
  ) {
    private val _renderer = MutableStateFlow<Renderer?>(null)
    val renderer: StateFlow<Renderer?> = _renderer.asStateFlow()

    private var player: UpnpPlayer? = null
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
        ) = holdLocks(player.keepsPolling)
      }

    init {
      // a reset from elsewhere, such as the playback service going away, ends the cast
      scope.launch {
        activePlayer.player.collect { if (it !== player) release() }
      }
    }

    fun connect(renderer: Renderer) {
      if (_renderer.value?.udn == renderer.udn) return
      Timber.d("Casting to ${renderer.name}")

      val previous = player
      val upnp =
        UpnpPlayer(
          AvTransport(renderer.controlUrl, httpClient),
          renderer.volumeUrl?.let { RenderingControl(it, httpClient) },
          streams,
          ::onFailure,
        )
      player = upnp
      _renderer.value = renderer

      handOver(from = activePlayer.current, to = upnp)
      previous?.let(::release)
      upnp.addListener(lockListener)
      holdLocks(upnp.keepsPolling)
    }

    fun disconnect() = disconnect(resume = null)

    /** A renderer scan every few seconds. A renderer missing from two scans in a row drops out. */
    fun scan(): Flow<List<Renderer>> =
      flow {
        val multicastLock = wifiManager.createMulticastLock("lissen:cast-discovery").apply { setReferenceCounted(false) }
        val missedScans = mutableMapOf<String, Int>()
        val known = mutableMapOf<String, Renderer>()

        try {
          multicastLock.acquire()

          while (true) {
            val found =
              withContext(Dispatchers.IO) {
                runCatching { discovery.search() }
                  .onFailure { Timber.w(it, "Renderer discovery failed") }
                  .getOrDefault(emptyList())
              }

            found.forEach {
              known[it.udn] = it
              missedScans[it.udn] = 0
            }
            known.keys
              .filter { udn -> found.none { it.udn == udn } }
              .forEach { udn ->
                val missed = (missedScans[udn] ?: 0) + 1
                missedScans[udn] = missed
                if (missed >= MAX_MISSED_SCANS) known.remove(udn)
              }

            emit(known.values.sortedBy { it.name.lowercase() })
            delay(SCAN_PAUSE_MS)
          }
        } finally {
          multicastLock.release()
        }
      }

    private fun disconnect(resume: Boolean?) {
      val upnp = player ?: return
      Timber.d("Casting ended")

      player = null
      _renderer.value = null

      handOver(from = upnp, to = exoPlayer, playWhenReady = resume ?: upnp.playWhenReady)
      release(upnp)
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
      val upnp = player ?: return
      player = null
      _renderer.value = null
      release(upnp)
    }

    private fun release(upnp: UpnpPlayer) {
      upnp.removeListener(lockListener)
      upnp.release()
      holdLocks(false)
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
