package org.grakovne.lissen.cast

import org.grakovne.lissen.playback.service.FileClip

data class QueueChapter(
  val bookId: String,
  val title: String,
  val album: String?,
  val clips: List<FileClip>,
) {
  val durationMs: Long = clips.sumOf { it.lengthMs }
}

data class FilePosition(
  val fileId: String,
  val offsetMs: Long,
)

data class QueuePosition(
  val index: Int,
  val positionMs: Long,
)

/** Maps the chapter queue the session shows onto the files the renderer plays, and back. */
class CastQueue(
  val chapters: List<QueueChapter>,
) {
  private val files = chapters.flatMap { it.clips }.map { it.fileId }.distinct()

  fun durationMs(index: Int): Long = chapters.getOrNull(index)?.durationMs ?: 0L

  /** A chapter without clips plays from the next chapter that has any. A position past the chapter end stays at its end. */
  fun locate(position: QueuePosition): FilePosition? {
    var rest = position.positionMs.coerceAtLeast(0)

    chapters.drop(position.index.coerceAtLeast(0)).forEach { chapter ->
      chapter.clips.forEachIndexed { clipIndex, clip ->
        if (rest < clip.lengthMs || clipIndex == chapter.clips.lastIndex) {
          return FilePosition(clip.fileId, clip.startMs + rest.coerceAtMost(clip.lengthMs))
        }
        rest -= clip.lengthMs
      }
      rest = 0
    }

    return null
  }

  /** An offset outside the clips of the file is held at the nearest clip bound. */
  fun resolve(file: FilePosition): QueuePosition? {
    var last: QueuePosition? = null

    chapters.forEachIndexed { index, chapter ->
      var before = 0L
      chapter.clips.forEach { clip ->
        if (clip.fileId == file.fileId) {
          when {
            file.offsetMs < clip.startMs -> return last ?: QueuePosition(index, before)
            file.offsetMs < clip.startMs + clip.lengthMs -> return QueuePosition(index, before + file.offsetMs - clip.startMs)
            else -> last = QueuePosition(index, before + clip.lengthMs)
          }
        }
        before += clip.lengthMs
      }
    }

    return last
  }

  /** Where the file after [fileId] starts to play, or null after the last file. */
  fun nextFile(fileId: String): FilePosition? {
    val next = files.getOrNull(files.indexOf(fileId) + 1) ?: return null
    val firstClip = chapters.flatMap { it.clips }.first { it.fileId == next }

    return FilePosition(next, firstClip.startMs)
  }

  /** The end of the last clip that plays from [fileId]. */
  fun fileEndMs(fileId: String): Long? =
    chapters
      .flatMap { it.clips }
      .lastOrNull { it.fileId == fileId }
      ?.let { it.startMs + it.lengthMs }
}

internal val FileClip.startMs: Long
  get() = if (clipStart.isFinite() && clipStart > 0) (clipStart * 1000).toLong() else 0L

internal val FileClip.lengthMs: Long
  get() = if (clipEnd.isFinite()) ((clipEnd * 1000).toLong() - startMs).coerceAtLeast(0) else 0L
