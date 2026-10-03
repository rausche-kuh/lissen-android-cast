package org.grakovne.lissen.cast.googlecast

import io.mockk.every
import io.mockk.mockk
import org.grakovne.lissen.cast.upnp.Renderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import su.litvak.chromecast.api.v2.ChromeCast

class GoogleCastProtocolTest {
  @Test
  fun `a discovered device shows its friendly name and is reached at its address`() {
    val protocol = GoogleCastProtocol(discover = { listOf(found("Chromecast-abc", "Living Room", "192.168.1.20", 8009)) })

    val device = protocol.search().single()

    assertEquals(CastReceiver("Chromecast-abc", "Living Room", "192.168.1.20", 8009), device)
    assertEquals("googlecast:Chromecast-abc", device.id)
    assertEquals("Google Cast", device.protocol)
  }

  @Test
  fun `the instance name stands in for a missing friendly name and a device without one is left out`() {
    val protocol =
      GoogleCastProtocol(discover = { listOf(found("Speaker-9", null, "10.0.0.5", 32187), found(null, "Nameless", "10.0.0.6", 8009)) })

    assertEquals(listOf(CastReceiver("Speaker-9", "Speaker-9", "10.0.0.5", 32187)), protocol.search())
  }

  @Test
  fun `only a cast receiver opens`() {
    val cast = mockk<ChromeCast>()
    val protocol = GoogleCastProtocol(discover = { emptyList() }, connect = { cast })

    val link = protocol.open(CastReceiver("Chromecast-abc", "Living Room", "192.168.1.20", 8009))

    assertNotNull(link)
    assertSame(link!!.transport, link.volume)
    assertNull(protocol.open(mockk<Renderer>()))
  }

  private fun found(
    instance: String?,
    title: String?,
    address: String,
    port: Int,
  ) = mockk<ChromeCast> {
    every { name } returns instance
    every { this@mockk.title } returns title
    every { this@mockk.address } returns address
    every { this@mockk.port } returns port
  }
}
