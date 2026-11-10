package dev.hearth.audio

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class Mp4DurationTest {
    @Test fun readsUnsignedAudioDurationBeyondSigned32Bits() {
        assertEquals(64_994_703L, duration(audio(header(0, 44_100, 2_866_266_417L))))
    }
    @Test fun readsVersionOneAudioDurationBeyondUnsigned32Bits() {
        assertEquals(100_000_000L, duration(audio(header(1, 44_100, 4_410_000_000L))))
    }
    @Test fun audioDurationTakesPriorityOverMovieDuration() {
        assertEquals(180_000L, duration(box("mvhd", header(0, 1000, 120_000)) + audio(header(1, 1000, 180_000))))
    }
    @Test fun usesMovieHeaderWhenAudioDurationIsUnknown() {
        assertEquals(120_000L, duration(box("mvhd", header(0, 1000, 120_000)) + audio(header(0, 1000, 0xffffffffL))))
    }
    @Test fun invalidOrMissingTimingHasNoDuration() {
        assertEquals(0L, duration(audio(header(1, 0, 1000))))
        assertEquals(0L, duration(audio(header(1, 1000, -1))))
        assertEquals(0L, duration(audio(byteArrayOf(1, 0, 0))))
        assertEquals(0L, duration(byteArrayOf()))
    }
    private fun header(version: Int, timescale: Int, ticks: Long): ByteArray = ByteArrayOutputStream().apply {
        DataOutputStream(this).use { data ->
            data.writeInt(version shl 24)
            data.write(ByteArray(8 + version * 8))
            data.writeInt(timescale)
            when (version) { 0 -> data.writeInt(ticks.toInt()); 1 -> data.writeLong(ticks) }
        }
    }.toByteArray()
    private fun audio(header: ByteArray): ByteArray = box("trak", box("mdia",
        box("hdlr", ByteArray(8) + "soun".toByteArray()) + box("mdhd", header)))
    private fun box(type: String, content: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        DataOutputStream(this).use { it.writeInt(content.size + 8); it.writeBytes(type); it.write(content) }
    }.toByteArray()
    private fun duration(contents: ByteArray): Long {
        val file = Files.createTempFile("mp4-duration", ".m4b").toFile()
        try {
            file.writeBytes(box("moov", contents))
            return RandomAccessFile(file, "r").channel.use(Mp4Chapters::readDuration)
        } finally { file.delete() }
    }
}
