package org.grakovne.lissen.cast.upnp

import okhttp3.OkHttpClient

/** The master volume of a renderer, 0 to [MAX_VOLUME]. Every call blocks until the renderer answers. */
interface VolumeControl {
  fun volume(): Int

  fun setVolume(volume: Int)

  fun muted(): Boolean

  fun setMuted(muted: Boolean)

  companion object {
    const val MAX_VOLUME = 100
  }
}

class RenderingControl(
  controlUrl: String,
  httpClient: OkHttpClient,
) : VolumeControl {
  private val service = SoapService(controlUrl, SsdpDiscovery.RENDERING_CONTROL, httpClient)

  override fun volume(): Int =
    service
      .invoke("GetVolume", CHANNEL)
      .childText("CurrentVolume")
      ?.toIntOrNull()
      ?.coerceIn(0, VolumeControl.MAX_VOLUME)
      ?: throw UpnpException("GetVolume failed: no CurrentVolume in the answer")

  override fun setVolume(volume: Int) {
    service.invoke("SetVolume", CHANNEL, "DesiredVolume" to volume.coerceIn(0, VolumeControl.MAX_VOLUME).toString())
  }

  override fun muted(): Boolean =
    when (service.invoke("GetMute", CHANNEL).childText("CurrentMute")?.trim()) {
      "1", "true", "yes" -> true
      else -> false
    }

  override fun setMuted(muted: Boolean) {
    service.invoke("SetMute", CHANNEL, "DesiredMute" to if (muted) "1" else "0")
  }

  private companion object {
    val CHANNEL = "Channel" to "Master"
  }
}
