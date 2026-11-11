package dev.hearth.audio

import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore

internal data class ByteRange(val start: Long, val end: Long) {
    val length: Long get() = end - start + 1

    companion object {
        fun parse(header: String, size: Long): ByteRange? {
            if (size <= 0 || !header.startsWith("bytes=")) return null
            val parts = header.removePrefix("bytes=").split('-')
            if (parts.size != 2) return null
            if (parts[0].isEmpty()) {
                val count = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
                return ByteRange((size - count).coerceAtLeast(0), size - 1)
            }
            val start = parts[0].toLongOrNull()?.takeIf { it in 0 until size } ?: return null
            val end = if (parts[1].isEmpty()) size - 1 else parts[1].toLongOrNull() ?: return null
            if (end < start) return null
            return ByteRange(start, end.coerceAtMost(size - 1))
        }
    }
}

internal data class OpenCastFile(val stream: InputStream, val length: Long)
internal data class CastFile(val mimeType: String, val open: () -> OpenCastFile)

/** Only opaque URLs explicitly granted by the player can read a file. */
internal class CastFileServer(address: String, private val reportError: (String) -> Unit) : NanoHTTPD(address, 0) {
    private val files = ConcurrentHashMap<String, CastFile>()
    private val paths = mutableMapOf<String, String>()
    private val host = address
    private val clients = ConcurrentHashMap.newKeySet<ClientHandler>()
    private val slots = Semaphore(8)
    private val workers = Executors.newFixedThreadPool(8)

    init {
        setAsyncRunner(object : AsyncRunner {
            override fun exec(client: ClientHandler) {
                if (!slots.tryAcquire()) { client.close(); return }
                clients.add(client)
                try { workers.execute(client) }
                catch (_: RejectedExecutionException) { client.close(); closed(client) }
            }
            override fun closed(client: ClientHandler) {
                if (clients.remove(client)) slots.release()
            }
            override fun closeAll() { clients.toList().forEach { it.close() } }
        })
    }

    @Synchronized fun grant(id: String, file: CastFile): String {
        val path = paths.getOrPut(id) { "/${UUID.randomUUID()}/${UUID.randomUUID()}" }
        files[path] = file
        return "http://$host:$listeningPort$path"
    }

    @Synchronized fun retain(ids: Set<String>) {
        paths.keys.filter { it !in ids }.forEach { id -> files.remove(paths.remove(id)) }
    }

    override fun serve(session: IHTTPSession): Response {
        val file = files[session.uri] ?: return reply(Response.Status.NOT_FOUND, "Not found")
        if (session.method == Method.OPTIONS) return reply(Response.Status.OK, "")
        if (session.method != Method.GET && session.method != Method.HEAD) {
            return reply(Response.Status.METHOD_NOT_ALLOWED, "Use GET or HEAD")
        }
        try {
            val opened = file.open()
            val stream = opened.stream
            try {
                val size = opened.length
                if (size < 0) {
                    stream.close()
                    reportError("This file provider cannot supply a seekable file for casting. Copy the book to a local folder first.")
                    return reply(Response.Status.SERVICE_UNAVAILABLE, "File size unavailable")
                }
                val header = session.headers["range"].takeUnless { session.method == Method.HEAD }
                val range = header?.let { ByteRange.parse(it, size) }
                if (header != null && range == null) {
                    stream.close()
                    return reply(Response.Status.RANGE_NOT_SATISFIABLE, "Invalid range").apply { addHeader("Content-Range", "bytes */$size") }
                }
                val length = range?.length ?: size
                if (session.method == Method.HEAD) stream.close()
                else {
                    var remaining = range?.start ?: 0
                    while (remaining > 0) {
                        val skipped = stream.skip(remaining)
                        if (skipped > 0) remaining -= skipped
                        else if (stream.read() >= 0) remaining--
                        else throw IOException("File ended before requested position")
                    }
                }
                return newFixedLengthResponse(if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT,
                    file.mimeType, if (session.method == Method.HEAD) InputStream.nullInputStream() else stream, length).apply {
                    commonHeaders(this)
                    addHeader("Accept-Ranges", "bytes")
                    if (range != null) addHeader("Content-Range", "bytes ${range.start}-${range.end}/$size")
                }
            } catch (error: IOException) { stream.close(); throw error }
        } catch (_: IOException) {
            reportError("The Cast device could not read this file. Check that the audiobook folder is still accessible.")
            return reply(Response.Status.NOT_FOUND, "File unavailable")
        } catch (_: SecurityException) {
            reportError("Folder access was revoked. Choose your audiobook folder again before casting.")
            return reply(Response.Status.FORBIDDEN, "File access denied")
        }
    }

    private fun reply(status: Response.Status, message: String): Response =
        newFixedLengthResponse(status, MIME_PLAINTEXT, message).apply { commonHeaders(this) }

    private fun commonHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Range, Content-Type")
        response.addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges")
        response.addHeader("Cache-Control", "no-store")
    }

    override fun stop() {
        super.stop()
        workers.shutdownNow()
        files.clear()
        paths.clear()
    }
}
