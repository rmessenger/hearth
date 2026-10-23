package dev.hearth.audio

import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class Mp4ChaptersTest {
    @Test fun readsRealM4bChapterNamesAndTimestamps() {
        val file = Files.createTempFile("chapters", ".m4b").toFile()
        try {
            file.writeBytes(javaClass.getResourceAsStream("/chapters.m4b")!!.readBytes())
            RandomAccessFile(file, "r").channel.use { channel ->
                assertEquals(listOf(Chapter("The Shore", 0), Chapter("The Crossing", 45_000), Chapter("Home Again", 90_000)), Mp4Chapters.read(channel))
            }
        } finally { file.delete() }
    }
    @Test fun readsQuickTimeChapterTrackWithoutNeroMetadata() {
        val file = Files.createTempFile("quicktime-chapters", ".m4b").toFile()
        try {
            file.writeBytes(javaClass.getResourceAsStream("/quicktime-chapters.m4b")!!.readBytes())
            RandomAccessFile(file, "r").channel.use { channel ->
                assertEquals(listOf(Chapter("The Shore", 0), Chapter("The Crossing", 45_000), Chapter("Home Again", 90_000)), Mp4Chapters.read(channel))
            }
        } finally { file.delete() }
    }
    @Test fun nonMp4FileHasNoChapters() {
        val file = Files.createTempFile("not-mp4", ".bin").toFile()
        try {
            file.writeText("not a media file")
            RandomAccessFile(file, "r").channel.use { assertEquals(emptyList<Chapter>(), Mp4Chapters.read(it)) }
        } finally { file.delete() }
    }
}
