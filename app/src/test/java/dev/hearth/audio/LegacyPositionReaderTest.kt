package dev.hearth.audio

import org.junit.Assert.*
import org.junit.Test

class LegacyPositionReaderTest {
    private val tracks = listOf(Track("one", "chapter2.mp3", 120_000), Track("two", "chapter10.mp3", 120_000))
    @Test fun importsFileRelativeSecondsFromRealSerializedValues() {
        val progress = requireNotNull(LegacyPositionReader.read(positionBackupFixture().inputStream(), tracks))
        assertEquals("two", progress.trackUri)
        assertEquals(65_000L, progress.positionMs)
        assertEquals("Started", progress.status)
        assertEquals(1f, progress.speed)
        assertTrue(progress.bookmarks.isEmpty())
    }
    @Test fun exactFilenameMatchingSupportsUnicode() {
        val name = "第二章 — A story 🎧.mp3"
        assertEquals("unicode", LegacyPositionReader.read(positionBackupFixture(name).inputStream(), listOf(Track("unicode", name, 120_000)))?.trackUri)
    }
    @Test fun unknownOrAmbiguousFilesAreNotImported() {
        assertNull(LegacyPositionReader.read(positionBackupFixture("missing.mp3").inputStream(), tracks))
        assertNull(LegacyPositionReader.read(positionBackupFixture().inputStream(), tracks + tracks.last().copy(uri = "duplicate")))
    }
    @Test fun negativeOutOfBoundsOrUnknownDurationAreNotImported() {
        assertNull(LegacyPositionReader.read(positionBackupFixture(seconds = -1).inputStream(), tracks))
        assertNull(LegacyPositionReader.read(positionBackupFixture(seconds = Int.MAX_VALUE).inputStream(), tracks))
        assertNull(LegacyPositionReader.read(positionBackupFixture().inputStream(), tracks.map { it.copy(durationMs = 0) }))
    }
    @Test fun roundingAtFileEndClampsToDuration() {
        assertEquals(119_500L, LegacyPositionReader.read(positionBackupFixture(seconds = 120).inputStream(), tracks.map { it.copy(durationMs = 119_500) })?.positionMs)
    }
    @Test fun truncatedAndOversizedBackupsAreIgnored() {
        val bytes = positionBackupFixture()
        (0 until bytes.size).forEach { size -> assertNull("prefix $size", LegacyPositionReader.read(bytes.copyOf(size).inputStream(), tracks)) }
        assertNull(LegacyPositionReader.read(ByteArray(65_537).inputStream(), tracks))
    }
    @Test fun unsupportedRootAndTrailingDataAreIgnored() {
        val changed = positionBackupFixture().toString(Charsets.ISO_8859_1).replace("BookDataBackup", "BookDataBroken").toByteArray(Charsets.ISO_8859_1)
        assertNull(LegacyPositionReader.read(changed.inputStream(), tracks))
        assertNull(LegacyPositionReader.read((positionBackupFixture() + byteArrayOf(0)).inputStream(), tracks))
    }
}
