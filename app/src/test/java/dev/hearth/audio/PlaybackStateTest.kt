package dev.hearth.audio

import org.junit.Assert.*
import org.junit.Test

class PlaybackStateTest {
    private val listening = PlaybackState(bookId = "book", trackIndex = 4, positionMs = 123_000, playing = true, speed = 1.5f)

    @Test fun emptyCastQueueKeepsTheBookAndChapterUntilReceiverIsReady() {
        val loading = PlaybackState(castDevice = "Speaker", sleepRemainingMs = 30_000)
        val first = loading.withCastLoadingFallback(listening, hasMediaItem = false)
        val next = loading.copy(bookId = first.bookId, sleepRemainingMs = 29_000)
            .withCastLoadingFallback(first, hasMediaItem = false)
        assertEquals("book", next.bookId)
        assertEquals(4, next.trackIndex)
        assertEquals(123_000L, next.positionMs)
        assertEquals(1.5f, next.speed)
        assertFalse(next.playing)
        assertEquals("Speaker", next.castDevice)
        assertEquals(29_000L, next.sleepRemainingMs)
        val ready = listening.copy(positionMs = 124_000, castDevice = "Speaker")
        assertEquals(ready, ready.withCastLoadingFallback(next, hasMediaItem = true))
    }
    @Test fun localQueueClearingStillClearsTheBook() {
        assertEquals(PlaybackState(), PlaybackState().withCastLoadingFallback(listening, hasMediaItem = false))
    }
    @Test fun openingAnotherBookDoesNotKeepTheOldBookOrPosition() {
        val selected = PlaybackState(bookId = "another", positionMs = 60_000, castDevice = "Speaker")
        assertEquals(selected, selected.withCastLoadingFallback(listening, hasMediaItem = false))
    }
    @Test fun connectingWithoutSelectingABookStaysEmpty() {
        val empty = PlaybackState(castDevice = "Speaker")
        assertEquals(empty, empty.withCastLoadingFallback(PlaybackState(), hasMediaItem = false))
    }
}
