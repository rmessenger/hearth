package dev.hearth.audio

import org.junit.Assert.*
import org.junit.Test

class BookMathTest {
    private val book = Book("book", "Title", "Author", listOf(Track("one", "1.mp3", 60_000), Track("two", "2.mp3", 90_000), Track("ten", "10.mp3", 30_000)))
    @Test fun numberedFilesUseNaturalOrderIncludingLargeNumbers() {
        assertEquals(listOf("Chapter 2.mp3", "Chapter 10.mp3", "Chapter 99999999999999999999999.mp3"), listOf("Chapter 99999999999999999999999.mp3", "Chapter 10.mp3", "Chapter 2.mp3").sortedWith(BookMath.naturalOrder))
    }
    @Test fun forwardSkipCrossesAFileBoundary() { assertEquals(1 to 15_000L, BookMath.locate(book, BookMath.absolute(book, "one", 45_000) + 30_000)) }
    @Test fun rewindCrossesAFileBoundary() { assertEquals(0 to 55_000L, BookMath.locate(book, BookMath.absolute(book, "two", 5_000) - 10_000)) }
    @Test fun seekingClampsToBothEndsOfTheBook() {
        assertEquals(0 to 0L, BookMath.locate(book, -100))
        assertEquals(2 to 30_000L, BookMath.locate(book, Long.MAX_VALUE))
    }
    @Test fun exactBoundarySelectsNextFile() { assertEquals(1 to 0L, BookMath.locate(book, 60_000)) }
    @Test fun remainingTimeAccountsForPlaybackSpeed() { assertEquals("1h 0m", BookMath.remaining(7_200_000, 2f)) }
    @Test fun missingFileHasSafePositionFallback() { assertEquals(45_000L, BookMath.absolute(book, "removed", 45_000)) }
    @Test fun emptyBookCanBeHandled() { assertEquals(0 to 0L, BookMath.locate(book.copy(tracks = emptyList()), 100)) }
    private val chapterBook = Book("chapters", "Chapters", "", listOf(
        Track("intro", "Introduction.mp3", 20_000),
        Track("main", "Main.m4b", 120_000, chapters = listOf(Chapter("One", 0), Chapter("Two", 40_000), Chapter("Three", 80_000))),
        Track("outro", "Afterword.mp3", 10_000),
    ))
    @Test fun chapterTimelineCombinesEmbeddedChaptersAndPlainFiles() {
        assertEquals(listOf("Introduction", "One", "Two", "Three", "Afterword"), chapterBook.chapters.map { it.title })
        assertEquals(listOf(20_000L, 40_000L, 40_000L, 40_000L, 10_000L), chapterBook.chapters.map { it.durationMs })
        assertEquals(listOf(0, 1, 1, 1, 2), chapterBook.chapters.map { it.trackIndex })
    }
    @Test fun chapterDetectionUsesExactBoundariesAndHandlesTheEndOfABook() {
        assertEquals(0, BookMath.chapterIndex(chapterBook, 0, 0))
        assertEquals(1, BookMath.chapterIndex(chapterBook, 1, 39_999))
        assertEquals(2, BookMath.chapterIndex(chapterBook, 1, 40_000))
        assertEquals(3, BookMath.chapterIndex(chapterBook, 1, 120_000))
        assertEquals(4, BookMath.chapterIndex(chapterBook, 2, 10_000))
    }
    @Test fun chapterTimeRemainingAccountsForSpeedAndClampsAtTheBoundaries() {
        val chapter = chapterBook.chapters[2]
        assertEquals(15_000L, BookMath.chapterRemaining(chapter, 50_000, 2f))
        assertEquals(40_000L, BookMath.chapterRemaining(chapter, 0, 1f))
        assertEquals(0L, BookMath.chapterRemaining(chapter, 100_000, 1f))
    }
    @Test fun chapterTimelineCoversLeadingAudioAndIgnoresInvalidOrDuplicateMarkers() {
        val book = Book("markers", "Markers", "", listOf(Track("one", "Prelude.m4b", 90_000, chapters = listOf(
            Chapter("Later", 50_000), Chapter("Invalid", -1), Chapter("First", 10_000), Chapter("Duplicate", 10_000), Chapter("End", 90_000),
        ))))
        assertEquals(listOf("Prelude", "First", "Later"), book.chapters.map { it.title })
        assertEquals(listOf(10_000L, 40_000L, 40_000L), book.chapters.map { it.durationMs })
        assertEquals(-1, BookMath.chapterIndex(book.copy(tracks = emptyList()), 0, 0))
    }
}
