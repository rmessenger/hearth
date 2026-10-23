package dev.hearth.audio

import android.content.Context
import android.database.ContentObserver
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.FileInputStream
import java.util.UUID

class LibraryRepository(private val context: Context) {
    val preferences = context.getSharedPreferences("hearth", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val scanLock = Mutex()
    private val mutableLibrary = MutableStateFlow(LibraryState(books = decodeBooks(preferences.getString("catalog", "[]")!!)))
    val state = mutableLibrary.asStateFlow()
    private val mutableProgress = MutableStateFlow(readProgress())
    val progress = mutableProgress.asStateFlow()
    private var refreshJob: Job? = null
    private var debounceJob: Job? = null
    private var observing = false
    val root: Uri? get() = preferences.getString("root", null)?.let(Uri::parse)
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            debounceJob?.cancel()
            debounceJob = scope.launch { delay(600); scan() }
        }
    }
    fun setRoot(uri: Uri) {
        stopWatching()
        preferences.edit().putString("root", uri.toString()).apply()
        startWatching()
    }
    fun startWatching() {
        if (refreshJob != null) return
        root?.let { uri ->
            context.contentResolver.registerContentObserver(uri, true, observer)
            context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
            observing = true
        }
        refreshJob = scope.launch { while (isActive) { scan(); delay(10_000) } }
    }
    fun stopWatching() {
        refreshJob?.cancel(); refreshJob = null
        debounceJob?.cancel()
        if (observing) context.contentResolver.unregisterContentObserver(observer)
        observing = false
    }
    fun getProgress(id: String): BookProgress = mutableProgress.value[id] ?: BookProgress()
    fun updateProgress(id: String, transform: (BookProgress) -> BookProgress) {
        val updated = transform(getProgress(id))
        mutableProgress.value = mutableProgress.value + (id to updated)
        preferences.edit().putString("progress:$id", encodeProgress(updated).toString()).apply()
    }
    fun bookmark(bookId: String, trackUri: String, position: Long, note: String) {
        updateProgress(bookId) { it.copy(bookmarks = it.bookmarks + Mark(UUID.randomUUID().toString(), trackUri, position, note, System.currentTimeMillis())) }
    }
    fun history(bookId: String, trackUri: String, position: Long, reason: String) {
        if (bookId.isEmpty() || trackUri.isEmpty()) return
        updateProgress(bookId) { it.copy(history = (listOf(Mark(UUID.randomUUID().toString(), trackUri, position, reason, System.currentTimeMillis())) + it.history).take(100)) }
    }
    suspend fun scan() {
        val rootUri = root ?: return
        if (!scanLock.tryLock()) return
        try {
            mutableLibrary.value = mutableLibrary.value.copy(scanning = true)
            val previous = mutableLibrary.value.books
            val books = withContext(Dispatchers.IO) { scanTree(rootUri, previous) }
            if (books != previous) preferences.edit().putString("catalog", encodeBooks(books).toString()).apply()
            mutableLibrary.value = LibraryState(books, scannedAt = System.currentTimeMillis())
        } catch (error: SecurityException) {
            mutableLibrary.value = mutableLibrary.value.copy(scanning = false, error = "Folder access expired. Choose your audiobook folder again in Settings.")
        } catch (error: IOException) {
            mutableLibrary.value = mutableLibrary.value.copy(scanning = false, error = "Could not read the folder. Your saved progress is safe. ${error.message.orEmpty()}")
        } finally {
            mutableLibrary.value = mutableLibrary.value.copy(scanning = false)
            scanLock.unlock()
        }
    }
    private data class Entry(val uri: Uri, val name: String?, val type: String?, val size: Long, val modified: Long) {
        val isDirectory: Boolean get() = type == DocumentsContract.Document.MIME_TYPE_DIR
        fun length(): Long = size
        fun lastModified(): Long = modified
    }
    private suspend fun scanTree(uri: Uri, previous: List<Book>): List<Book> {
        val tree = DocumentFile.fromTreeUri(context, uri) ?: throw IOException("Folder unavailable")
        if (!tree.exists()) throw IOException("Folder unavailable")
        val oldTracks = previous.flatMap { it.tracks }.associateBy { it.uri }
        val books = mutableListOf<Book>()
        suspend fun walk(folder: Entry, depth: Int) {
            currentCoroutineContext().ensureActive()
            if (depth > 32) throw IOException("Folders are nested too deeply")
            val files = children(folder.uri)
            val audio = files.filter { it.name.orEmpty().substringAfterLast('.').lowercase() in AUDIO_EXTENSIONS }
            if (audio.isNotEmpty()) {
                val tracks = audio.sortedWith { a, b -> BookMath.naturalOrder.compare(a.name.orEmpty(), b.name.orEmpty()) }.map { file ->
                    currentCoroutineContext().ensureActive()
                    val old = oldTracks[file.uri.toString()]
                    if (old != null && old.size == file.length() && old.modified == file.lastModified()) old
                    else readTrack(file)
                }
                val id = folder.uri.toString()
                val oldBook = previous.firstOrNull { it.id == id }
                val coverFile = files.firstOrNull { it.name.orEmpty().lowercase() in setOf("cover.jpg", "cover.png", "folder.jpg", "folder.png") }
                    ?: files.firstOrNull { it.type?.startsWith("image/") == true }
                var author = oldBook?.author.orEmpty()
                var cover = coverFile?.uri?.toString() ?: oldBook?.cover
                if (oldBook == null || tracks != oldBook.tracks) {
                    try {
                        MediaMetadataRetriever().use { reader ->
                            reader.setDataSource(context, Uri.parse(tracks.first().uri))
                            author = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
                                ?: reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
                            if (coverFile == null) reader.embeddedPicture?.let { bytes ->
                                val coverDir = File(context.filesDir, "covers").apply { mkdirs() }
                                val target = File(coverDir, UUID.nameUUIDFromBytes(id.toByteArray()).toString() + ".jpg")
                                target.writeBytes(bytes); cover = target.toURI().toString()
                            }
                        }
                    } catch (_: IllegalArgumentException) { /* Unsupported metadata; the audio can still be playable. */ }
                }
                books += Book(id, folder.name ?: "Untitled audiobook", author, tracks, cover)
            }
            files.filter { it.isDirectory }.forEach { walk(it, depth + 1) }
        }
        walk(Entry(tree.uri, tree.name, DocumentsContract.Document.MIME_TYPE_DIR, 0, 0), 0)
        return books.sortedWith { a, b -> BookMath.naturalOrder.compare(a.title, b.title) }
    }
    private fun children(parent: Uri): List<Entry> {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val cursor = context.contentResolver.query(childUri, columns, null, null, null)
            ?: throw IOException("The storage provider did not return the folder contents")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(Entry(DocumentsContract.buildDocumentUriUsingTree(parent, it.getString(0)), it.getString(1), it.getString(2), it.getLong(3), it.getLong(4)))
                }
            }
        }
    }
    private fun readTrack(file: Entry): Track {
        var duration = 0L
        try {
            MediaMetadataRetriever().use { reader ->
                reader.setDataSource(context, file.uri)
                duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
        } catch (_: IllegalArgumentException) { /* Keep unreadable tracks visible so playback can report the problem. */ }
        var chapters = emptyList<Chapter>()
        if (file.name.orEmpty().substringAfterLast('.').lowercase() in setOf("m4b", "m4a", "mp4")) {
            try {
                context.contentResolver.openFileDescriptor(file.uri, "r")?.use { descriptor ->
                    FileInputStream(descriptor.fileDescriptor).channel.use { chapters = Mp4Chapters.read(it) }
                }
            } catch (_: IOException) { /* Non-seekable providers still play as one file. */ }
            catch (_: java.nio.BufferUnderflowException) { chapters = emptyList() }
            catch (_: IllegalArgumentException) { chapters = emptyList() }
        }
        return Track(file.uri.toString(), file.name ?: "Audio", duration, file.length(), file.lastModified(), chapters)
    }
    private fun readProgress(): Map<String, BookProgress> = preferences.all.keys.filter { it.startsWith("progress:") }.associate { key ->
        key.removePrefix("progress:") to decodeProgress(JSONObject(preferences.getString(key, "{}")!!))
    }
    companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "m4b", "m4a", "aac", "ogg", "opus", "flac", "wav", "mp4")
        fun encodeBooks(books: List<Book>): JSONArray = JSONArray().apply {
            books.forEach { book -> put(JSONObject().put("id", book.id).put("title", book.title).put("author", book.author).put("cover", book.cover)
                .put("tracks", JSONArray().apply { book.tracks.forEach { track -> put(JSONObject().put("uri", track.uri).put("name", track.name).put("duration", track.durationMs).put("size", track.size).put("modified", track.modified).put("chapters", JSONArray().apply { track.chapters.forEach { put(JSONObject().put("title", it.title).put("start", it.startMs)) } })) } })) }
        }
        fun decodeBooks(json: String): List<Book> {
            val array = JSONArray(json)
            return (0 until array.length()).map { index ->
                val book = array.getJSONObject(index); val tracks = book.getJSONArray("tracks")
                Book(book.getString("id"), book.getString("title"), book.optString("author"), (0 until tracks.length()).map { i ->
                    val track = tracks.getJSONObject(i)
                    val chapters = track.optJSONArray("chapters") ?: JSONArray()
                    Track(track.getString("uri"), track.getString("name"), track.getLong("duration"), track.optLong("size"), track.optLong("modified"),
                        (0 until chapters.length()).map { j ->
                            val chapter = chapters.getJSONObject(j)
                            Chapter(chapter.getString("title"), chapter.getLong("start"))
                        })
                }, book.optString("cover").takeIf { it.isNotEmpty() && it != "null" })
            }
        }
        fun encodeProgress(value: BookProgress): JSONObject = JSONObject().put("track", value.trackUri).put("position", value.positionMs)
            .put("status", value.status).put("speed", value.speed).put("lastPlayed", value.lastPlayed).put("listened", value.listenedMs)
            .put("bookmarks", marksJson(value.bookmarks)).put("history", marksJson(value.history))
            .put("characters", JSONArray().apply { value.characters.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("note", it.note)) } })
        fun decodeProgress(value: JSONObject): BookProgress {
            val characters = value.optJSONArray("characters") ?: JSONArray()
            return BookProgress(value.optString("track"), value.optLong("position"), value.optString("status", "New"), value.optDouble("speed", 1.0).toFloat(),
                readMarks(value.optJSONArray("bookmarks")), readMarks(value.optJSONArray("history")),
                (0 until characters.length()).map { i -> characters.getJSONObject(i).let { CharacterNote(it.getString("id"), it.getString("name"), it.getString("note")) } },
                value.optLong("lastPlayed"), value.optLong("listened"))
        }
        private fun marksJson(marks: List<Mark>): JSONArray = JSONArray().apply { marks.forEach { put(JSONObject().put("id", it.id).put("track", it.trackUri).put("position", it.positionMs).put("note", it.note).put("created", it.created)) } }
        private fun readMarks(array: JSONArray?): List<Mark> = if (array == null) emptyList() else (0 until array.length()).map { i ->
            array.getJSONObject(i).let { Mark(it.getString("id"), it.getString("track"), it.getLong("position"), it.getString("note"), it.getLong("created")) }
        }
    }
}
