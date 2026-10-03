package org.grakovne.lissen.cast

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActivePlayerTest {
  private val exoPlayer = mockk<ExoPlayer>(relaxed = true)
  private val renderer = mockk<Player>(relaxed = true)
  private val listener = mockk<Player.Listener>(relaxed = true)
  private val activePlayer = ActivePlayer(exoPlayer).apply { eventsOf = { mockk(relaxed = true) } }

  @Test
  fun `a listener moves along with a switch`() {
    activePlayer.addListener(listener)

    activePlayer.switch(renderer)

    verify { exoPlayer.addListener(listener) }
    verify { exoPlayer.removeListener(listener) }
    verify { renderer.addListener(listener) }
    assertSame(renderer, activePlayer.current)
    assertFalse(activePlayer.isLocal)
  }

  @Test
  fun `a reset returns to the local player with its listeners`() {
    activePlayer.switch(renderer)
    activePlayer.addListener(listener)

    activePlayer.reset()

    verify { renderer.removeListener(listener) }
    verify { exoPlayer.addListener(listener) }
    assertTrue(activePlayer.isLocal)
  }

  @Test
  fun `a removed listener stays behind`() {
    activePlayer.addListener(listener)
    activePlayer.removeListener(listener)

    activePlayer.switch(renderer)

    verify(exactly = 0) { renderer.addListener(listener) }
  }

  @Test
  fun `a switch tells the listeners what changed`() {
    every { exoPlayer.isPlaying } returns true
    every { renderer.isPlaying } returns false
    activePlayer.addListener(listener)

    activePlayer.switch(renderer)

    verify { listener.onIsPlayingChanged(false) }
    verify { listener.onTimelineChanged(any(), Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }
    verify(exactly = 0) { listener.onPlayWhenReadyChanged(any(), any()) }
  }

  @Test
  fun `a switch cancels the messages of the previous player`() {
    val message = mockk<PlayerMessage>(relaxed = true)
    every { exoPlayer.createMessage(any()) } returns message

    activePlayer.createMessage(mockk())
    activePlayer.switch(renderer)

    verify { message.cancel() }
  }

  @Test
  fun `a listener is added once`() {
    activePlayer.addListener(listener)
    activePlayer.addListener(listener)

    verify(exactly = 1) { exoPlayer.addListener(listener) }
  }
}
