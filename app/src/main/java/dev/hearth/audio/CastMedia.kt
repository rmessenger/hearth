@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.hearth.audio

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.common.images.WebImage
import java.io.FileNotFoundException
import java.io.IOException
import java.net.Inet4Address

internal class CastMedia(private val context: Context, private val reportError: (String) -> Unit) : MediaItemConverter {
    private var server: CastFileServer? = null
    private var address: String? = null
    private var renewedAt = 0L
    private val originals = mutableMapOf<String, MediaItem>()
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hearth:CastFiles").apply { setReferenceCounted(false) }

    fun start() {
        if (server != null) return
        val host = wifiAddress() ?: throw IOException("Connect your phone and Cast device to the same Wi-Fi network.")
        val created = CastFileServer(host, reportError)
        try { created.start(15_000, true) }
        catch (error: IOException) { created.stop(); throw error }
        server = created
        address = host
        renewWakeLock()
    }

    fun networkAvailable(): Boolean {
        if (server != null && SystemClock.elapsedRealtime() - renewedAt >= 10 * 60_000L) renewWakeLock()
        return address == wifiAddress()
    }

    private fun renewWakeLock() {
        wakeLock.acquire(30 * 60_000L)
        renewedAt = SystemClock.elapsedRealtime()
    }

    private fun wifiAddress(): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.allNetworks.firstNotNullOfOrNull { network ->
            if (manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) null
            else manager.getLinkProperties(network)?.linkAddresses?.firstOrNull {
                it.address is Inet4Address && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress
            }?.address?.hostAddress
        }
    }

    fun retain(items: List<MediaItem>) {
        val ids = items.flatMap { listOfNotNull(it.mediaId, it.mediaMetadata.artworkUri?.toString()) }.toSet()
        server?.retain(ids)
        originals.entries.removeAll { it.value.mediaId !in ids }
    }

    override fun toMediaQueueItem(item: MediaItem): MediaQueueItem {
        val active = checkNotNull(server) { "Cast file server must be started before transferring playback" }
        val config = checkNotNull(item.localConfiguration)
        val mime = mimeType(config.uri, item.mediaMetadata.subtitle?.toString().orEmpty())
        val url = active.grant(item.mediaId, file(config.uri, mime))
        originals[url] = item
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, item.mediaMetadata.title.toString())
            putString(MediaMetadata.KEY_ARTIST, item.mediaMetadata.artist.toString())
            putString(MediaMetadata.KEY_ALBUM_TITLE, item.mediaMetadata.albumTitle.toString())
            item.mediaMetadata.artworkUri?.let { cover ->
                addImage(WebImage(Uri.parse(active.grant(cover.toString(), file(cover, mimeType(cover, cover.toString()))))))
            }
        }
        val info = MediaInfo.Builder(url).setContentUrl(url).setContentType(mime)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED).setMetadata(metadata)
            .setStreamDuration(item.mediaMetadata.extras?.getLong("durationMs") ?: 0).build()
        return MediaQueueItem.Builder(info).build()
    }

    override fun toMediaItem(item: MediaQueueItem): MediaItem = originals[item.media?.contentId] ?: MediaItem.EMPTY

    private fun file(uri: Uri, mime: String) = CastFile(mime) {
        val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
            ?: throw FileNotFoundException("Audiobook unavailable")
        try {
            OpenCastFile(descriptor.createInputStream(), descriptor.length)
        } catch (error: IOException) { descriptor.close(); throw error }
    }

    fun stop() {
        server?.stop(); server = null; address = null
        originals.clear()
        if (wakeLock.isHeld) wakeLock.release()
    }

    companion object {
        fun mimeType(uri: Uri, name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4b", "m4a", "mp4" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> if (uri.toString().endsWith(".png", true)) "image/png" else "application/octet-stream"
        }
    }
}
