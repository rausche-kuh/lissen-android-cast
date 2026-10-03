package org.grakovne.lissen.cast

import timber.log.Timber
import kotlin.math.abs

data class RendererState(
  val prepared: Boolean = false,
  val playWhenReady: Boolean = false,
  val index: Int = 0,
  val positionMs: Long = 0,
  /** The clock time at which [positionMs] was the position; it advances from there while [playing]. */
  val positionAt: Long = 0,
  val playing: Boolean = false,
  val loading: Boolean = false,
  val ended: Boolean = false,
  /** Counts the moves into a later chapter that playback made by itself. */
  val autoTransitions: Int = 0,
  val failure: Exception? = null,
)

/**
 * Plays a chapter queue on a renderer. Not thread-safe: every call is made from one thread, and
 * only [state] is read from others.
 *
 * A file is loaded only once playback should run, so a paused queue never makes the renderer play.
 */
class RendererController(
  private val transport: Transport,
  private val streams: StreamSource,
  private val clock: () -> Long,
  private val sleep: (Long) -> Unit = Thread::sleep,
) {
  @Volatile
  var state = RendererState()
    private set

  private var queue = CastQueue(emptyList())
  private var loadedFile: String? = null
  private var loadedUrl: String? = null
  private var lastTrack: TrackPosition? = null
  private var pollFailures = 0
  private var lastCommandAt = Long.MIN_VALUE / 2
  private var lastReport: TransportState? = null

  fun setQueue(
    chapters: List<QueueChapter>,
    index: Int,
    positionMs: Long,
  ) = command {
    unload()
    queue = CastQueue(chapters)
    if (chapters.isNotEmpty() && chapters.all { it.clips.isEmpty() }) Timber.w("None of the ${chapters.size} chapters maps to a file")
    moveTo(QueuePosition(index, positionMs))
    state = state.copy(ended = false)

    if (state.prepared && state.playWhenReady) start()
  }

  fun prepare() =
    command {
      state = state.copy(prepared = true)
      if (state.playWhenReady) start()
    }

  fun setPlayWhenReady(playWhenReady: Boolean) =
    command {
      state = state.copy(playWhenReady = playWhenReady)

      when {
        state.prepared.not() || state.ended -> Unit
        playWhenReady -> start()
        loadedFile != null -> transport.pause().also { freeze() }
      }
    }

  fun seek(
    index: Int,
    positionMs: Long,
  ) = command {
    moveTo(QueuePosition(index, positionMs))
    state = state.copy(ended = false)

    val target = queue.locate(QueuePosition(state.index, state.positionMs))
    when {
      loadedFile == null -> {
        if (state.prepared && state.playWhenReady) start()
      }

      // a seek is a new request for the file: one with a refreshed token loads it again
      target != null && target.fileId == loadedFile && streamUrl(target.fileId) == loadedUrl -> {
        Timber.d("Seeking to ${target.offsetMs}ms in file ${target.fileId}")
        transport.seek(target.offsetMs)
      }

      else -> {
        unload().also { if (state.playWhenReady) start() }
      }
    }
  }

  fun stop() =
    command {
      unload()
      state = state.copy(prepared = false, loading = false)
    }

  fun release() {
    if (loadedFile != null) runCatching { transport.stop() }
    runCatching { transport.close() }
    loadedFile = null
    loadedUrl = null
  }

  fun poll() {
    val file = loadedFile ?: return
    if (state.failure != null) return

    val transportState: TransportState
    val track: TrackPosition
    try {
      transportState = transport.transportState()
      track = transport.positionInfo()
      pollFailures = 0
    } catch (e: Exception) {
      Timber.w(e, "Renderer poll failed")
      // Android cuts the network of a paused app in the background, so only a playing renderer can be lost
      if (state.playWhenReady && ++pollFailures >= MAX_POLL_FAILURES) fail(e)
      return
    }

    if (transportState != lastReport) {
      Timber.d("Renderer reports $transportState at ${track.relTimeMs}ms of ${track.trackDurationMs}ms in file $file")
      lastReport = transportState
    }

    // right after a command the renderer may still report what it did before
    if (clock() - lastCommandAt < SETTLE_MS) return

    if (transportState == TransportState.PLAYING || transportState == TransportState.PAUSED) {
      if (track.playsAnother(file)) return command { onTakenOver(track) }
    }

    when (transportState) {
      TransportState.PLAYING -> follow(file, track, playing = true)
      TransportState.PAUSED -> follow(file, track, playing = false)
      TransportState.STOPPED, TransportState.NO_MEDIA -> command { onStopped(file) }
      TransportState.TRANSITIONING -> state = state.copy(loading = true)
      TransportState.UNKNOWN -> Unit
    }
  }

  /** The renderer is the authority on position and on play or pause, also when its own remote changed them. */
  private fun follow(
    file: String,
    track: TrackPosition,
    playing: Boolean,
  ) {
    lastTrack = track
    val position = track.relTimeMs?.let { queue.resolve(FilePosition(file, it)) }

    state =
      when (position) {
        null -> {
          state.copy(playing = playing, playWhenReady = playing, loading = false)
        }

        else -> {
          // renderers report whole seconds and start late: a small difference is no reason to move the position back and forth
          val close =
            playing && state.playing && position.index == state.index &&
              abs(position.positionMs - currentPositionMs()) < DRIFT_TOLERANCE_MS

          state.copy(
            index = position.index,
            positionMs = if (close) state.positionMs else position.positionMs,
            positionAt = if (close) state.positionAt else clock(),
            playing = playing,
            playWhenReady = playing,
            loading = false,
            autoTransitions = state.autoTransitions + if (position.index > state.index) 1 else 0,
          )
        }
      }
  }

  /** Renderers may rewrite the URI, but it keeps the file id; a missing URI is trusted. */
  private fun TrackPosition.playsAnother(file: String): Boolean {
    val uri = trackUri?.takeIf { it.isNotBlank() && it != NOT_IMPLEMENTED } ?: return false
    return uri.contains(file).not()
  }

  /** Another app plays on the renderer now: this queue pauses where it was and leaves the renderer to it. */
  private fun onTakenOver(track: TrackPosition) {
    Timber.d("The renderer plays ${track.trackUri} now, pausing")
    loadedFile = null
    loadedUrl = null
    lastTrack = null
    state = state.copy(playing = false, playWhenReady = false, loading = false, positionAt = clock())
  }

  /** A file that played to its end moves on to the next one. Any other stop came from the renderer and counts as a pause. */
  private fun onStopped(file: String) {
    val track = lastTrack
    val endMs = track?.trackDurationMs?.takeIf { it > 0 } ?: queue.fileEndMs(file)
    val relTimeMs = track?.relTimeMs
    val finished = state.playWhenReady && relTimeMs != null && endMs != null && relTimeMs >= endMs - END_TOLERANCE_MS
    // the token in the URL expired, and the renderer stopped at its next request
    val stale = finished.not() && state.playWhenReady && streamUrl(file) != loadedUrl

    loadedFile = null
    loadedUrl = null
    lastTrack = null

    Timber.d("Renderer stopped file $file, finished=$finished, stale=$stale, last report $track")

    if (stale) {
      freeze()
      return start()
    }

    if (finished.not()) {
      state = state.copy(playing = false, playWhenReady = false, positionAt = clock())
      return
    }

    val next = queue.nextFile(file) ?: return finish()
    queue.resolve(next)?.let { position ->
      val advanced = position.index > state.index
      moveTo(position)
      if (advanced) state = state.copy(autoTransitions = state.autoTransitions + 1)
    }
    load(next)
  }

  private fun start() {
    if (loadedFile != null) {
      transport.play()
      state = state.copy(playing = true, positionAt = clock())
      return
    }

    if (queue.chapters.isEmpty()) return
    val target = queue.locate(QueuePosition(state.index, state.positionMs)) ?: return finish()
    load(target)
  }

  private fun load(target: FilePosition) {
    Timber.d("Loading file ${target.fileId} at ${target.offsetMs}ms for chapter ${state.index}")
    val stream = streams.open(queue.chapters[state.index], target.fileId)
    state = state.copy(loading = true)

    // a renderer left paused by an earlier session keeps its old stream and resumes it on play
    if (transport.transportState() !in IDLE) transport.stop()
    transport.setUri(stream)
    transport.play()
    awaitPlaying()
    Timber.d("Renderer plays file ${target.fileId}")

    // several renderers reject a seek before the transport plays
    if (target.offsetMs >= MIN_SEEK_MS) seekWhilePlaying(target.offsetMs)

    loadedFile = target.fileId
    loadedUrl = stream.url
    lastTrack = null
    state = state.copy(loading = false, playing = true, positionAt = clock())
  }

  private fun awaitPlaying() {
    val deadline = clock() + LOAD_TIMEOUT_MS

    while (transport.transportState() != TransportState.PLAYING) {
      if (clock() > deadline) throw RendererException("The renderer did not start playing")
      sleep(LOAD_POLL_MS)
    }
  }

  private fun unload() {
    if (loadedFile != null) transport.stop()
    loadedFile = null
    loadedUrl = null
    lastTrack = null
    freeze()
  }

  /** Some renderers take a seek while they buffer, then play from the start: it is sent again until the position holds. */
  private fun seekWhilePlaying(offsetMs: Long) {
    val deadline = clock() + SEEK_CONFIRM_MS

    while (true) {
      Timber.d("Seeking to ${offsetMs}ms in the new file")
      runCatching { transport.seek(offsetMs) }.onFailure { Timber.w(it, "The renderer rejected the seek") }

      val resendAt = clock() + SEEK_RESEND_MS
      while (clock() < resendAt) {
        sleep(LOAD_POLL_MS)
        val relTimeMs = runCatching { transport.positionInfo().relTimeMs }.getOrNull()
        if (relTimeMs != null && relTimeMs >= offsetMs - SEEK_TOLERANCE_MS) return
      }

      if (clock() >= deadline) return Timber.w("The renderer did not seek to ${offsetMs}ms")
    }
  }

  private fun streamUrl(fileId: String): String = streams.open(queue.chapters[state.index], fileId).url

  private fun finish() {
    Timber.d("End of the queue")
    val last = queue.chapters.lastIndex
    state =
      state.copy(
        index = last,
        positionMs = queue.durationMs(last),
        positionAt = clock(),
        playing = false,
        ended = true,
      )
  }

  private fun moveTo(position: QueuePosition) {
    val index = position.index.coerceIn(0, (queue.chapters.size - 1).coerceAtLeast(0))
    state = state.copy(index = index, positionMs = position.positionMs.coerceAtLeast(0), positionAt = clock())
  }

  /** Takes the position the renderer has reached into [state] and stops it from advancing. */
  private fun freeze() {
    state = state.copy(positionMs = currentPositionMs(), positionAt = clock(), playing = false)
  }

  private fun currentPositionMs(): Long =
    when (state.playing) {
      true -> (state.positionMs + clock() - state.positionAt).coerceAtMost(queue.durationMs(state.index))
      false -> state.positionMs
    }

  private fun command(block: () -> Unit) {
    if (state.failure != null) return

    try {
      block()
    } catch (e: Exception) {
      fail(e)
    }
    lastCommandAt = clock()
  }

  private fun fail(error: Exception) {
    Timber.e(error, "Renderer failed")
    state = state.copy(failure = error, playing = false, loading = false)
  }

  companion object {
    const val POLL_INTERVAL_MS = 1000L
    private const val MAX_POLL_FAILURES = 3
    private const val SETTLE_MS = 2000L
    private const val END_TOLERANCE_MS = 3000L
    private const val MIN_SEEK_MS = 1000L
    private const val SEEK_TOLERANCE_MS = 5000L
    private const val SEEK_CONFIRM_MS = 3000L
    private const val SEEK_RESEND_MS = 1000L
    private const val DRIFT_TOLERANCE_MS = 2500L
    private const val LOAD_TIMEOUT_MS = 15_000L
    private const val LOAD_POLL_MS = 250L
    private const val NOT_IMPLEMENTED = "NOT_IMPLEMENTED"
    private val IDLE = setOf(TransportState.STOPPED, TransportState.NO_MEDIA)
  }
}
