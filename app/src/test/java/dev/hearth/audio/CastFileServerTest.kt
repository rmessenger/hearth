package dev.hearth.audio

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

class CastFileServerTest {
    private lateinit var server: CastFileServer
    private lateinit var url: String
    private val bytes = ByteArray(4096) { (it % 251).toByte() }
    private val errors = mutableListOf<String>()

    @Before fun start() {
        server = CastFileServer("127.0.0.1") { errors.add(it) }
        server.start()
        url = server.grant("book", CastFile("audio/mpeg") { OpenCastFile(ByteArrayInputStream(bytes), bytes.size.toLong()) })
    }
    @After fun stop() { server.stop() }

    @Test fun fullFileStreamsWithLengthAndCors() {
        val request = request()
        assertEquals(200, request.responseCode)
        assertEquals(4096, request.contentLength)
        assertEquals("audio/mpeg", request.contentType)
        assertEquals("*", request.getHeaderField("Access-Control-Allow-Origin"))
        assertArrayEquals(bytes, request.inputStream.use { it.readBytes() })
        assertTrue(errors.isEmpty())
        request.disconnect()
    }
    @Test fun seekStreamsOnlyTheRequestedBytes() {
        val request = request("bytes=1024-2047")
        assertEquals(206, request.responseCode)
        assertEquals("bytes 1024-2047/4096", request.getHeaderField("Content-Range"))
        assertEquals(1024, request.contentLength)
        assertArrayEquals(bytes.copyOfRange(1024, 2048), request.inputStream.use { it.readBytes() })
        request.disconnect()
    }
    @Test fun suffixAndOpenEndedRangesReachTheEnd() {
        val suffix = request("bytes=-100")
        assertEquals(206, suffix.responseCode)
        assertArrayEquals(bytes.takeLast(100).toByteArray(), suffix.inputStream.use { it.readBytes() })
        suffix.disconnect()
        val openEnded = request("bytes=4000-")
        assertEquals(206, openEnded.responseCode)
        assertArrayEquals(bytes.copyOfRange(4000, 4096), openEnded.inputStream.use { it.readBytes() })
        openEnded.disconnect()
    }
    @Test fun headReportsFullSizeWithoutStreamingABody() {
        val request = request("bytes=0-9", "HEAD")
        assertEquals(200, request.responseCode)
        assertEquals(4096, request.contentLength)
        assertEquals(0, request.inputStream.use { it.readBytes().size })
        request.disconnect()
    }
    @Test fun invalidRangesReturn416() {
        listOf("bytes=4096-", "bytes=20-10", "bytes=-0", "bytes=0-1,3-4", "bytes=abc-", "bytes=99999999999999999999-").forEach {
            val request = request(it)
            assertEquals(416, request.responseCode)
            assertEquals("bytes */4096", request.getHeaderField("Content-Range"))
            request.disconnect()
        }
    }
    @Test fun urlsCannotReadOtherFilesAndAreRevokedOnBookChange() {
        val unknown = URL("http://127.0.0.1:${server.listeningPort}/etc/passwd").openConnection() as HttpURLConnection
        assertEquals(404, unknown.responseCode)
        unknown.disconnect()
        server.retain(setOf("other-book"))
        val revoked = request()
        assertEquals(404, revoked.responseCode)
        revoked.disconnect()
    }
    @Test fun oversizedEndIsClampedAndEmptyFileHasNoRange() {
        assertEquals(ByteRange(5, 9), ByteRange.parse("bytes=5-100", 10))
        assertEquals(ByteRange(0, 9), ByteRange.parse("bytes=-100", 10))
        assertNull(ByteRange.parse("bytes=0-", 0))
    }
    private fun request(range: String? = null, method: String = "GET"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3000
            readTimeout = 3000
            range?.let { setRequestProperty("Range", it) }
        }
}
