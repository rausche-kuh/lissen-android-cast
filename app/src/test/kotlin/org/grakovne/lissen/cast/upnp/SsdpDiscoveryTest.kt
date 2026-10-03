package org.grakovne.lissen.cast.upnp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SsdpDiscoveryTest {
  @Test
  fun `an answer to the search yields its device and its description location`() {
    val response =
      listOf(
        "HTTP/1.1 200 OK",
        "CACHE-CONTROL: max-age=1800",
        "Location: http://192.168.1.20:49152/description.xml",
        "ST: urn:schemas-upnp-org:service:AVTransport:1",
        "USN: uuid:renderer-1::urn:schemas-upnp-org:service:AVTransport:1",
        "",
        "",
      ).joinToString("\r\n")

    assertEquals(
      "uuid:renderer-1" to "http://192.168.1.20:49152/description.xml",
      SsdpDiscovery.parseSearchResponse(response),
    )
  }

  @Test
  fun `a notification or an answer without a location is ignored`() {
    assertNull(SsdpDiscovery.parseSearchResponse("NOTIFY * HTTP/1.1\r\nLOCATION: http://a/b.xml\r\n\r\n"))
    assertNull(SsdpDiscovery.parseSearchResponse("HTTP/1.1 200 OK\r\nUSN: uuid:x\r\n\r\n"))
  }

  @Test
  fun `a relative control URL resolves against the description location`() {
    val renderer = SsdpDiscovery.parseDescription(description(controlUrl = "/upnp/control/AVTransport1"), LOCATION)

    assertEquals(
      Renderer(
        "uuid:renderer-1",
        "Living Room",
        "http://192.168.1.20:49152/upnp/control/AVTransport1",
        "http://192.168.1.20:49152/upnp/control/RenderingControl1",
      ),
      renderer,
    )
  }

  @Test
  fun `a URLBase takes precedence over the description location`() {
    val renderer =
      SsdpDiscovery.parseDescription(
        description(controlUrl = "AVTransport/control", urlBase = "http://192.168.1.30:8080/dev/"),
        LOCATION,
      )

    assertEquals("http://192.168.1.30:8080/dev/AVTransport/control", renderer?.controlUrl)
  }

  @Test
  fun `the renderer is the embedded device that owns the transport`() {
    val xml =
      """
      <root xmlns="urn:schemas-upnp-org:device-1-0">
        <device>
          <UDN>uuid:root</UDN>
          <friendlyName>Receiver</friendlyName>
          <deviceList>
            <device>
              <UDN>uuid:media</UDN>
              <friendlyName>Receiver Media</friendlyName>
              <serviceList>
                <service>
                  <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                  <controlURL>/av</controlURL>
                </service>
              </serviceList>
            </device>
          </deviceList>
        </device>
      </root>
      """.trimIndent()

    val renderer = SsdpDiscovery.parseDescription(xml, LOCATION)

    assertEquals(Renderer("uuid:media", "Receiver Media", "http://192.168.1.20:49152/av"), renderer)
  }

  @Test
  fun `a device without a transport is no renderer`() {
    val xml =
      """
      <root xmlns="urn:schemas-upnp-org:device-1-0"><device><UDN>uuid:server</UDN><friendlyName>NAS</friendlyName>
      <serviceList><service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
      <controlURL>/cd</controlURL></service></serviceList></device></root>
      """.trimIndent()

    assertNull(SsdpDiscovery.parseDescription(xml, LOCATION))
  }

  private fun description(
    controlUrl: String,
    urlBase: String? = null,
  ) = """
    <?xml version="1.0"?>
    <root xmlns="urn:schemas-upnp-org:device-1-0">
      ${urlBase?.let { "<URLBase>$it</URLBase>" }.orEmpty()}
      <device>
        <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
        <friendlyName>Living Room</friendlyName>
        <UDN>uuid:renderer-1</UDN>
        <serviceList>
          <service>
            <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
            <controlURL>/upnp/control/RenderingControl1</controlURL>
          </service>
          <service>
            <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
            <controlURL>$controlUrl</controlURL>
          </service>
        </serviceList>
      </device>
    </root>
    """.trimIndent()

  private companion object {
    const val LOCATION = "http://192.168.1.20:49152/description.xml"
  }
}
