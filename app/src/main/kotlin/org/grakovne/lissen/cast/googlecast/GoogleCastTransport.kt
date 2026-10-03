package org.grakovne.lissen.cast.googlecast

import org.grakovne.lissen.cast.CastStream
import org.grakovne.lissen.cast.RendererException
import org.grakovne.lissen.cast.TrackPosition
import org.grakovne.lissen.cast.Transport
import org.grakovne.lissen.cast.TransportState
import org.grakovne.lissen.cast.VolumeControl
import su.litvak.chromecast.api.v2.Application
import su.litvak.chromecast.api.v2.ChromeCast
import su.litvak.chromecast.api.v2.Media
import su.litvak.chromecast.api.v2.MediaStatus
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * Plays through the Default Media Receiver, which every Cast device runs without registration.
 * The library opens the connection at the first call and again after it dropped.
 */
class GoogleCastTransport(
  private val cast: ChromeCast,
) : Transport,
  VolumeControl {
  // a status repeats the media only when it changed
  private var contentId: String? = null
  private var durationMs: Long? = null

  override fun setUri(stream: CastStream) {
    if (mediaApp() == null) {
      Timber.d("Launching the media receiver")
      cast.launchApp(MEDIA_RECEIVER_APP_ID) ?: throw RendererException("The media receiver did not start")
    }

    val metadata =
      mapOf(
        Media.METADATA_TYPE to Media.MetadataType.MUSIC_TRACK.ordinal,
        Media.METADATA_TITLE to stream.title,
        Media.METADATA_ALBUM_NAME to stream.album,
        Media.METADATA_IMAGES to listOfNotNull(stream.coverUrl?.let { mapOf("url" to it) }),
      ).filterValues { it != null }

    val media = Media(stream.url, stream.mimeType ?: DEFAULT_CONTENT_TYPE, null, Media.StreamType.BUFFERED, null, metadata, null, null)
    contentId = stream.url
    durationMs = null
    cast.load(media)?.let(::remember) ?: throw RendererException("The media receiver did not load ${stream.url}")
  }

  override fun play() {
    requireMedia("PLAY")
    cast.play()
  }

  override fun pause() {
    requireMedia("PAUSE")
    cast.pause()
  }

  /** The library has no media STOP: a pause leaves the receiver, and the next LOAD replaces the media. */
  override fun stop() {
    if (mediaStatus()?.playerState == MediaStatus.PlayerState.PLAYING) cast.pause()
  }

  override fun seek(positionMs: Long) {
    requireMedia("SEEK")
    cast.seek(positionMs.coerceAtLeast(0) / 1000.0)
  }

  override fun positionInfo(): TrackPosition {
    val status = mediaStatus() ?: return TrackPosition(null, null, null)

    return TrackPosition(
      relTimeMs = (status.currentTime * 1000).toLong(),
      trackDurationMs = durationMs,
      trackUri = contentId,
    )
  }

  override fun transportState(): TransportState =
    when (mediaStatus()?.playerState) {
      null -> TransportState.NO_MEDIA
      MediaStatus.PlayerState.PLAYING -> TransportState.PLAYING
      MediaStatus.PlayerState.PAUSED -> TransportState.PAUSED
      MediaStatus.PlayerState.BUFFERING, MediaStatus.PlayerState.LOADING -> TransportState.TRANSITIONING
      MediaStatus.PlayerState.IDLE -> TransportState.STOPPED
    }

  override fun volume(): Int =
    cast.status
      ?.volume
      ?.level
      ?.let { (it * VolumeControl.MAX_VOLUME).roundToInt().coerceIn(0, VolumeControl.MAX_VOLUME) }
      ?: throw RendererException("The receiver status has no volume level")

  override fun setVolume(volume: Int) = cast.setVolume(volume.coerceIn(0, VolumeControl.MAX_VOLUME) / VolumeControl.MAX_VOLUME.toFloat())

  override fun muted(): Boolean = cast.status?.volume?.muted == true

  override fun setMuted(muted: Boolean) = cast.setMuted(muted)

  /** The media receiver goes, so the device doesn't keep showing a paused book. */
  override fun close() {
    if (cast.isConnected.not()) return
    runCatching { mediaApp()?.let { cast.stopSession(it.sessionId) } }
      .onFailure { Timber.w(it, "Can't stop the media receiver") }
    runCatching { cast.disconnect() }
  }

  /** The media receiver, when it is the app on the device; the library sends media commands to that app. */
  private fun mediaApp(): Application? = cast.status?.runningApp?.takeIf { it.id == MEDIA_RECEIVER_APP_ID }

  /** The status of the media receiver's media, or null when the device plays none, or another app runs. */
  private fun mediaStatus(): MediaStatus? {
    if (mediaApp() == null) return null
    return cast.mediaStatus?.let(::remember)
  }

  private fun requireMedia(command: String) {
    mediaStatus() ?: throw RendererException("$command without media on the media receiver")
  }

  private fun remember(status: MediaStatus): MediaStatus {
    status.media?.let { media ->
      contentId = media.url ?: contentId
      durationMs = media.duration?.let { (it * 1000).toLong() } ?: durationMs
    }
    return status
  }

  companion object {
    const val MEDIA_RECEIVER_APP_ID = "CC1AD845"
    private const val DEFAULT_CONTENT_TYPE = "audio/mpeg"
  }
}
