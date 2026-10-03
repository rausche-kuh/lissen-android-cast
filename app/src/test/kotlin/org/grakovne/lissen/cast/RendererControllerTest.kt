package org.grakovne.lissen.cast

import org.grakovne.lissen.playback.service.FileClip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RendererControllerTest {
  private var now = 0L
  private val transport = FakeTransport()
  private var token = ""
  private var unopenable: String? = null
  private var onSleep: () -> Unit = {}
  private val controller =
    RendererController(
      transport = transport,
      streams = { chapter, fileId ->
        if (fileId == unopenable) throw RendererException("no token")
        CastStream("http://abs/$fileId$token", chapter.title)
      },
      clock = { now },
      sleep = {
        now += it
        onSleep()
      },
    )

  @Test
  fun `a paused queue leaves the renderer alone`() {
    controller.setQueue(castChapters(), 1, 0)
    controller.prepare()
    controller.seek(2, 10_000)

    assertEquals(emptyList<String>(), transport.commands)
  }

  @Test
  fun `playing loads the file of the chapter and seeks once it plays`() {
    start(index = 0, positionMs = 120_000)

    assertEquals(listOf("setUri http://abs/a One", "play", "seek 120000"), transport.commands)
    assertTrue(controller.state.playing)
  }

  @Test
  fun `a seek inside the loaded file only seeks`() {
    start(index = 0, positionMs = 0)
    transport.commands.clear()

    controller.seek(1, 30_000)

    assertEquals(listOf("seek 330000"), transport.commands)
    assertEquals(QueuePosition(1, 30_000), controller.state.position)
  }

  @Test
  fun `a seek into another file loads that file`() {
    start(index = 0, positionMs = 0)
    transport.commands.clear()

    controller.seek(1, 350_000)

    assertEquals(listOf("stop", "setUri http://abs/b Two", "play", "seek 50000"), transport.commands)
  }

  @Test
  fun `a pause survives a move into another file`() {
    start(index = 0, positionMs = 0)
    controller.setPlayWhenReady(false)
    transport.commands.clear()

    controller.seek(2, 0)

    assertEquals(listOf("stop"), transport.commands)
    assertFalse(controller.state.playWhenReady)

    controller.setPlayWhenReady(true)

    assertEquals(listOf("stop", "setUri http://abs/b Three", "play", "seek 100000"), transport.commands)
  }

  @Test
  fun `a renderer left paused by an earlier session is stopped before the new file`() {
    transport.state = TransportState.PAUSED

    start(index = 0, positionMs = 0)

    assertEquals(listOf("stop", "setUri http://abs/a One", "play"), transport.commands)
  }

  @Test
  fun `a pause holds the position the renderer reached`() {
    start(index = 0, positionMs = 10_000)
    now += 5_000

    controller.setPlayWhenReady(false)

    assertEquals(QueuePosition(0, 15_000), controller.state.position)
    assertFalse(controller.state.playing)
  }

  @Test
  fun `a seek the renderer dropped while loading is sent again`() {
    transport.dropsSeeks = 1
    start(index = 0, positionMs = 120_000)

    assertEquals(listOf("setUri http://abs/a One", "play", "seek 120000", "seek 120000"), transport.commands)
    assertEquals(QueuePosition(0, 120_000), controller.state.position)
  }

  @Test
  fun `a renderer that never seeks is given up on`() {
    transport.dropsSeeks = Int.MAX_VALUE
    start(index = 0, positionMs = 120_000)

    assertEquals(3, transport.commands.count { it == "seek 120000" })
    assertTrue(controller.state.playing)
  }

  @Test
  fun `a renderer a second behind leaves the position alone`() {
    start(index = 0, positionMs = 120_000)
    val startedAt = controller.state.positionAt
    now += 3_000
    transport.track = TrackPosition(relTimeMs = 121_000, trackDurationMs = 600_000)

    controller.poll()

    assertEquals(QueuePosition(0, 120_000), controller.state.position)
    assertEquals(startedAt, controller.state.positionAt)
  }

  @Test
  fun `a polled file position becomes the chapter position`() {
    start(index = 0, positionMs = 0)
    settle()

    transport.track = TrackPosition(relTimeMs = 450_000, trackDurationMs = 600_000)
    controller.poll()

    assertEquals(QueuePosition(1, 150_000), controller.state.position)
    assertEquals(1, controller.state.autoTransitions)
  }

  @Test
  fun `a poll right after a command is not trusted`() {
    start(index = 0, positionMs = 0)
    controller.seek(2, 0)

    transport.track = TrackPosition(relTimeMs = 5_000, trackDurationMs = 600_000)
    controller.poll()

    assertEquals(QueuePosition(2, 0), controller.state.position)
  }

  @Test
  fun `a file played to its end moves on to the next file`() {
    start(index = 1, positionMs = 0)
    settle()
    transport.track = TrackPosition(relTimeMs = 599_000, trackDurationMs = 600_000)
    controller.poll()
    transport.commands.clear()

    transport.state = TransportState.STOPPED
    controller.poll()

    assertEquals(listOf("setUri http://abs/b Two", "play"), transport.commands)
    assertEquals(QueuePosition(1, 300_000), controller.state.position)
    assertEquals(0, controller.state.autoTransitions)
    assertTrue(controller.state.playing)
  }

  @Test
  fun `the end of the last file ends playback`() {
    start(index = 2, positionMs = 0)
    settle()
    transport.track = TrackPosition(relTimeMs = 398_500, trackDurationMs = 400_000)
    controller.poll()

    transport.state = TransportState.STOPPED
    controller.poll()

    assertTrue(controller.state.ended)
    assertEquals(QueuePosition(2, 300_000), controller.state.position)
  }

  @Test
  fun `a stop on the renderer before the end counts as a pause`() {
    start(index = 0, positionMs = 0)
    settle()
    transport.track = TrackPosition(relTimeMs = 100_000, trackDurationMs = 600_000)
    controller.poll()
    transport.commands.clear()

    transport.state = TransportState.STOPPED
    controller.poll()

    assertFalse(controller.state.playWhenReady)
    assertEquals(emptyList<String>(), transport.commands)
  }

  @Test
  fun `a seek after a token refresh loads the file with the new token`() {
    start(index = 0, positionMs = 0)
    transport.commands.clear()
    token = "?token=new"

    controller.seek(1, 30_000)

    assertEquals(listOf("stop", "setUri http://abs/a?token=new Two", "play", "seek 330000"), transport.commands)
  }

  @Test
  fun `a stop after a token refresh loads the file again where it was`() {
    start(index = 0, positionMs = 0)
    settle()
    transport.track = TrackPosition(relTimeMs = 100_000, trackDurationMs = 600_000)
    controller.poll()
    transport.commands.clear()
    token = "?token=new"

    transport.state = TransportState.STOPPED
    controller.poll()

    assertEquals(listOf("setUri http://abs/a?token=new One", "play", "seek 100000"), transport.commands)
    assertTrue(controller.state.playWhenReady)
  }

  @Test
  fun `another app on the renderer pauses the queue and leaves the renderer to it`() {
    start(index = 0, positionMs = 0)
    settle()
    transport.track = TrackPosition(relTimeMs = 100_000, trackDurationMs = 600_000)
    controller.poll()
    transport.commands.clear()
    settle()

    transport.track = TrackPosition(relTimeMs = 5_000, trackDurationMs = 200_000, trackUri = "http://music/song.mp3")
    controller.poll()

    assertFalse(controller.state.playWhenReady)
    assertEquals(QueuePosition(0, 100_000), controller.state.position)
    assertEquals(emptyList<String>(), transport.commands)
  }

  @Test
  fun `a rewritten URI of the loaded file is still the queue`() {
    start(index = 0, positionMs = 0)
    settle()

    transport.track = TrackPosition(relTimeMs = 100_000, trackDurationMs = 600_000, trackUri = "http://proxy/stream/a")
    controller.poll()

    assertTrue(controller.state.playWhenReady)
    assertEquals(QueuePosition(0, 100_000), controller.state.position)
  }

  @Test
  fun `a pause from the renderer remote is followed`() {
    start(index = 0, positionMs = 0)
    settle()

    transport.state = TransportState.PAUSED
    controller.poll()

    assertFalse(controller.state.playWhenReady)
  }

  @Test
  fun `three failed polls in a row fail the renderer`() {
    start(index = 0, positionMs = 0)
    transport.failing = true

    controller.poll()
    controller.poll()
    assertNull(controller.state.failure)

    controller.poll()
    assertNotNull(controller.state.failure)
  }

  @Test
  fun `failed polls while paused keep the renderer`() {
    start(index = 0, positionMs = 0)
    controller.setPlayWhenReady(false)
    transport.failing = true

    repeat(5) { controller.poll() }

    assertNull(controller.state.failure)
  }

  @Test
  fun `a successful poll forgives earlier failures`() {
    start(index = 0, positionMs = 0)

    transport.failing = true
    repeat(2) { controller.poll() }
    transport.failing = false
    controller.poll()
    transport.failing = true
    repeat(2) { controller.poll() }

    assertNull(controller.state.failure)
  }

  @Test
  fun `a renderer that never plays the stream fails it`() {
    transport.startsPlaying = false

    start(index = 0, positionMs = 0)

    assertNotNull(controller.state.failure)
  }

  @Test
  fun `a playing file hands the renderer the next file once, close to its end`() {
    transport.takesNext = true
    start(index = 1, positionMs = 0)
    settle()
    transport.commands.clear()

    transport.track = TrackPosition(relTimeMs = 310_000, trackDurationMs = 600_000, trackUri = "http://abs/a")
    controller.poll()
    assertEquals(emptyList<String>(), transport.commands)

    transport.track = TrackPosition(relTimeMs = 560_000, trackDurationMs = 600_000, trackUri = "http://abs/a")
    controller.poll()
    controller.poll()

    assertEquals(listOf("setNext http://abs/b Two"), transport.commands)
  }

  @Test
  fun `the renderer going on to the next file moves the queue without loading it`() {
    transport.takesNext = true
    start(index = 1, positionMs = 0)
    settle()
    transport.track = TrackPosition(relTimeMs = 599_000, trackDurationMs = 600_000, trackUri = "http://abs/a")
    controller.poll()
    transport.commands.clear()

    transport.track = TrackPosition(relTimeMs = 105_000, trackDurationMs = 400_000, trackUri = "http://abs/b")
    controller.poll()

    assertEquals(emptyList<String>(), transport.commands)
    assertEquals(QueuePosition(2, 5_000), controller.state.position)
    assertEquals(1, controller.state.autoTransitions)
    assertTrue(controller.state.playWhenReady)

    // the renderer plays the file it went on to, and is not seen as taken over
    settle()
    transport.track = TrackPosition(relTimeMs = 110_000, trackDurationMs = 400_000, trackUri = "http://abs/b")
    controller.poll()

    assertTrue(controller.state.playWhenReady)
    assertEquals(2, controller.state.index)
  }

  @Test
  fun `a renderer reporting the first URI in the next file still moves the queue`() {
    transport.takesNext = true
    start(index = 1, positionMs = 0)
    settle()
    transport.track = TrackPosition(relTimeMs = 599_000, trackDurationMs = 600_000, trackUri = "http://abs/a")
    controller.poll()
    transport.commands.clear()

    settle()
    transport.track = TrackPosition(relTimeMs = 500, trackDurationMs = 600_000, trackUri = "http://abs/a")
    controller.poll()

    assertEquals(emptyList<String>(), transport.commands)
    assertEquals(QueuePosition(1, 300_500), controller.state.position)
  }

  @Test
  fun `a renderer that stops for a moment before the next file is not loaded again`() {
    transport.takesNext = true
    start(index = 1, positionMs = 0)
    settle()
    transport.track = TrackPosition(relTimeMs = 599_000, trackDurationMs = 600_000, trackUri = "http://abs/a")
    controller.poll()
    transport.commands.clear()

    transport.state = TransportState.STOPPED
    onSleep = {
      transport.state = TransportState.PLAYING
      transport.track = TrackPosition(relTimeMs = 1_000, trackDurationMs = 100_000, trackUri = "http://abs/b")
    }
    controller.poll()
    settle()
    controller.poll()

    assertEquals(emptyList<String>(), transport.commands)
    assertEquals(QueuePosition(1, 301_000), controller.state.position)
  }

  @Test
  fun `a renderer that stays stopped instead of going on to the next file loads it`() {
    transport.takesNext = true
    start(index = 1, positionMs = 0)
    settle()
    transport.track = TrackPosition(relTimeMs = 599_000, trackDurationMs = 600_000, trackUri = "http://abs/a")
    controller.poll()
    transport.commands.clear()

    transport.state = TransportState.STOPPED
    controller.poll()

    assertEquals(listOf("setNext null", "setUri http://abs/b Two", "play"), transport.commands)
  }

  @Test
  fun `a seek after the renderer went on to the next file loads the file again`() {
    transport.takesNext = true
    start(index = 1, positionMs = 0)
    settle()
    transport.track = TrackPosition(relTimeMs = 599_000, trackDurationMs = 600_000, trackUri = "http://abs/a")
    controller.poll()
    transport.commands.clear()

    transport.track = TrackPosition(relTimeMs = 1_000, trackDurationMs = 100_000, trackUri = "http://abs/b")
    controller.seek(1, 100_000)

    assertEquals(listOf("setNext null", "stop", "setUri http://abs/a Two", "play", "seek 400000"), transport.commands)
  }

  @Test
  fun `a next file that can't be opened leaves the renderer playing`() {
    transport.takesNext = true
    start(index = 1, positionMs = 0)
    settle()
    unopenable = "b"

    transport.track = TrackPosition(relTimeMs = 599_000, trackDurationMs = 600_000, trackUri = "http://abs/a")
    controller.poll()

    assertNull(controller.state.failure)
    assertFalse(transport.commands.any { it.startsWith("setNext") })
  }

  @Test
  fun `a seek into another file takes the next file back first`() {
    transport.takesNext = true
    start(index = 0, positionMs = 0)
    settle()
    transport.track = TrackPosition(relTimeMs = 570_000, trackDurationMs = 600_000, trackUri = "http://abs/a")
    controller.poll()
    transport.commands.clear()

    controller.seek(2, 50_000)

    assertEquals(listOf("setNext null", "stop", "setUri http://abs/b Three", "play", "seek 150000"), transport.commands)
  }

  @Test
  fun `a renderer that can't take a next file is not asked again`() {
    start(index = 0, positionMs = 0)
    settle()
    transport.track = TrackPosition(relTimeMs = 570_000, trackDurationMs = 600_000, trackUri = "http://abs/a")
    controller.poll()
    controller.seek(0, 0)
    settle()
    controller.poll()

    assertEquals(1, transport.commands.count { it.startsWith("setNext") })
  }

  @Test
  fun `a next file that doesn't start at its beginning is not handed over`() {
    transport.takesNext = true
    val chapters =
      listOf(
        QueueChapter("book", "One", null, listOf(FileClip("a", 0.0, 10.0))),
        QueueChapter("book", "Two", null, listOf(FileClip("b", 5.0, 20.0))),
      )
    controller.setQueue(chapters, 0, 0)
    controller.prepare()
    controller.setPlayWhenReady(true)
    settle()

    transport.track = TrackPosition(relTimeMs = 2_000, trackDurationMs = 10_000, trackUri = "http://abs/a")
    controller.poll()

    assertFalse(transport.commands.any { it.startsWith("setNext") })
  }

  @Test
  fun `the end of a file the renderer has no next one for is polled fast`() {
    start(index = 0, positionMs = 0)
    settle()

    transport.track = TrackPosition(relTimeMs = 100_000, trackDurationMs = 600_000)
    controller.poll()
    assertEquals(RendererController.POLL_INTERVAL_MS, controller.pollDelayMs())

    settle()
    transport.track = TrackPosition(relTimeMs = 598_000, trackDurationMs = 600_000)
    controller.poll()
    assertTrue(controller.pollDelayMs() < RendererController.POLL_INTERVAL_MS)
  }

  @Test
  fun `the end of a file the renderer goes on from by itself is polled as usual`() {
    transport.takesNext = true
    start(index = 1, positionMs = 0)
    settle()

    transport.track = TrackPosition(relTimeMs = 598_000, trackDurationMs = 600_000, trackUri = "http://abs/a")
    controller.poll()

    assertEquals(RendererController.POLL_INTERVAL_MS, controller.pollDelayMs())
  }

  private fun start(
    index: Int,
    positionMs: Long,
  ) {
    controller.setQueue(castChapters(), index, positionMs)
    controller.prepare()
    controller.setPlayWhenReady(true)
  }

  private fun settle() {
    now += 5_000
  }

  private val RendererState.position: QueuePosition
    get() = QueuePosition(index, positionMs)

  private class FakeTransport : Transport {
    val commands = mutableListOf<String>()
    var state = TransportState.NO_MEDIA
    var track = TrackPosition(relTimeMs = 0, trackDurationMs = null)
    var failing = false
    var startsPlaying = true
    var dropsSeeks = 0
    var takesNext = false

    override fun setUri(stream: CastStream) {
      commands += "setUri ${stream.url} ${stream.title}"
      state = TransportState.STOPPED
    }

    override fun play() {
      commands += "play"
      if (startsPlaying) state = TransportState.PLAYING
    }

    override fun pause() {
      commands += "pause"
      state = TransportState.PAUSED
    }

    override fun stop() {
      commands += "stop"
      state = TransportState.STOPPED
    }

    override fun seek(positionMs: Long) {
      commands += "seek $positionMs"
      if (dropsSeeks-- <= 0) track = track.copy(relTimeMs = positionMs)
    }

    override fun setNext(stream: CastStream?): Boolean {
      commands += "setNext ${stream?.let { "${it.url} ${it.title}" }}"
      return takesNext
    }

    override fun positionInfo(): TrackPosition = track.also { if (failing) throw RendererException("timeout") }

    override fun transportState(): TransportState = state.also { if (failing) throw RendererException("timeout") }
  }
}
