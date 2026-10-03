package org.grakovne.lissen.cast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RendererVolumeTest {
  private val control = FakeVolumeControl()
  private val volume = RendererVolume(control)

  @Test
  fun `polls follow the renderer every few rounds`() {
    control.level = 40
    volume.poll()
    assertEquals(40, volume.volume)

    control.level = 60
    repeat(RendererVolume.POLL_EVERY - 1) { volume.poll() }
    assertEquals(40, volume.volume)

    volume.poll()
    assertEquals(60, volume.volume)
  }

  @Test
  fun `a step moves the volume by five and stays in range`() {
    volume.set(97)
    volume.adjust(1)
    assertEquals(100, control.level)

    volume.set(3)
    volume.adjust(-1)
    assertEquals(0, control.level)
    assertEquals(0, volume.volume)
  }

  @Test
  fun `a step before the first poll counts from the renderer volume`() {
    control.level = 60

    volume.adjust(1)

    assertEquals(65, control.level)
  }

  @Test
  fun `a step leaves a renderer alone that never told its volume`() {
    control.level = 60
    control.failing = true

    volume.adjust(1)
    control.failing = false

    assertEquals(60, control.level)
  }

  @Test
  fun `a scale fades the renderer volume and restores it`() {
    control.level = 40

    volume.scale(0.5f)
    assertEquals(20, control.level)

    volume.scale(0f)
    assertEquals(0, control.level)

    volume.scale(1f)
    assertEquals(40, control.level)
    assertEquals(1f, volume.scale)
  }

  @Test
  fun `changing the volume unmutes`() {
    volume.setMuted(true)
    assertTrue(control.mute)

    volume.adjust(1)

    assertFalse(control.mute)
    assertFalse(volume.muted)
  }

  @Test
  fun `a failing renderer keeps the shown volume`() {
    volume.set(30)
    control.failing = true

    volume.set(50)
    volume.poll()

    assertEquals(50, volume.volume)
  }

  @Test
  fun `a renderer without volume control does nothing`() {
    val none = RendererVolume(null)

    none.set(50)
    none.poll()

    assertFalse(none.available)
    assertEquals(0, none.volume)
  }

  private class FakeVolumeControl : VolumeControl {
    var level = 0
    var mute = false
    var failing = false

    override fun volume(): Int = check().let { level }

    override fun setVolume(volume: Int) {
      check()
      level = volume
    }

    override fun muted(): Boolean = check().let { mute }

    override fun setMuted(muted: Boolean) {
      check()
      mute = muted
    }

    private fun check() {
      if (failing) throw RendererException("unreachable")
    }
  }
}
