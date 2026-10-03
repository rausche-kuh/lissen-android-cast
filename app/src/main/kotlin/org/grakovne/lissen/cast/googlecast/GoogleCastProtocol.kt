package org.grakovne.lissen.cast.googlecast

import org.grakovne.lissen.cast.CastDevice
import org.grakovne.lissen.cast.CastProtocol
import org.grakovne.lissen.cast.DeviceLink
import su.litvak.chromecast.api.v2.ChromeCast
import su.litvak.chromecast.api.v2.ChromeCasts
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

data class CastReceiver(
  /** The mDNS instance name, which carries the device's id. */
  val instance: String,
  override val name: String,
  val host: String,
  val port: Int,
) : CastDevice {
  override val id: String = "googlecast:$instance"
  override val protocol: String = "Google Cast"
}

class GoogleCastProtocol(
  private val discover: () -> List<ChromeCast> = ::discover,
  private val connect: (CastReceiver) -> ChromeCast = { ChromeCast(it.host, it.port) },
) : CastProtocol {
  override fun search(): List<CastDevice> =
    discover().mapNotNull { cast ->
      val instance = cast.name ?: return@mapNotNull null
      CastReceiver(instance, cast.title?.takeIf { it.isNotBlank() } ?: instance, cast.address, cast.port)
    }

  override fun open(device: CastDevice): DeviceLink? {
    val receiver = device as? CastReceiver ?: return null
    val transport = GoogleCastTransport(connect(receiver))

    return DeviceLink(transport = transport, volume = transport)
  }

  private companion object {
    const val SEARCH_WINDOW_MS = 3000L

    /** One discovery per scan, so nothing listens on the network once the device list closes. */
    fun discover(): List<ChromeCast> {
      wifiAddress()?.let { ChromeCasts.startDiscovery(it) } ?: ChromeCasts.startDiscovery()
      try {
        Thread.sleep(SEARCH_WINDOW_MS)
        return ChromeCasts.get()
      } finally {
        ChromeCasts.stopDiscovery()
      }
    }

    /** Without an address JmDNS may bind to the cellular or an IPv6 interface. */
    fun wifiAddress(): InetAddress? =
      NetworkInterface
        .getNetworkInterfaces()
        ?.toList()
        .orEmpty()
        .filter { it.isUp && it.isLoopback.not() && it.supportsMulticast() }
        .sortedByDescending { it.name.startsWith("wlan") }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
  }
}
