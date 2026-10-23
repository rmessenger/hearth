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
}
