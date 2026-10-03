package org.grakovne.lissen.cast

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.grakovne.lissen.playback.service.FileClip
import org.grakovne.lissen.playback.service.PlaybackService.Companion.CHAPTER_START_MS
import org.grakovne.lissen.playback.service.PlaybackService.Companion.FILE_SEGMENTS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/** The real player on the main looper, as the cast handover drives it, over a recording transport. */
@RunWith(AndroidJUnit4::class)
class RendererPlayerOnDeviceTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val transport = RecordingTransport()
  private lateinit var player: RendererPlayer

  @After
  fun tearDown() {
    instrumentation.runOnMainSync { player.release() }
  }

  @Test
  fun aHandedOverPlayingQueueLoadsTheFileOfTheChapter() {
    instrumentation.runOnMainSync {
      player = RendererPlayer(transport, null, { _, fileId -> CastStream("http://abs/$fileId", "") }, {})
      player.setMediaItems(chapterItems(), 1, 30_000)
      player.playWhenReady = true
      player.prepare()
    }

    awaitCommands("seek 330000")

    assertEquals(listOf("setUri http://abs/a", "play", "seek 330000"), transport.commands.take(3))
  }

  @Test
  fun playPressedAfterAPausedHandoverLoadsTheFile() {
    instrumentation.runOnMainSync {
      player = RendererPlayer(transport, null, { _, fileId -> CastStream("http://abs/$fileId", "") }, {})
      player.setMediaItems(chapterItems(), 2, 0)
      player.playWhenReady = false
      player.prepare()
    }
    Thread.sleep(500)
    assertTrue(transport.commands.isEmpty())

    // what MediaSessionConnection.play does
    instrumentation.runOnMainSync {
      player.prepare()
      player.setPlaybackSpeed(1.5f)
      player.play()
    }

    awaitCommands("seek 100000")
    instrumentation.runOnMainSync { assertEquals(Player.STATE_READY, player.playbackState) }
  }

  private fun awaitCommands(last: String) {
    val deadline = System.currentTimeMillis() + 5_000
    while (last !in transport.commands && System.currentTimeMillis() < deadline) Thread.sleep(50)
    assertTrue("commands: ${transport.commands}", last in transport.commands)
  }

  private fun chapterItems(): List<MediaItem> =
    listOf(
      listOf(FileClip("a", 0.0, 300.0)),
      listOf(FileClip("a", 300.0, 600.0), FileClip("b", 0.0, 100.0)),
      listOf(FileClip("b", 100.0, 400.0)),
    ).mapIndexed { index, clips ->
      MediaItem
        .Builder()
        .setMediaId("chapter:book:$index")
        .setRequestMetadata(
          MediaItem.RequestMetadata
            .Builder()
            .setExtras(Bundle().apply { putParcelableArrayList(FILE_SEGMENTS, ArrayList(clips)) })
            .build(),
        ).setMediaMetadata(
          MediaMetadata
            .Builder()
            .setTitle("Chapter $index")
            .setExtras(Bundle().apply { putLong(CHAPTER_START_MS, index * 300_000L) })
            .build(),
        ).build()
    }

  private class RecordingTransport : Transport {
    val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile
    private var state = TransportState.NO_MEDIA

    override fun setUri(stream: CastStream) {
      commands += "setUri ${stream.url}"
      state = TransportState.STOPPED
    }

    override fun play() {
      commands += "play"
      state = TransportState.PLAYING
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
    }

    override fun positionInfo() = TrackPosition(relTimeMs = 0, trackDurationMs = null)

    override fun transportState() = state
  }
}
