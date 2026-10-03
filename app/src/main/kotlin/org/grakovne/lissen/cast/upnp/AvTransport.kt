package org.grakovne.lissen.cast.upnp

import okhttp3.OkHttpClient
import org.grakovne.lissen.cast.CastStream
import org.grakovne.lissen.cast.RendererException
import org.grakovne.lissen.cast.TrackPosition
import org.grakovne.lissen.cast.Transport
import org.grakovne.lissen.cast.TransportState
import java.util.Locale

class UpnpException(
  message: String,
  val errorCode: Int? = null,
) : RendererException(message)

class AvTransport(
  controlUrl: String,
  httpClient: OkHttpClient,
) : Transport {
  private val service = SoapService(controlUrl, SsdpDiscovery.AV_TRANSPORT, httpClient)

  override fun setUri(stream: CastStream) =
    setUri(stream.url, didlLite(stream.url, stream.title, stream.album, stream.coverUrl, stream.mimeType))

  internal fun setUri(
    uri: String,
    metadata: String,
  ) {
    service.invoke("SetAVTransportURI", "CurrentURI" to uri, "CurrentURIMetaData" to metadata)
  }

  /** Optional in AVTransport:1; a renderer without it answers with an action error, or with a bare HTTP error. */
  override fun setNext(stream: CastStream?): Boolean {
    val uri = stream?.url.orEmpty()
    val metadata = stream?.let { didlLite(it.url, it.title, it.album, it.coverUrl, it.mimeType) }.orEmpty()

    return try {
      service.invoke("SetNextAVTransportURI", "NextURI" to uri, "NextURIMetaData" to metadata)
      true
    } catch (e: UpnpException) {
      if (e.errorCode != null && e.errorCode !in UNSUPPORTED) throw e
      false
    }
  }

  override fun play() {
    service.invoke("Play", "Speed" to "1")
  }

  override fun pause() {
    service.invoke("Pause")
  }

  override fun stop() {
    service.invoke("Stop")
  }

  override fun seek(positionMs: Long) {
    service.invoke("Seek", "Unit" to "REL_TIME", "Target" to formatTime(positionMs))
  }

  override fun positionInfo(): TrackPosition {
    val response = service.invoke("GetPositionInfo")

    return TrackPosition(
      relTimeMs = response.childText("RelTime")?.let(::parseTime),
      trackDurationMs = response.childText("TrackDuration")?.let(::parseTime),
      trackUri = response.childText("TrackURI"),
    )
  }

  override fun transportState(): TransportState =
    when (service.invoke("GetTransportInfo").childText("CurrentTransportState")) {
      "PLAYING" -> TransportState.PLAYING
      "PAUSED_PLAYBACK" -> TransportState.PAUSED
      "STOPPED" -> TransportState.STOPPED
      "TRANSITIONING" -> TransportState.TRANSITIONING
      "NO_MEDIA_PRESENT" -> TransportState.NO_MEDIA
      else -> TransportState.UNKNOWN
    }

  companion object {
    // invalid action, action failed, optional action not implemented
    private val UNSUPPORTED = setOf(401, 501, 602)

    /** `H+:MM:SS[.F+]`; `NOT_IMPLEMENTED` and anything unparsable give null. */
    internal fun parseTime(value: String): Long? {
      val parts = value.trim().split(':')
      if (parts.size != 3) return null

      val hours = parts[0].toLongOrNull() ?: return null
      val minutes = parts[1].toLongOrNull() ?: return null
      val seconds = parts[2].toDoubleOrNull() ?: return null

      return (hours * 3600 + minutes * 60) * 1000 + (seconds * 1000).toLong()
    }

    /** Whole seconds: several renderers reject the fraction. */
    internal fun formatTime(positionMs: Long): String {
      val totalSeconds = positionMs.coerceAtLeast(0) / 1000
      return String.format(Locale.ROOT, "%d:%02d:%02d", totalSeconds / 3600, totalSeconds / 60 % 60, totalSeconds % 60)
    }
  }
}
