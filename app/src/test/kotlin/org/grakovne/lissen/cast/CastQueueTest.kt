package org.grakovne.lissen.cast

import org.grakovne.lissen.playback.service.FileClip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CastQueueTest {
  // file a runs 600s, file b 400s; chapter 1 spans both files
  private val queue = CastQueue(castChapters())

  @Test
  fun `a chapter position maps into the file that plays it`() {
    assertEquals(FilePosition("a", 120_000), queue.locate(QueuePosition(0, 120_000)))
    assertEquals(FilePosition("a", 330_000), queue.locate(QueuePosition(1, 30_000)))
    assertEquals(FilePosition("b", 50_000), queue.locate(QueuePosition(1, 350_000)))
    assertEquals(FilePosition("b", 100_000), queue.locate(QueuePosition(2, 0)))
  }

  @Test
  fun `a position past the chapter end stays at its end`() {
    assertEquals(FilePosition("a", 300_000), queue.locate(QueuePosition(0, 999_000)))
  }

  @Test
  fun `a chapter without clips plays from the next one`() {
    val withGap = CastQueue(listOf(chapter("a", 0.0, 10.0), QueueChapter("book", "empty", null, emptyList()), chapter("b", 0.0, 10.0)))

    assertEquals(FilePosition("b", 0), withGap.locate(QueuePosition(1, 0)))
  }

  @Test
  fun `a file position maps back to its chapter`() {
    assertEquals(QueuePosition(0, 120_000), queue.resolve(FilePosition("a", 120_000)))
    assertEquals(QueuePosition(1, 0), queue.resolve(FilePosition("a", 300_000)))
    assertEquals(QueuePosition(1, 350_000), queue.resolve(FilePosition("b", 50_000)))
    assertEquals(QueuePosition(2, 50_000), queue.resolve(FilePosition("b", 150_000)))
  }

  @Test
  fun `a file position past its clips is held at the last clip end`() {
    assertEquals(QueuePosition(2, 300_000), queue.resolve(FilePosition("b", 420_000)))
  }

  @Test
  fun `an unknown file resolves to nothing`() {
    assertNull(queue.resolve(FilePosition("z", 0)))
  }

  @Test
  fun `the next file starts at its first clip`() {
    assertEquals(FilePosition("b", 0), queue.nextFile("a"))
    assertNull(queue.nextFile("b"))
  }

  @Test
  fun `chapter durations add up their clips`() {
    assertEquals(listOf(300_000L, 400_000L, 300_000L), queue.chapters.indices.map(queue::durationMs))
    assertEquals(400_000L, queue.fileEndMs("b"))
  }
}

internal fun castChapters(): List<QueueChapter> =
  listOf(
    QueueChapter("book", "One", "Book", listOf(FileClip("a", 0.0, 300.0))),
    QueueChapter("book", "Two", "Book", listOf(FileClip("a", 300.0, 600.0), FileClip("b", 0.0, 100.0))),
    QueueChapter("book", "Three", "Book", listOf(FileClip("b", 100.0, 400.0))),
  )

private fun chapter(
  fileId: String,
  start: Double,
  end: Double,
) = QueueChapter("book", fileId, null, listOf(FileClip(fileId, start, end)))
