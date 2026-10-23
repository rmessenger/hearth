@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.hearth.audio

import android.app.PendingIntent
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.*
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

class PlaybackService : MediaLibraryService(), SensorEventListener {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var repo: LibraryRepository
    private val handler = Handler(Looper.getMainLooper())
    private var sleepDeadline = 0L
    private var sleepDuration = 0L
    private var sleepExpired = 0L
    private var endOfTrack = false
    private var lastShake = 0L
    private var pausedAt = 0L
    private var lastTick = 0L
    private var saveTicks = 0
    private var listenedDeltaMs = 0L
    private var equalizer: Equalizer? = null
    private var loudness: LoudnessEnhancer? = null
    private var currentBookId = ""
    private var playbackError: String? = null
    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (player.isPlaying && lastTick > 0 && currentBookId.isNotEmpty()) {
                val elapsed = (now - lastTick).coerceAtMost(2000)
                listenedDeltaMs += elapsed
            }
            lastTick = now
            if (sleepDeadline > 0 && now >= sleepDeadline) {
                val wasPlaying = player.isPlaying
                player.pause(); sleepDeadline = 0; sleepExpired = if (wasPlaying) now else 0
            }
            player.volume = if (sleepDeadline > 0 && player.isPlaying) ((sleepDeadline - now).toFloat() / 15_000).coerceIn(0f, 1f) else 1f
            if (++saveTicks >= 5) { save(); saveTicks = 0 }
            publish()
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        repo = (application as HearthApp).library
        player = ExoPlayer.Builder(this).setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(30_000).build()
        player.skipSilenceEnabled = repo.preferences.getBoolean("skipSilence", false)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) pausedAt = SystemClock.elapsedRealtime()
                save(); publish(); HearthWidget.updateAll(this@PlaybackService)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && endOfTrack) {
                    player.pause(); endOfTrack = false
                }
                currentBookId = mediaItem?.mediaMetadata?.extras?.getString("bookId").orEmpty()
                save(); publish()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK && currentBookId.isNotEmpty()) {
                    repo.history(currentBookId, oldPosition.mediaItem?.localConfiguration?.uri?.toString().orEmpty(), oldPosition.positionMs, "Before seeking")
                }
                save(); publish()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && currentBookId.isNotEmpty()) {
                    save(); repo.updateProgress(currentBookId) { it.copy(status = "Finished") }
                    endOfTrack = false; sleepDeadline = 0
                }
                publish()
            }
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                if (currentBookId.isNotEmpty()) repo.updateProgress(currentBookId) { it.copy(speed = playbackParameters.speed) }
                publish()
            }
            override fun onPlayerError(error: PlaybackException) {
                playbackError = "This audio could not be played. Check that the file is complete and the folder is accessible. (${error.errorCodeName})"
                publish()
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
                equalizer?.release(); loudness?.release()
                try {
                    equalizer = Equalizer(0, audioSessionId)
                    loudness = LoudnessEnhancer(audioSessionId)
                    applyEffects()
                } catch (_: UnsupportedOperationException) {
                    playbackError = "This device does not support the selected audio effects. Normal playback is available."
                }
            }
        })
        val forwardingPlayer = object : ForwardingPlayer(player) {
            override fun play() {
                playbackError = null
                val rewind = repo.preferences.getInt("rewind", 5)
                if (pausedAt > 0 && SystemClock.elapsedRealtime() - pausedAt > 3000 && rewind > 0) skip(-rewind * 1000L)
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0)
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                super.play()
            }
        }
        session = MediaLibrarySession.Builder(this, forwardingPlayer, Callback()).setSessionActivity(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ).build()
        (getSystemService(SENSOR_SERVICE) as SensorManager).let { sensors ->
            sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
        restoreLastBook()
        handler.post(ticker)
    }
    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            if (controller.packageName == packageName) listOf(OPEN, SLEEP, SKIP, EFFECTS).forEach { commands.add(SessionCommand(it, Bundle.EMPTY)) }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands.build()).build()
        }
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                OPEN -> {
                    val book = repo.state.value.books.firstOrNull { it.id == args.getString("book") }
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    openBook(book, args.getBoolean("play", false))
                }
                SLEEP -> {
                    sleepDuration = args.getLong("minutes") * 60_000
                    sleepDeadline = if (sleepDuration > 0) SystemClock.elapsedRealtime() + sleepDuration else 0
                    sleepExpired = 0; endOfTrack = args.getBoolean("endOfTrack")
                }
                SKIP -> skip(args.getLong("delta"))
                EFFECTS -> { player.skipSilenceEnabled = repo.preferences.getBoolean("skipSilence", false); applyEffects() }
            }
            publish()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(MediaItem.Builder().setMediaId("library").setMediaMetadata(MediaMetadata.Builder().setTitle("Hearth").setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS).build()).build(), params))
        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items = if (parentId == "library") repo.state.value.books.map { book ->
                MediaItem.Builder().setMediaId(book.id).setMediaMetadata(MediaMetadata.Builder().setTitle(book.title).setArtist(book.author).setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK).setArtworkUri(book.cover?.let(Uri::parse)).build()).build()
            } else emptyList()
            return Futures.immediateFuture(LibraryResult.ofItemList(items.drop(page * pageSize).take(pageSize), params))
        }
        override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val requested = mediaItems.firstOrNull()
            val query = requested?.requestMetadata?.searchQuery.orEmpty()
            val book = repo.state.value.books.firstOrNull { it.id == requested?.mediaId }
                ?: repo.state.value.books.firstOrNull { query.isNotBlank() && (it.title.contains(query, true) || it.author.contains(query, true)) }
                ?: return Futures.immediateFailedFuture(IllegalArgumentException("Choose a book from the Hearth library"))
            save(); currentBookId = book.id
            repo.preferences.edit().putString("currentBook", book.id).apply()
            val progress = repo.getProgress(book.id)
            player.setPlaybackSpeed(progress.speed)
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items(book), book.tracks.indexOfFirst { it.uri == progress.trackUri }.coerceAtLeast(0), progress.positionMs))
        }
        override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val book = repo.state.value.books.firstOrNull { it.id == repo.preferences.getString("currentBook", null) }
                ?: return Futures.immediateFailedFuture(IllegalStateException("Choose an audiobook first"))
            currentBookId = book.id
            val progress = repo.getProgress(book.id)
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items(book), book.tracks.indexOfFirst { it.uri == progress.trackUri }.coerceAtLeast(0), progress.positionMs))
        }
    }
    private fun items(book: Book): List<MediaItem> = book.tracks.map { track ->
        MediaItem.Builder().setMediaId(track.uri).setUri(track.uri).setMediaMetadata(MediaMetadata.Builder()
            .setTitle(book.title).setArtist(book.author.ifBlank { track.name }).setAlbumTitle(book.title).setSubtitle(track.name)
            .setArtworkUri(book.cover?.let(Uri::parse)).setExtras(Bundle().apply { putString("bookId", book.id) }).setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK).build()).build()
    }
    private fun restoreLastBook() {
        repo.state.value.books.firstOrNull { it.id == repo.preferences.getString("currentBook", null) }?.let { openBook(it, false) }
    }
    private fun openBook(book: Book, play: Boolean) {
        if (currentBookId == book.id && player.mediaItemCount > 0) { if (play) session.player.play(); return }
        save(); player.pause(); currentBookId = ""
        val progress = repo.getProgress(book.id)
        val index = book.tracks.indexOfFirst { it.uri == progress.trackUri }.coerceAtLeast(0)
        player.setMediaItems(items(book), index, progress.positionMs)
        currentBookId = book.id
        player.setPlaybackSpeed(progress.speed)
        playbackError = null; pausedAt = 0
        repo.preferences.edit().putString("currentBook", book.id).apply()
        player.prepare(); if (play) player.play()
        repo.history(book.id, book.tracks[index].uri, progress.positionMs, "Opened book")
        publish()
    }
    private fun save() {
        val uri = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
        val bookId = player.currentMediaItem?.mediaMetadata?.extras?.getString("bookId") ?: return
        val listened = listenedDeltaMs
        listenedDeltaMs = 0
        repo.updateProgress(bookId) {
            it.copy(listenedMs = it.listenedMs + listened, trackUri = uri, positionMs = player.currentPosition.coerceAtLeast(0), speed = player.playbackParameters.speed,
                status = if (player.isPlaying) "Started" else it.status,
                lastPlayed = if (player.isPlaying) System.currentTimeMillis() else it.lastPlayed)
        }
    }
    private fun skip(delta: Long) {
        val book = repo.state.value.books.firstOrNull { it.id == currentBookId } ?: return
        val uri = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
        val (index, position) = BookMath.locate(book, BookMath.absolute(book, uri, player.currentPosition) + delta)
        player.seekTo(index, position)
    }
    private fun applyEffects() {
        val preset = repo.preferences.getInt("equalizer", 0)
        equalizer?.let { effect ->
            for (band in 0 until effect.numberOfBands) {
                val level = when (preset) { 1 -> if (band in 1..3) 300 else -150; 2 -> if (band < 2) 400 else 0; else -> 0 }
                effect.setBandLevel(band.toShort(), level.toShort())
            }
            effect.enabled = preset != 0
        }
        loudness?.let { effect ->
            val boost = repo.preferences.getInt("boost", 0)
            effect.setTargetGain(boost * 100); effect.enabled = boost > 0
        }
    }
    private fun publish() {
        mutableState.value = PlaybackState(currentBookId, player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition.coerceAtLeast(0), player.isPlaying, player.playbackParameters.speed,
            if (sleepDeadline > 0) (sleepDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) else 0, endOfTrack, playbackError)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "dev.hearth.TOGGLE" -> if (player.isPlaying) player.pause() else session.player.play()
            "dev.hearth.BACK" -> skip(-10_000)
            "dev.hearth.FORWARD" -> skip(30_000)
        }
        return super.onStartCommand(intent, flags, startId)
    }
    override fun onTaskRemoved(rootIntent: Intent?) { if (!player.playWhenReady) stopSelf() }
    override fun onSensorChanged(event: SensorEvent) {
        if (!repo.preferences.getBoolean("shake", true)) return
        val force = sqrt(event.values.sumOf { (it * it).toDouble() }) / SensorManager.GRAVITY_EARTH
        val now = SystemClock.elapsedRealtime()
        if (force > 2.5 && now - lastShake > 1500) {
            lastShake = now
            if (sleepDeadline > 0) sleepDeadline = now + sleepDuration
            else if (sleepExpired > 0 && now - sleepExpired < 30_000) { sleepDeadline = now + sleepDuration; sleepExpired = 0; player.play() }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onDestroy() {
        save(); handler.removeCallbacksAndMessages(null)
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        equalizer?.release(); loudness?.release(); session.release(); player.release()
        mutableState.value = PlaybackState()
        super.onDestroy()
    }
    companion object {
        const val OPEN = "dev.hearth.OPEN"
        const val SLEEP = "dev.hearth.SLEEP"
        const val SKIP = "dev.hearth.SKIP"
        const val EFFECTS = "dev.hearth.EFFECTS"
        private val mutableState = MutableStateFlow(PlaybackState())
        val state = mutableState.asStateFlow()
    }
}
