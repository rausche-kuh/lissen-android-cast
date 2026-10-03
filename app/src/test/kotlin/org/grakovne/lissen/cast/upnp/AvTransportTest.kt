package org.grakovne.lissen.cast.upnp

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.grakovne.lissen.cast.TrackPosition
import org.grakovne.lissen.cast.TransportState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AvTransportTest {
  private val server = MockWebServer()
  private lateinit var transport: AvTransport

  @BeforeEach
  fun setUp() {
    server.start()
    transport = AvTransport(server.url("/control").toString(), OkHttpClient())
  }

  @AfterEach
  fun tearDown() {
    server.close()
  }

  @Test
  fun `setting the URI sends the action with the escaped stream and metadata`() {
    server.enqueue(ok("SetAVTransportURI"))

    transport.setUri("http://abs/api/items/1/file/2?token=a&b", "<DIDL-Lite/>")

    val request = server.takeRequest()
    val body = request.body!!.utf8()
    assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"", request.headers["SOAPACTION"])
    assertTrue(body.contains("<InstanceID>0</InstanceID>"))
    assertTrue(body.contains("<CurrentURI>http://abs/api/items/1/file/2?token=a&amp;b</CurrentURI>"))
    assertTrue(body.contains("<CurrentURIMetaData>&lt;DIDL-Lite/&gt;</CurrentURIMetaData>"))
  }

  @Test
  fun `play pause stop and seek send their actions`() {
    repeat(4) { server.enqueue(ok(listOf("Play", "Pause", "Stop", "Seek")[it])) }

    transport.play()
    transport.pause()
    transport.stop()
    transport.seek(3_725_400)

    val actions = List(4) { server.takeRequest() }
    assertEquals(
      listOf("Play", "Pause", "Stop", "Seek"),
      actions.map { it.headers["SOAPACTION"]!!.substringAfter('#').trimEnd('"') },
    )
    assertTrue(actions[0].body!!.utf8().contains("<Speed>1</Speed>"))
    assertTrue(actions[3].body!!.utf8().contains("<Unit>REL_TIME</Unit><Target>1:02:05</Target>"))
  }

  @Test
  fun `position info is read from the response`() {
    server.enqueue(ok("GetPositionInfo", "<Track>1</Track><TrackDuration>0:45:10.500</TrackDuration><RelTime>0:01:02</RelTime>"))

    assertEquals(TrackPosition(relTimeMs = 62_000, trackDurationMs = 2_710_500), transport.positionInfo())
  }

  @Test
  fun `an unimplemented position is unknown`() {
    server.enqueue(ok("GetPositionInfo", "<TrackDuration>NOT_IMPLEMENTED</TrackDuration><RelTime>NOT_IMPLEMENTED</RelTime>"))

    val position = transport.positionInfo()

    assertNull(position.relTimeMs)
    assertNull(position.trackDurationMs)
  }

  @Test
  fun `the transport state is mapped`() {
    listOf("PLAYING", "PAUSED_PLAYBACK", "STOPPED", "TRANSITIONING", "NO_MEDIA_PRESENT", "RECORDING").forEach {
      server.enqueue(
        ok("GetTransportInfo", "<CurrentTransportState>$it</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus>"),
      )
    }

    val states = List(6) { transport.transportState() }

    assertEquals(
      listOf(
        TransportState.PLAYING,
        TransportState.PAUSED,
        TransportState.STOPPED,
        TransportState.TRANSITIONING,
        TransportState.NO_MEDIA,
        TransportState.UNKNOWN,
      ),
      states,
    )
  }

  @Test
  fun `a SOAP fault carries the UPnP error`() {
    server.enqueue(
      MockResponse
        .Builder()
        .code(500)
        .body(
          """
          <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>
          <faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>
          <UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>714</errorCode>
          <errorDescription>Illegal MIME-type</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>
          """.trimIndent(),
        ).build(),
    )

    val error = assertThrows<UpnpException> { transport.setUri("http://x", "") }

    assertEquals("SetAVTransportURI failed: HTTP 500, UPnP error 714 (Illegal MIME-type)", error.message)
  }

  @Test
  fun `an HTTP error without a SOAP body fails the action`() {
    server.enqueue(MockResponse.Builder().code(404).build())

    assertThrows<UpnpException> { transport.play() }
  }

  @Test
  fun `times parse with and without a fraction`() {
    assertEquals(0L, AvTransport.parseTime("0:00:00"))
    assertEquals(36_000_250L, AvTransport.parseTime("10:00:00.25"))
    assertNull(AvTransport.parseTime("NOT_IMPLEMENTED"))
    assertNull(AvTransport.parseTime(""))
  }

  @Test
  fun `times format as whole seconds`() {
    assertEquals("0:00:00", AvTransport.formatTime(-5))
    assertEquals("0:00:59", AvTransport.formatTime(59_999))
    assertEquals("12:34:56", AvTransport.formatTime(45_296_000))
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
        <s:Body><u:${action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">$content</u:${action}Response></s:Body>
        </s:Envelope>
        """.trimIndent(),
      ).build()
}
