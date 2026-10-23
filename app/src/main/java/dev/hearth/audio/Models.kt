package dev.hearth.audio

import java.util.Locale

data class Chapter(val title: String, val startMs: Long)
data class Track(val uri: String, val name: String, val durationMs: Long, val size: Long = 0, val modified: Long = 0, val chapters: List<Chapter> = emptyList())
data class Book(val id: String, val title: String, val author: String, val tracks: List<Track>, val cover: String? = null) {
    val durationMs: Long = tracks.sumOf { it.durationMs }
    val chapters: List<BookChapter> = tracks.flatMapIndexed { index, track ->
        val embedded = track.chapters.filter { it.startMs >= 0 && it.startMs < track.durationMs }
            .sortedBy { it.startMs }.distinctBy { it.startMs }
        val starts = if (embedded.firstOrNull()?.startMs == 0L) embedded
            else listOf(Chapter(track.name.substringBeforeLast('.'), 0)) + embedded
        starts.mapIndexed { chapterIndex, chapter ->
            BookChapter(chapter.title, index, chapter.startMs, starts.getOrNull(chapterIndex + 1)?.startMs ?: track.durationMs)
        }
    }
}
data class BookChapter(val title: String, val trackIndex: Int, val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}
data class Mark(val id: String, val trackUri: String, val positionMs: Long, val note: String, val created: Long)
data class BookmarkTarget(val bookId: String, val trackUri: String, val positionMs: Long)
data class CharacterNote(val id: String, val name: String, val note: String)
data class BookProgress(
    val trackUri: String = "", val positionMs: Long = 0, val status: String = "New",
    val speed: Float = 1f, val bookmarks: List<Mark> = emptyList(),
    val history: List<Mark> = emptyList(), val characters: List<CharacterNote> = emptyList(),
    val lastPlayed: Long = 0, val listenedMs: Long = 0,
)
data class LibraryState(val books: List<Book> = emptyList(), val scanning: Boolean = false, val error: String? = null, val scannedAt: Long = 0)
data class PlaybackState(val bookId: String = "", val trackIndex: Int = 0, val positionMs: Long = 0, val playing: Boolean = false, val speed: Float = 1f, val sleepRemainingMs: Long = 0, val endOfTrack: Boolean = false, val error: String? = null)

object BookMath {
    private val tokens = Regex("\\d+|\\D+")
    val naturalOrder: Comparator<String> = Comparator { a, b ->
        val left = tokens.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
        val right = tokens.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
        left.zip(right).firstNotNullOfOrNull { (x, y) ->
            val result = if (x.first().isDigit() && y.first().isDigit()) {
                val nx = x.trimStart('0').ifEmpty { "0" }; val ny = y.trimStart('0').ifEmpty { "0" }
                nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
            } else x.compareTo(y)
            result.takeIf { it != 0 }
        } ?: left.size.compareTo(right.size)
    }
    fun absolute(book: Book, trackUri: String, position: Long): Long {
        val index = book.tracks.indexOfFirst { it.uri == trackUri }.coerceAtLeast(0)
        return book.tracks.take(index).sumOf { it.durationMs } + position.coerceIn(0, book.tracks.getOrNull(index)?.durationMs ?: 0)
    }
    fun locate(book: Book, absoluteMs: Long): Pair<Int, Long> {
        var remaining = absoluteMs.coerceIn(0, book.durationMs)
        book.tracks.forEachIndexed { index, track ->
            if (remaining < track.durationMs || index == book.tracks.lastIndex) return index to remaining
            remaining -= track.durationMs
        }
        return 0 to 0L
    }
    fun chapterIndex(book: Book, trackIndex: Int, positionMs: Long): Int =
        book.chapters.indexOfLast { it.trackIndex < trackIndex || (it.trackIndex == trackIndex && it.startMs <= positionMs.coerceAtLeast(0)) }
    fun chapterRemaining(chapter: BookChapter, positionMs: Long, speed: Float): Long =
        ((chapter.endMs - positionMs.coerceIn(chapter.startMs, chapter.endMs)) / speed.coerceAtLeast(0.1f)).toLong()
    fun time(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
    }
    fun remaining(ms: Long, speed: Float): String {
        val minutes = (ms.coerceAtLeast(0) / speed / 60000).toInt()
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }
}
