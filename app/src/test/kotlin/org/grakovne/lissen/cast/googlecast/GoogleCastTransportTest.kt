package org.grakovne.lissen.cast.googlecast

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.grakovne.lissen.cast.CastStream
import org.grakovne.lissen.cast.RendererException
import org.grakovne.lissen.cast.TrackPosition
import org.grakovne.lissen.cast.TransportState
import org.grakovne.lissen.cast.googlecast.GoogleCastTransport.Companion.MEDIA_RECEIVER_APP_ID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import su.litvak.chromecast.api.v2.ChromeCast
import su.litvak.chromecast.api.v2.Media
import su.litvak.chromecast.api.v2.MediaStatus
import su.litvak.chromecast.api.v2.Request
import su.litvak.chromecast.api.v2.Status

class GoogleCastTransportTest {
  private val cast = mockk<ChromeCast>(relaxUnitFun = true)
  private val transport = GoogleCastTransport(cast)

  @Test
  fun `loading a stream launches the media receiver and loads it with its metadata`() {
    running(null)
    every { cast.launchApp(MEDIA_RECEIVER_APP_ID) } returns app(MEDIA_RECEIVER_APP_ID)
    val media = slot<Media>()
    every { cast.load(capture(media)) } returns status(MediaStatus.PlayerState.BUFFERING)

    transport.setUri(CastStream("http://abs/file/a?token=t", "Chapter 1", "Book", "http://abs/cover", "audio/mp4"))

    verify { cast.launchApp(MEDIA_RECEIVER_APP_ID) }
    assertEquals("http://abs/file/a?token=t", media.captured.url)
    assertEquals("audio/mp4", media.captured.contentType)
    assertEquals(Media.StreamType.BUFFERED, media.captured.streamType)
    assertEquals(Media.MetadataType.MUSIC_TRACK, media.captured.metadataType)
    assertEquals("Chapter 1", media.captured.metadata[Media.METADATA_TITLE])
    assertEquals("Book", media.captured.metadata[Media.METADATA_ALBUM_NAME])
    assertEquals(listOf(mapOf("url" to "http://abs/cover")), media.captured.metadata[Media.METADATA_IMAGES])
  }

  @Test
  fun `a running media receiver is joined instead of launched again and a stream without type loads as MPEG audio`() {
    running(MEDIA_RECEIVER_APP_ID)
    val media = slot<Media>()
    every { cast.load(capture(media)) } returns status(MediaStatus.PlayerState.BUFFERING)

    transport.setUri(CastStream("http://abs/a", "One"))

    verify(exactly = 0) { cast.launchApp(any()) }
    assertEquals("audio/mpeg", media.captured.contentType)
    assertEquals(false, media.captured.metadata.containsKey(Media.METADATA_ALBUM_NAME))
  }

  @Test
  fun `a failed launch fails the load`() {
    running(null)
    every { cast.launchApp(any()) } returns null

    assertThrows<RendererException> { transport.setUri(CastStream("http://abs/a", "One")) }
  }

  @Test
  fun `the media status maps to the transport and keeps the duration it reported once`() {
    running(MEDIA_RECEIVER_APP_ID)
    every { cast.mediaStatus } returns status(MediaStatus.PlayerState.PLAYING, 61.5, media("http://abs/a", 3600.0))
    transport.transportState()

    every { cast.mediaStatus } returns status(MediaStatus.PlayerState.PLAYING, 62.0)

    assertEquals(TransportState.PLAYING, transport.transportState())
    assertEquals(TrackPosition(relTimeMs = 62_000, trackDurationMs = 3_600_000, trackUri = "http://abs/a"), transport.positionInfo())
  }

  @Test
  fun `buffering is a transition and an idle player is stopped`() {
    running(MEDIA_RECEIVER_APP_ID)

    every { cast.mediaStatus } returns status(MediaStatus.PlayerState.LOADING)
    assertEquals(TransportState.TRANSITIONING, transport.transportState())

    every { cast.mediaStatus } returns status(MediaStatus.PlayerState.IDLE)
    assertEquals(TransportState.STOPPED, transport.transportState())

    every { cast.mediaStatus } returns null
    assertEquals(TransportState.NO_MEDIA, transport.transportState())
  }

  @Test
  fun `another app on the device is no media and gets no commands`() {
    running("233637DE")

    assertEquals(TransportState.NO_MEDIA, transport.transportState())
    assertEquals(TrackPosition(null, null, null), transport.positionInfo())
    assertThrows<RendererException> { transport.play() }
    verify(exactly = 0) { cast.mediaStatus }
    verify(exactly = 0) { cast.play() }
  }

  @Test
  fun `media loaded by another sender shows its own URI`() {
    running(MEDIA_RECEIVER_APP_ID)
    every { cast.load(any<Media>()) } returns status(MediaStatus.PlayerState.BUFFERING)
    transport.setUri(CastStream("http://abs/a", "One"))

    every { cast.mediaStatus } returns status(MediaStatus.PlayerState.PLAYING, 1.0, media("http://elsewhere/song", 200.0))

    assertEquals("http://elsewhere/song", transport.positionInfo().trackUri)
  }

  @Test
  fun `a next stream is appended to the queue to buffer ahead`() {
    running(MEDIA_RECEIVER_APP_ID)
    every { cast.mediaStatus } returns status(MediaStatus.PlayerState.PLAYING)
    val request = slot<Request>()
    every { cast.send(any<String>(), capture(request), QueueResponse::class.java) } returns answer("MEDIA_STATUS")

    assertTrue(transport.setNext(CastStream("http://abs/b", "Two", "Book", mimeType = "audio/mp4")))

    verify { cast.send("urn:x-cast:com.google.cast.media", any<Request>(), QueueResponse::class.java) }
    val sent = json.readTree(json.writeValueAsString(request.captured))
    assertEquals("QUEUE_INSERT", sent["type"].asText())
    assertEquals(7, sent["mediaSessionId"].asLong())
    val item = sent["items"][0]
    assertEquals("http://abs/b", item["media"]["contentId"].asText())
    assertEquals("audio/mp4", item["media"]["contentType"].asText())
    assertEquals("Two", item["media"]["metadata"][Media.METADATA_TITLE].asText())
    assertTrue(item["autoplay"].asBoolean())
    assertTrue(item["preloadTime"].asDouble() > 0)
  }

  @Test
  fun `a next stream the receiver rejects fails`() {
    running(MEDIA_RECEIVER_APP_ID)
    every { cast.mediaStatus } returns status(MediaStatus.PlayerState.PLAYING)
    every { cast.send(any<String>(), any<Request>(), QueueResponse::class.java) } returns answer("INVALID_REQUEST")

    assertThrows<RendererException> { transport.setNext(CastStream("http://abs/b", "Two")) }
  }

  @Test
  fun `taking the next stream back removes the inserted item`() {
    running(MEDIA_RECEIVER_APP_ID)
    every { cast.mediaStatus } returns status(MediaStatus.PlayerState.PLAYING)
    val requests = mutableListOf<Request>()
    every { cast.send(any<String>(), capture(requests), QueueResponse::class.java) } returns answer("MEDIA_STATUS")
    transport.setNext(CastStream("http://abs/b", "Two"))

    assertTrue(transport.setNext(null))

    val sent = json.readTree(json.writeValueAsString(requests.last()))
    assertEquals("QUEUE_REMOVE", sent["type"].asText())
    assertEquals(listOf(2L), sent["itemIds"].map { it.asLong() })
  }

  @Test
  fun `taking back a next stream that was never handed over sends nothing`() {
    assertTrue(transport.setNext(null))

    verify(exactly = 0) { cast.send(any<String>(), any<Request>(), QueueResponse::class.java) }
  }

  @Test
  fun `the receiver going on to the next item reports its URI and forgets the old duration`() {
    running(MEDIA_RECEIVER_APP_ID)
    every { cast.mediaStatus } returns status(MediaStatus.PlayerState.PLAYING, 3599.0, media("http://abs/a", 3600.0))
    transport.positionInfo()

    every { cast.mediaStatus } returns status(MediaStatus.PlayerState.PLAYING, 1.0, media("http://abs/b", null))

    assertEquals(TrackPosition(relTimeMs = 1_000, trackDurationMs = null, trackUri = "http://abs/b"), transport.positionInfo())
  }

  @Test
  fun `commands go to the media receiver`() {
    running(MEDIA_RECEIVER_APP_ID)
    every { cast.mediaStatus } returns status(MediaStatus.PlayerState.PAUSED)

    transport.play()
    transport.pause()
    transport.seek(61_500)

    verify { cast.play() }
    verify { cast.pause() }
    verify { cast.seek(61.5) }
  }

  @Test
  fun `stop pauses playing media and leaves anything else alone`() {
    running(MEDIA_RECEIVER_APP_ID)
    every { cast.mediaStatus } returns status(MediaStatus.PlayerState.IDLE)
    transport.stop()
    verify(exactly = 0) { cast.pause() }

    every { cast.mediaStatus } returns status(MediaStatus.PlayerState.PLAYING)
    transport.stop()
    verify(exactly = 1) { cast.pause() }
  }

  @Test
  fun `the volume maps to the receiver level`() {
    every { cast.status } returns receiverStatus(null, """{"level":0.42,"muted":true}""")

    assertEquals(42, transport.volume())
    assertTrue(transport.muted())

    transport.setVolume(30)
    transport.setMuted(false)

    verify { cast.setVolume(0.3f) }
    verify { cast.setMuted(false) }
  }

  @Test
  fun `closing stops the media receiver and disconnects`() {
    every { cast.isConnected } returns true
    running(MEDIA_RECEIVER_APP_ID)

    transport.close()

    verify { cast.stopSession("session-1") }
    verify { cast.disconnect() }
  }

  @Test
  fun `closing leaves another app running`() {
    every { cast.isConnected } returns true
    running("233637DE")

    transport.close()

    verify(exactly = 0) { cast.stopSession(any()) }
    verify { cast.disconnect() }
  }

  @Test
  fun `closing an unconnected device sends nothing`() {
    every { cast.isConnected } returns false

    transport.close()

    verify(exactly = 0) { cast.status }
    verify(exactly = 0) { cast.disconnect() }
  }

  private fun running(appId: String?) {
    every { cast.status } returns receiverStatus(appId)
  }

  private fun receiverStatus(
    appId: String?,
    volume: String = "{}",
  ): Status {
    val apps = appId?.let { """[{"appId":"$it","displayName":"app","sessionId":"session-1","transportId":"transport-1"}]""" } ?: "[]"
    return json.readValue("""{"volume":$volume,"applications":$apps}""", Status::class.java)
  }

  private fun app(id: String) = receiverStatus(id).runningApp

  private fun media(
    url: String,
    duration: Double?,
  ) = ""","media":{"contentId":"$url","contentType":"audio/mpeg","duration":$duration}"""

  private fun status(
    state: MediaStatus.PlayerState,
    currentTime: Double = 0.0,
    media: String = "",
  ): MediaStatus =
    json.readValue("""{"mediaSessionId":7,"playerState":"$state","currentTime":$currentTime$media}""", MediaStatus::class.java)

  /** The receiver's answer as the library hands it over, with the type renamed; the queue holds the playing item 1 and the inserted 2. */
  private fun answer(type: String): QueueResponse =
    json.readValue(
      """{"responseType":"$type","requestId":1,"status":[{"mediaSessionId":7,"playerState":"PLAYING","currentTime":0,""" +
        """"currentItemId":1,"items":[{"itemId":1,"autoplay":true},{"itemId":2,"autoplay":true}]}]}""",
      QueueResponse::class.java,
    )

  private companion object {
    val json: ObjectMapper = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
  }
}
