package org.grakovne.lissen.cast.upnp

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RenderingControlTest {
  private val server = MockWebServer()
  private lateinit var control: RenderingControl

  @BeforeEach
  fun setUp() {
    server.start()
    control = RenderingControl(server.url("/control").toString(), OkHttpClient())
  }

  @AfterEach
  fun tearDown() {
    server.close()
  }

  @Test
  fun `the volume is read from the master channel`() {
    server.enqueue(ok("GetVolume", "<CurrentVolume>37</CurrentVolume>"))

    assertEquals(37, control.volume())

    val request = server.takeRequest()
    assertEquals("\"urn:schemas-upnp-org:service:RenderingControl:1#GetVolume\"", request.headers["SOAPACTION"])
    assertTrue(request.body!!.utf8().contains("<InstanceID>0</InstanceID><Channel>Master</Channel>"))
  }

  @Test
  fun `a volume outside the range is clamped`() {
    server.enqueue(ok("SetVolume"))
    server.enqueue(ok("GetVolume", "<CurrentVolume>255</CurrentVolume>"))

    control.setVolume(120)

    assertTrue(
      server
        .takeRequest()
        .body!!
        .utf8()
        .contains("<DesiredVolume>100</DesiredVolume>"),
    )
    assertEquals(100, control.volume())
  }

  @Test
  fun `an answer without a volume fails`() {
    server.enqueue(ok("GetVolume"))

    assertThrows<UpnpException> { control.volume() }
  }

  @Test
  fun `mute is read and set`() {
    server.enqueue(ok("GetMute", "<CurrentMute>1</CurrentMute>"))
    server.enqueue(ok("GetMute", "<CurrentMute>0</CurrentMute>"))
    server.enqueue(ok("SetMute"))

    assertTrue(control.muted())
    assertFalse(control.muted())
    control.setMuted(true)

    server.takeRequest()
    server.takeRequest()
    assertTrue(
      server
        .takeRequest()
        .body!!
        .utf8()
        .contains("<Channel>Master</Channel><DesiredMute>1</DesiredMute>"),
    )
  }

  private fun ok(
    action: String,
    content: String = "",
  ): MockResponse =
    MockResponse
      .Builder()
      .code(200)
      .body(
        """
        <?xml version="1.0"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
        <s:Body><u:${action}Response xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">$content</u:${action}Response></s:Body>
        </s:Envelope>
        """.trimIndent(),
      ).build()
}
