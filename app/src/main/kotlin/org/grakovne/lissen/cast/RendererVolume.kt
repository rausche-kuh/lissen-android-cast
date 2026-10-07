package org.grakovne.lissen.cast

import timber.log.Timber
import kotlin.math.roundToInt

/**
 * The volume of a renderer as the cast player shows it. Commands take effect in the shown state
 * at once; polls follow changes made elsewhere, such as on the renderer's own remote.
 */
class RendererVolume(
  private val control: VolumeControl?,
) {
  val available: Boolean = control != null

  @Volatile
  var volume: Int = 0
    private set

  @Volatile
  var muted: Boolean = false
    private set

  /**
   * The volume in steps of [STEP], as the player shows it. Android and the media session move a
   * remote volume by one unit of its maximum per key press, so a press is one step here.
   */
  val steps: Int
    get() = (volume / STEP.toFloat()).roundToInt()

  /** The player volume, as the sleep timer fades it: a share of the volume the renderer had at full scale. */
  @Volatile
  var scale: Float = 1f
    private set

  // nothing counts from the volume before the renderer told it
  private var known = false
  private var fullScaleVolume = 0
  private var polls = 0

  fun poll() {
    control ?: return
    if (polls++ % POLL_EVERY != 0) return
    read()
  }

  fun set(volume: Int) {
    control ?: return
    this.volume = volume.coerceIn(0, VolumeControl.MAX_VOLUME)
    known = true
    command { control.setVolume(this.volume) }
    if (muted) setMuted(false)
  }

  fun setSteps(steps: Int) = set(steps * STEP)

  /** Counts from the shown step, so the renderer lands where the placeholder of the press showed it. */
  fun adjust(steps: Int) {
    if (known.not()) read()
    if (known) setSteps(this.steps + steps)
  }

  fun scale(scale: Float) {
    control ?: return
    if (known.not()) read()
    if (known.not()) return

    if (this.scale == 1f) fullScaleVolume = volume
    this.scale = scale.coerceIn(0f, 1f)

    val scaled = (fullScaleVolume * this.scale).roundToInt()
    if (scaled != volume) {
      volume = scaled
      command { control.setVolume(scaled) }
    }
  }

  fun setMuted(muted: Boolean) {
    control ?: return
    this.muted = muted
    command { control.setMuted(muted) }
  }

  private fun read() {
    control ?: return
    try {
      volume = control.volume()
      muted = control.muted()
      known = true
    } catch (e: Exception) {
      Timber.w(e, "Can't read the renderer volume")
    }
  }

  private fun command(block: () -> Unit) {
    try {
      block()
    } catch (e: Exception) {
      Timber.w(e, "The renderer rejected a volume change")
    }
  }

  companion object {
    /**
     * The Cast design checklist asks hardware volume keys for at most 5% of the device's range on
     * video devices and 2% on audio-only ones; 2% suits both, and is finer than a phone's own steps.
     */
    const val STEP = 2
    const val MAX_STEPS = VolumeControl.MAX_VOLUME / STEP
    const val POLL_EVERY = 3
  }
}
