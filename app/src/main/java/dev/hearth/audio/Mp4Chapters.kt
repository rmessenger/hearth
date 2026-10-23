package dev.hearth.audio

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Reads Nero chapter lists and QuickTime chapter text tracks without loading the audio. */
object Mp4Chapters {
    private data class Box(val type: String, val start: Long, val data: Long, val end: Long)
    fun read(channel: FileChannel): List<Chapter> {
        fun bytes(position: Long, size: Int): ByteArray {
            if (size < 0 || size > 4_000_000 || position < 0 || position + size > channel.size()) throw IOException("Invalid chapter metadata")
            val buffer = ByteBuffer.allocate(size)
            var offset = position
            while (buffer.hasRemaining()) {
                val count = channel.read(buffer, offset)
                if (count <= 0) throw IOException("Truncated chapter metadata")
                offset += count
            }
            return buffer.array()
        }
        fun boxes(start: Long, end: Long): List<Box> {
            val result = mutableListOf<Box>(); var offset = start
            while (offset + 8 <= end && result.size < 10000) {
                val header = ByteBuffer.wrap(bytes(offset, 8))
                var size = header.int.toLong() and 0xffffffffL
                val type = ByteArray(4).also(header::get).toString(Charsets.ISO_8859_1)
                var headerSize = 8
                if (size == 1L) { size = ByteBuffer.wrap(bytes(offset + 8, 8)).long; headerSize = 16 }
                if (size == 0L) size = end - offset
                if (size < headerSize || size > end - offset) break
                result += Box(type, offset, offset + headerSize, offset + size)
                offset += size
            }
            return result
        }
        fun children(box: Box): List<Box> = boxes(box.data, box.end)
        val moov = boxes(0, channel.size()).firstOrNull { it.type == "moov" } ?: return emptyList()
        val moovBoxes = children(moov)
        val chapterList = moovBoxes.firstOrNull { it.type == "udta" }?.let(::children)?.firstOrNull { it.type == "chpl" }
        if (chapterList != null) {
            val data = ByteBuffer.wrap(bytes(chapterList.data, (chapterList.end - chapterList.data).toInt()))
            if (data.remaining() < 5) return emptyList()
            val version = data.int ushr 24
            if (version == 1) { if (data.remaining() < 5) return emptyList(); data.int }
            val count = data.get().toInt() and 255
            val chapters = mutableListOf<Chapter>()
            repeat(count) {
                if (data.remaining() < 9) return chapters
                val start = data.long / 10_000
                val size = data.get().toInt() and 255
                if (data.remaining() < size) return chapters
                val title = ByteArray(size).also(data::get).toString(Charsets.UTF_8)
                chapters += Chapter(title.ifBlank { "Chapter ${it + 1}" }, start.coerceAtLeast(0))
            }
            return chapters.sortedBy { it.startMs }
        }
        val tracks = moovBoxes.filter { it.type == "trak" }
        val chapterTrackIds = tracks.flatMap { track ->
            children(track).firstOrNull { it.type == "tref" }?.let(::children)?.filter { it.type == "chap" }?.flatMap { ref ->
                val data = ByteBuffer.wrap(bytes(ref.data, (ref.end - ref.data).toInt()))
                List(data.remaining() / 4) { data.int }
            }.orEmpty()
        }.toSet()
        for (track in tracks) {
            val trackBoxes = children(track)
            val tkhd = trackBoxes.firstOrNull { it.type == "tkhd" } ?: continue
            val header = ByteBuffer.wrap(bytes(tkhd.data, minOf(32, (tkhd.end - tkhd.data).toInt())))
            val version = header.int ushr 24
            header.position(if (version == 1) 20 else 12)
            if (header.remaining() < 4 || header.int !in chapterTrackIds) continue
            val media = trackBoxes.firstOrNull { it.type == "mdia" }?.let(::children) ?: continue
            val mdhd = media.firstOrNull { it.type == "mdhd" } ?: continue
            val timeHeader = ByteBuffer.wrap(bytes(mdhd.data, minOf(32, (mdhd.end - mdhd.data).toInt())))
            val timeVersion = timeHeader.int ushr 24
            timeHeader.position(if (timeVersion == 1) 20 else 12)
            val timescale = timeHeader.int.toLong() and 0xffffffffL
            if (timescale == 0L) continue
            val sampleTable = media.firstOrNull { it.type == "minf" }?.let(::children)?.firstOrNull { it.type == "stbl" }?.let(::children) ?: continue
            fun table(name: String): ByteBuffer? = sampleTable.firstOrNull { it.type == name }?.let { box -> ByteBuffer.wrap(bytes(box.data, (box.end - box.data).toInt())) }
            val sizes = table("stsz") ?: continue
            sizes.int
            val fixedSize = sizes.int
            val count = sizes.int
            if (count !in 1..10000 || (fixedSize == 0 && sizes.remaining() < count * 4)) continue
            val sampleSizes = List(count) { if (fixedSize == 0) sizes.int else fixedSize }
            val times = table("stts") ?: continue
            times.int
            val timeEntries = times.int
            if (timeEntries !in 1..10000 || times.remaining() < timeEntries * 8) continue
            val starts = mutableListOf<Long>(); var timestamp = 0L
            repeat(timeEntries) {
                val repetitions = times.int; val delta = times.int.toLong() and 0xffffffffL
                if (repetitions < 0 || repetitions > count - starts.size) throw IOException("Invalid chapter timing")
                repeat(repetitions) { starts += timestamp * 1000 / timescale; timestamp += delta }
            }
            val chunks = table("stsc") ?: continue
            chunks.int
            val mappings = chunks.int
            if (mappings !in 1..10000 || chunks.remaining() < mappings * 12) continue
            val mapping = List(mappings) { val first = chunks.int; val samples = chunks.int; chunks.int; first to samples }
            val offsets32 = table("stco")
            val offsets = offsets32 ?: table("co64") ?: continue
            offsets.int
            val chunkCount = offsets.int
            if (chunkCount !in 1..10000 || offsets.remaining() < chunkCount * (if (offsets32 != null) 4 else 8)) continue
            val result = mutableListOf<Chapter>(); var sample = 0
            repeat(chunkCount) { chunkIndex ->
                var offset = if (offsets32 != null) offsets.int.toLong() and 0xffffffffL else offsets.long
                val samplesInChunk = mapping.lastOrNull { it.first <= chunkIndex + 1 }?.second ?: 0
                if (samplesInChunk < 0 || samplesInChunk > count - sample) throw IOException("Invalid chapter chunks")
                repeat(samplesInChunk) {
                    val size = sampleSizes[sample]
                    if (size >= 2 && sample < starts.size) {
                        val text = ByteBuffer.wrap(bytes(offset, size))
                        val length = text.short.toInt() and 65535
                        if (length <= text.remaining()) result += Chapter(ByteArray(length).also(text::get).toString(Charsets.UTF_8).ifBlank { "Chapter ${sample + 1}" }, starts[sample])
                    }
                    offset += size; sample++
                }
            }
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }
}
