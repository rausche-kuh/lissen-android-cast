package org.grakovne.lissen.cast.upnp

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

data class Renderer(
  val udn: String,
  val name: String,
  val controlUrl: String,
  val volumeUrl: String? = null,
)

/**
 * Finds MediaRenderers through one SSDP M-SEARCH and the device description of each answer. A
 * description is read once per location, as long as the device keeps answering.
 */
class SsdpDiscovery(
  private val httpClient: OkHttpClient,
) {
  private val descriptions = ConcurrentHashMap<String, Renderer>()

  fun search(timeoutMs: Int = SEARCH_WINDOW_MS): List<Renderer> {
    val locations = mutableMapOf<String, String>()

    DatagramSocket().use { socket ->
      val request = M_SEARCH.toByteArray()
      val group = InetAddress.getByName(SSDP_ADDRESS)
      repeat(2) { socket.send(DatagramPacket(request, request.size, group, SSDP_PORT)) }

      val deadline = System.currentTimeMillis() + timeoutMs
      val buffer = ByteArray(2048)
      while (true) {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) break

        socket.soTimeout = remaining.toInt()
        val packet = DatagramPacket(buffer, buffer.size)
        try {
          socket.receive(packet)
        } catch (_: SocketTimeoutException) {
          break
        }

        parseSearchResponse(String(packet.data, 0, packet.length))
          ?.let { (usn, location) -> locations.putIfAbsent(usn, location) }
      }
    }

    val current = locations.values.distinct()
    descriptions.keys.retainAll(current.toSet())

    return current
      .mapNotNull { location -> descriptions[location] ?: describe(location)?.also { descriptions[location] = it } }
      .distinctBy { it.udn }
  }

  private fun describe(location: String): Renderer? =
    try {
      httpClient
        .newCall(Request.Builder().url(location).build())
        .execute()
        .use { response ->
          when (response.isSuccessful) {
            true -> parseDescription(response.body.string(), location)
            false -> null
          }
        }
    } catch (e: Exception) {
      Timber.w(e, "Can't read the device description at $location")
      null
    }

  companion object {
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"

    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SEARCH_WINDOW_MS = 3000

    private val M_SEARCH =
      listOf(
        "M-SEARCH * HTTP/1.1",
        "HOST: $SSDP_ADDRESS:$SSDP_PORT",
        "MAN: \"ssdp:discover\"",
        "MX: 2",
        "ST: $AV_TRANSPORT",
        "",
        "",
      ).joinToString("\r\n")

    /** The USN and LOCATION of an answer to the M-SEARCH. */
    internal fun parseSearchResponse(response: String): Pair<String, String>? {
      val lines = response.split("\r\n", "\n")
      if (lines.firstOrNull()?.startsWith("HTTP/1.1 200") != true) return null

      val headers =
        lines
          .drop(1)
          .mapNotNull { line ->
            line
              .indexOf(':')
              .takeIf { it > 0 }
              ?.let { line.substring(0, it).trim().uppercase() to line.substring(it + 1).trim() }
          }.toMap()

      val location = headers["LOCATION"]?.takeIf { it.isNotEmpty() } ?: return null
      val usn = headers["USN"]?.substringBefore("::")?.takeIf { it.isNotEmpty() } ?: location

      return usn to location
    }

    internal fun parseDescription(
      xml: String,
      location: String,
    ): Renderer? {
      val root = parseXml(xml)
      val base = root.childText("URLBase")?.takeIf { it.isNotEmpty() } ?: location

      val service =
        root
          .descendants("service")
          .firstOrNull { it.childText("serviceType") == AV_TRANSPORT }
          ?: return null

      // the service sits in the serviceList of the device that owns it
      val device = service.parentNode?.parentNode as? Element ?: return null
      val controlUrl = service.controlUrl(base) ?: return null
      val volumeUrl =
        service.parentNode
          ?.let { it as? Element }
          ?.children("service")
          ?.firstOrNull { it.childText("serviceType") == RENDERING_CONTROL }
          ?.controlUrl(base)

      return Renderer(
        udn = device.childText("UDN") ?: location,
        name = device.childText("friendlyName")?.takeIf { it.isNotEmpty() } ?: controlUrl.host,
        controlUrl = controlUrl.toString(),
        volumeUrl = volumeUrl?.toString(),
      )
    }

    private fun Element.controlUrl(base: String) = childText("controlURL")?.let { base.toHttpUrlOrNull()?.resolve(it) }
  }
}
