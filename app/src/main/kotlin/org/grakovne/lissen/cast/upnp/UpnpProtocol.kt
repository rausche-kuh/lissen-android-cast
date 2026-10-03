package org.grakovne.lissen.cast.upnp

import okhttp3.OkHttpClient
import org.grakovne.lissen.cast.CastDevice
import org.grakovne.lissen.cast.CastProtocol
import org.grakovne.lissen.cast.DeviceLink

class UpnpProtocol(
  private val discovery: SsdpDiscovery,
  private val httpClient: OkHttpClient,
) : CastProtocol {
  override fun search(): List<CastDevice> = discovery.search()

  override fun open(device: CastDevice): DeviceLink? {
    val renderer = device as? Renderer ?: return null

    return DeviceLink(
      transport = AvTransport(renderer.controlUrl, httpClient),
      volume = renderer.volumeUrl?.let { RenderingControl(it, httpClient) },
    )
  }
}
