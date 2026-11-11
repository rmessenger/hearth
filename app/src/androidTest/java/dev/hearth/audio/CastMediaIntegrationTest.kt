@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.hearth.audio

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Socket
import java.net.URI

@RunWith(AndroidJUnit4::class)
class CastMediaIntegrationTest {
    @Test fun castQueuePreservesLocalIdentityAndServesAudioAndCoverUntilDisconnected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = File.createTempFile("cast-test", ".mp3", context.cacheDir)
        val cover = File.createTempFile("cast-cover", ".jpg", context.cacheDir)
        val bytes = ByteArray(1024) { (it % 251).toByte() }
        audio.writeBytes(bytes)
        cover.writeBytes(byteArrayOf(1, 2, 3, 4))
        val errors = mutableListOf<String>()
        val media = CastMedia(context) { errors.add(it) }
        try {
            media.start()
            val item = MediaItem.Builder().setMediaId("local-track").setUri(Uri.fromFile(audio))
                .setMediaMetadata(MediaMetadata.Builder().setTitle("Cast test").setSubtitle("chapter.mp3")
                    .setArtist("Test narrator").setAlbumTitle("Cast test").setArtworkUri(Uri.fromFile(cover))
                    .setExtras(Bundle().apply { putString("bookId", "local-book"); putLong("durationMs", 123_000) }).build()).build()
            val queued = media.toMediaQueueItem(item)
            assertEquals(item, media.toMediaItem(queued))
            val info = requireNotNull(queued.media)
            assertEquals("audio/mpeg", info.contentType)
            assertEquals(123_000L, info.streamDuration)
            assertFalse(info.contentId.contains(audio.name))
            val response = get(info.contentId, "bytes=100-199")
            assertTrue(response.first.startsWith("HTTP/1.1 206"))
            assertArrayEquals(bytes.copyOfRange(100, 200), response.second)
            val artwork = requireNotNull(info.metadata).images.single().url.toString()
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), get(artwork).second)
            media.retain(emptyList())
            assertTrue(get(info.contentId).first.startsWith("HTTP/1.1 404"))
            assertTrue(errors.isEmpty())
        } finally {
            media.stop()
            audio.delete()
            cover.delete()
        }
    }

    private fun get(url: String, range: String? = null): Pair<String, ByteArray> {
        val uri = URI(url)
        return Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 5000
            val headers = "GET ${uri.path} HTTP/1.1\r\nHost: ${uri.host}\r\nConnection: close\r\n" +
                (range?.let { "Range: $it\r\n" } ?: "") + "\r\n"
            socket.getOutputStream().write(headers.toByteArray())
            val response = socket.getInputStream().readBytes()
            val text = response.toString(Charsets.ISO_8859_1)
            val boundary = text.indexOf("\r\n\r\n")
            check(boundary >= 0)
            text.substring(0, boundary) to response.copyOfRange(boundary + 4, response.size)
        }
    }
}
