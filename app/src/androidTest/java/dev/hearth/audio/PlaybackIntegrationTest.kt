@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.hearth.audio

import android.content.ComponentName
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.media3.session.MediaController
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PlaybackIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val repo get() = (context.applicationContext as HearthApp).library
    private var scenario: ActivityScenario<MainActivity>? = null
    private var connectedController: MediaController? = null
    private val controller: MediaController get() = checkNotNull(connectedController)
    private lateinit var book: Book
    private val name = "HearthIntegration-${UUID.randomUUID()}"
    private val directory = "/sdcard/Audiobooks/$name"

    @Before fun prepareRealLibraryAndPlayer() = runBlocking {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        device.wait(Until.findObject(By.desc("Settings")), 10_000).click()
        device.wait(Until.findObject(By.text("Choose folder")), 5000).click()
        device.wait(Until.findObject(By.text("Audiobooks")), 5000).click()
        device.wait(Until.findObject(By.text("USE THIS FOLDER")), 5000).click()
        device.wait(Until.findObject(By.text("ALLOW")), 5000).click()
        device.wait(Until.findObject(By.desc("Back")), 5000).click()
        device.executeShellCommand("mkdir -p $directory")
        assertEquals("", device.executeShellCommand("cp /sdcard/hearth-test.mp3 $directory/chapter10.mp3"))
        assertEquals("", device.executeShellCommand("cp /sdcard/hearth-test.mp3 $directory/chapter2.mp3"))
        assertEquals("", device.executeShellCommand("cp /sdcard/hearth-test-cover.jpg $directory/cover.jpg"))
        val library = withTimeout(25_000) { repo.state.first { state -> state.books.any { it.title == name } } }
        book = library.books.first { it.title == name }
        val future = onMain { MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        connectedController = future.get(10, TimeUnit.SECONDS)
        onMain { repo.preferences.edit().putInt("rewind", 0).apply() }
    }
    @After fun cleanUpTestBook() {
        onMain { connectedController?.let { it.pause(); it.stop(); it.release() }; repo.preferences.edit().putInt("rewind", 5).apply() }
        device.executeShellCommand("rm -rf $directory")
        scenario?.close()
    }
    @Test fun autoRefreshDiscoversTracksAndCoverAndRemovesDeletedBook() = runBlocking {
        assertEquals(listOf("chapter2.mp3", "chapter10.mp3"), book.tracks.map { it.name })
        assertTrue(book.tracks.all { it.durationMs > 120_000 })
        assertTrue(book.cover!!.endsWith("cover.jpg"))
        device.executeShellCommand("rm -rf $directory")
        val refreshed = withTimeout(25_000) { repo.state.first { state -> state.books.none { it.id == book.id } } }
        assertFalse(refreshed.books.any { it.title == name })
    }
    @Test fun realPlaybackSurvivesHomeAndMediaButtonsAndRemembersSpeedAndPosition() = runBlocking {
        openBook()
        onMain { controller.setPlaybackSpeed(1.5f); controller.seekTo(0, 10_000); controller.play() }
        withTimeout(10_000) { PlaybackService.state.first { it.playing && it.bookId == book.id } }
        device.pressHome()
        withTimeout(15_000) { PlaybackService.state.first { it.playing && it.positionMs > 11_000 } }
        assertTrue(PlaybackService.state.value.playing)
        device.executeShellCommand("input keyevent 127")
        withTimeout(5000) { PlaybackService.state.first { !it.playing } }
        assertTrue(repo.getProgress(book.id).positionMs > 11_000)
        assertEquals(1.5f, repo.getProgress(book.id).speed, 0.01f)
        val stored = LibraryRepository.decodeProgress(org.json.JSONObject(repo.preferences.getString("progress:${book.id}", "{}")!!))
        assertEquals(repo.getProgress(book.id).positionMs, stored.positionMs)
        assertEquals(1.5f, stored.speed, 0.01f)
    }
    @Test fun seekCrossesRealFilesAndCreatesRestorableHistory() = runBlocking {
        openBook()
        onMain { controller.seekTo(0, book.tracks[0].durationMs - 5000) }
        command(PlaybackService.SKIP, Bundle().apply { putLong("delta", 30_000) })
        withTimeout(5000) { PlaybackService.state.first { it.trackIndex == 1 } }
        assertEquals(25_000L, PlaybackService.state.value.positionMs)
        assertTrue(repo.getProgress(book.id).history.any { it.note == "Before seeking" && it.trackUri == book.tracks[0].uri })
        onMain { repo.bookmark(book.id, book.tracks[1].uri, 25_000, "Test moment") }
        assertEquals("Test moment", repo.getProgress(book.id).bookmarks.single().note)
    }
    @Test fun sleepAtEndOfFilePausesBeforeTheNextFilePlays() = runBlocking {
        openBook()
        command(PlaybackService.SLEEP, Bundle().apply { putBoolean("endOfTrack", true) })
        onMain { controller.seekTo(0, book.tracks[0].durationMs - 1000); controller.play() }
        val state = withTimeout(10_000) { PlaybackService.state.first { it.trackIndex == 1 && !it.playing } }
        assertTrue(state.positionMs < 1000)
        assertFalse(state.endOfTrack)
    }
    @Test fun mediaBrowsersCanDiscoverAndSelectAnAudiobook() {
        val future = onMain { MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        val browser = future.get(10, TimeUnit.SECONDS)
        try {
            val rootFuture = onMain { browser.getLibraryRoot(null) }
            val root = rootFuture.get(5, TimeUnit.SECONDS)
            assertEquals(0, root.resultCode)
            val childrenFuture = onMain { browser.getChildren(root.value!!.mediaId, 0, 100, null) }
            val children = childrenFuture.get(5, TimeUnit.SECONDS)
            assertTrue(children.value!!.any { it.mediaId == book.id })
        } finally { onMain { browser.release() } }
    }
    @Test fun openingABookFromTheLibraryWaitsForAnExplicitPlay() = runBlocking {
        device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000).text = name
        device.wait(Until.findObject(By.text(name).clazz("android.widget.TextView")), 5000).click()
        withTimeout(5000) { PlaybackService.state.first { it.bookId == book.id } }
        assertTrue(device.wait(Until.hasObject(By.desc("Play")), 5000))
        delay(1200)
        assertFalse(PlaybackService.state.value.playing)
        assertFalse(onMain { controller.playWhenReady })
        assertTrue(device.hasObject(By.text("CHAPTER 1 OF 2")))
        clickPlayerControl("Next chapter")
        withTimeout(5000) { PlaybackService.state.first { it.trackIndex == 1 } }
        assertTrue(device.wait(Until.hasObject(By.text("CHAPTER 2 OF 2")), 5000))
        clickPlayerControl("Previous chapter")
        withTimeout(5000) { PlaybackService.state.first { it.trackIndex == 0 } }
        assertFalse(onMain { controller.playWhenReady })
    }
    @Test fun embeddedChaptersNavigateWhilePausedAndShowCurrentProgress() = runBlocking {
        val chapterName = "$name-Chapters"
        val chapterDirectory = "$directory/$chapterName"
        device.executeShellCommand("mkdir -p $chapterDirectory")
        assertEquals("", device.executeShellCommand("cp /sdcard/hearth-test-chapters.m4b $chapterDirectory/story.m4b"))
        val library = withTimeout(25_000) { repo.state.first { state -> state.books.any { it.title == chapterName } } }
        val chapterBook = library.books.first { it.title == chapterName }
        assertEquals(listOf("The Beginning", "The Journey", "The Homecoming"), chapterBook.chapters.map { it.title })
        device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000).text = chapterName
        device.wait(Until.findObject(By.text(chapterName).clazz("android.widget.TextView")), 5000).click()
        withTimeout(5000) { PlaybackService.state.first { it.bookId == chapterBook.id } }
        assertTrue(device.wait(Until.hasObject(By.text("CHAPTER 1 OF 3")), 5000))
        onMain { repo.preferences.edit().putInt("rewind", 5).apply(); controller.play() }
        withTimeout(5000) { PlaybackService.state.first { it.playing } }
        onMain { controller.pause() }
        withTimeout(5000) { PlaybackService.state.first { !it.playing } }
        onMain { controller.seekTo(0, 20_000) }
        withTimeout(5000) { PlaybackService.state.first { it.positionMs == 20_000L } }
        clickPlayerControl("Next chapter")
        withTimeout(5000) { PlaybackService.state.first { it.positionMs == 40_000L } }
        assertTrue(device.wait(Until.hasObject(By.text("CHAPTER 2 OF 3")), 5000))
        assertTrue(device.hasObject(By.text("The Journey")))
        assertTrue(device.hasObject(By.text("0:40 left in chapter")))
        assertFalse(onMain { controller.playWhenReady })
        delay(3200)
        clickPlayerControl("Play")
        val resumed = withTimeout(5000) { PlaybackService.state.first { it.playing } }
        assertTrue("Resume must stay in the selected chapter: $resumed", resumed.positionMs in 40_000L..42_000L)
        onMain { controller.pause(); repo.preferences.edit().putInt("rewind", 0).apply() }
        onMain { controller.setPlaybackSpeed(2f); controller.seekTo(0, 50_000) }
        assertTrue(device.wait(Until.hasObject(By.text("0:15 left in chapter")), 5000))
        clickPlayerControl("Chapters")
        assertTrue(device.wait(Until.hasObject(By.textStartsWith("Current chapter · 2 of 3")), 5000))
        device.findObject(By.desc("Go to The Homecoming")).click()
        assertTrue(device.wait(Until.hasObject(By.text("CHAPTER 3 OF 3")), 5000))
        assertEquals(80_000L, PlaybackService.state.value.positionMs)
        clickPlayerControl("Next chapter")
        assertEquals(80_000L, PlaybackService.state.value.positionMs)
        clickPlayerControl("Previous chapter")
        assertTrue(device.wait(Until.hasObject(By.text("CHAPTER 2 OF 3")), 5000))
        assertEquals(40_000L, PlaybackService.state.value.positionMs)
        onMain { controller.play() }
        withTimeout(5000) { PlaybackService.state.first { it.playing } }
        clickPlayerControl("Next chapter")
        withTimeout(5000) { PlaybackService.state.first { it.playing && it.positionMs >= 80_000 } }
        assertTrue(onMain { controller.playWhenReady })
    }
    @Test fun ordinaryResumeStillRewindsButPausedSeeksKeepTheirPosition() = runBlocking {
        openBook()
        onMain { repo.preferences.edit().putInt("rewind", 5).apply(); controller.seekTo(0, 60_000); controller.play() }
        withTimeout(5000) { PlaybackService.state.first { it.playing } }
        onMain { controller.pause() }
        val paused = withTimeout(5000) { PlaybackService.state.first { !it.playing } }
        delay(3200)
        onMain { controller.play() }
        val resumed = withTimeout(5000) { PlaybackService.state.first { it.playing } }
        assertTrue("Ordinary resume should rewind five seconds", paused.positionMs - resumed.positionMs in 4000L..5500L)
        onMain { controller.pause() }
        withTimeout(5000) { PlaybackService.state.first { !it.playing } }
        onMain { controller.seekTo(0, 80_000) }
        withTimeout(5000) { PlaybackService.state.first { it.positionMs == 80_000L } }
        delay(3200)
        onMain { controller.play() }
        val sought = withTimeout(5000) { PlaybackService.state.first { it.playing } }
        assertTrue("Resume should preserve a scrubbed position", sought.positionMs in 80_000L..82_000L)
        onMain { controller.pause() }
        withTimeout(5000) { PlaybackService.state.first { !it.playing } }
        onMain { controller.seekTo(1, 0) }
        withTimeout(5000) { PlaybackService.state.first { it.trackIndex == 1 && it.positionMs == 0L } }
        delay(3200)
        onMain { controller.play() }
        val nextFile = withTimeout(5000) { PlaybackService.state.first { it.playing } }
        assertEquals("Resume should preserve the selected file", 1, nextFile.trackIndex)
        assertTrue(nextFile.positionMs < 2000)
    }
    private fun clickPlayerControl(description: String) {
        assertTrue(UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().description(description)))
        device.wait(Until.findObject(By.desc(description)), 5000).click()
    }
    private suspend fun openBook() {
        command(PlaybackService.OPEN, Bundle().apply { putString("book", book.id); putBoolean("play", false) })
        withTimeout(5000) { PlaybackService.state.first { it.bookId == book.id } }
    }
    private fun command(action: String, args: Bundle) {
        val future = onMain { controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }
        assertEquals(0, future.get(5, TimeUnit.SECONDS).resultCode)
    }
    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
}
