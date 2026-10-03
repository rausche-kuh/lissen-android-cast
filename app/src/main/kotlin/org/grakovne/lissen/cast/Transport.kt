package org.grakovne.lissen.cast

import java.io.IOException

enum class TransportState {
  PLAYING,
  PAUSED,
  STOPPED,
  TRANSITIONING,
  NO_MEDIA,
  UNKNOWN,
}

data class TrackPosition(
  val relTimeMs: Long?,
  val trackDurationMs: Long?,
  val trackUri: String? = null,
)

/** A file on the server as a device opens it, with what it shows while the file plays. */
data class CastStream(
  val url: String,
  val title: String,
  val album: String? = null,
  val coverUrl: String? = null,
  val mimeType: String? = null,
)

fun interface StreamSource {
  fun open(
    chapter: QueueChapter,
    fileId: String,
  ): CastStream
}

/** The transport of one device, whatever protocol it speaks. Every call blocks until the device answers. */
interface Transport {
  fun setUri(stream: CastStream)

  fun play()

  fun pause()

  fun stop()

  fun seek(positionMs: Long)

  fun positionInfo(): TrackPosition

  fun transportState(): TransportState

  /** Lets go of the device. Nothing is called after it. */
  fun close() = Unit
}

/** The master volume of a device, 0 to [MAX_VOLUME]. Every call blocks until the device answers. */
interface VolumeControl {
  fun volume(): Int

  fun setVolume(volume: Int)

  fun muted(): Boolean

  fun setMuted(muted: Boolean)

  companion object {
    const val MAX_VOLUME = 100
  }
}

open class RendererException(
  message: String,
) : IOException(message)
