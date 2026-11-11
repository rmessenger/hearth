@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.hearth.audio

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlayerLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val book = Book("layout", "A Very Long Audiobook Title That Runs Across Several Lines: The Remarkable Journey Home, Volume Four of a Very Long Series",
        "A Narrator With a Long Name", listOf(Track("audio", "book.m4b", 180_000, chapters = listOf(
            Chapter("Beginning", 0), Chapter("A chapter with a particularly long descriptive title", 45_000), Chapter("Homecoming", 90_000)))), coverAspectRatio = 307f / 475f)

    @Test fun portraitKeepsAllControlsVisible() = verify(360, 650, 1f)
    @Test fun smallPortraitKeepsAllControlsVisible() = verify(320, 380, 1f)
    @Test fun landscapeKeepsAllControlsVisible() = verify(640, 240, 1f)
    @Test fun largeTextPortraitKeepsAllControlsVisible() = verify(320, 450, 2f)
    @Test fun largeTextLandscapeKeepsAllControlsVisible() = verify(640, 240, 2f)

    @Test fun browsingWithMiniPlayerKeepsControlsVisible() = verify(360, 450, 1f, browsing = true)
    @Test fun browsingWithLargeTextKeepsControlsVisible() = verify(320, 380, 2f, browsing = true)

    private fun verify(width: Int, height: Int, fontScale: Float, browsing: Boolean = false) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize()) {
                            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                                Box(Modifier.offset(y = 100.dp).size(width.dp, height.dp).semantics { contentDescription = "Player viewport" }) {
                                    PlayerScreen(book, PlaybackState(bookId = book.id, positionMs = 60_000), {}, {}, {}, {}, browsing = browsing)
                                }
                            }
                        }
                    }
                }
            }
            val viewport = device.wait(Until.findObject(By.desc("Player viewport")), 5000).visibleBounds
            device.waitForIdle()
            device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), "player-$width-$height-$fontScale.png"))
            listOf("Play", "Previous chapter", "Next chapter", "Back 10 seconds", "Forward 30 seconds", "Chapters", "Chapter progress", "More playback options").forEach { name ->
                val control = device.wait(Until.findObject(By.desc(name)), 3000)
                assertNotNull("Missing $name at $width × $height / $fontScale", control)
                val target = generateSequence(control) { it.parent }.firstOrNull { it.isClickable } ?: control
                val bounds = target.visibleBounds
                assertTrue("Clipped $name: $bounds outside $viewport", viewport.contains(bounds))
                assertTrue("Undersized $name: $bounds", bounds.width() >= 40 && bounds.height() >= 40)
            }
            assertFalse(device.hasObject(By.pkg(instrumentation.targetContext.packageName).scrollable(true)))
            device.findObject(By.desc("More playback options")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Bookmarks")), 3000))
            assertTrue(device.hasObject(By.text("History")))
            assertTrue(device.hasObject(By.text("Characters")))
            device.pressBack()
        }
    }
}
