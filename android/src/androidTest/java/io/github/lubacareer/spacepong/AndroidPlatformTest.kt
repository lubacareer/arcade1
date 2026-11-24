package io.github.lubacareer.spacepong

import android.graphics.Insets
import android.os.SystemClock
import android.view.WindowInsets
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.badlogic.gdx.Gdx
import io.github.lubacareer.spacepong.game.UiTarget
import io.github.lubacareer.spacepong.progression.CampaignProgression
import io.github.lubacareer.spacepong.progression.CampaignSave
import io.github.lubacareer.spacepong.progression.SaveRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AndroidPlatformTest {
    private fun <T> onGame(action: () -> T): T {
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        Gdx.app.postRunnable {
            result = runCatching(action)
            latch.countDown()
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "GL thread did not respond" }
        return result!!.getOrThrow()
    }

    private fun firstTarget(app: AndroidLauncher): UiTarget {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val target = onGame { app.game.uiTargets().firstOrNull() }
            if (target != null) return target
            SystemClock.sleep(50)
        }
        error("Game never rendered a menu target")
    }

    @Test
    fun backgroundingReleasesAudioFocusBeforeTheGameCanResume() {
        ActivityScenario.launch(AndroidLauncher::class.java).use { scenario ->
            lateinit var app: AndroidLauncher
            scenario.onActivity { app = it }
            firstTarget(app)
            scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse("Backgrounded activity must not authorize audio playback", app.hasAudioFocus())
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun insetChangesRepositionControlsWithoutASurfaceResize() {
        ActivityScenario.launch(AndroidLauncher::class.java).use { scenario ->
            lateinit var app: AndroidLauncher
            scenario.onActivity { app = it }
            val before = firstTarget(app)
            val beforeInsets = app.safeInsets()
            scenario.onActivity {
                val original = it.window.decorView.rootWindowInsets ?: WindowInsets.CONSUMED
                val changed = WindowInsets.Builder(original)
                    .setInsets(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                        Insets.of(beforeInsets.left + 240, beforeInsets.top, beforeInsets.right, beforeInsets.bottom),
                    )
                    .build()
                it.window.decorView.dispatchApplyWindowInsets(changed)
            }
            val after = onGame { app.game.uiTargets().first { it.id == before.id } }
            assertTrue(
                "Safe-area change did not move controls: before=$before, after=$after, insetsBefore=$beforeInsets, insetsAfter=${app.safeInsets()}",
                after.x > before.x + 20f,
            )
        }
    }

    @Test
    fun regainingWindowFocusRestoresMenuAudioWhenAudioFocusWasRetained() {
        ActivityScenario.launch(AndroidLauncher::class.java).use { scenario ->
            lateinit var app: AndroidLauncher
            scenario.onActivity { app = it }
            firstTarget(app)
            scenario.onActivity { it.onWindowFocusChanged(true) }
            assertTrue("Foreground activity should hold audio focus", app.hasAudioFocus())
            scenario.onActivity { it.onWindowFocusChanged(false) }
            assertTrue("Window interruption should suspend menu audio", onGame { audioSuspended(app) })
            assertTrue("Window focus loss alone should retain audio focus", app.hasAudioFocus())
            scenario.onActivity { it.onWindowFocusChanged(true) }
            assertFalse("Window focus regain should reactivate menu audio", onGame { audioSuspended(app) })
        }
    }

    private fun audioSuspended(app: AndroidLauncher): Boolean {
        val audioField = app.game.javaClass.getDeclaredField("audio").apply { isAccessible = true }
        val audio = audioField.get(app.game)
        val suspendedField = audio.javaClass.getDeclaredField("suspended").apply { isAccessible = true }
        return suspendedField.getBoolean(audio)
    }

    @Test
    fun upgradesBrickScoresOnTheDeviceFilesystem() {
        ActivityScenario.launch(AndroidLauncher::class.java).use { scenario ->
            lateinit var app: AndroidLauncher
            scenario.onActivity { app = it }
            val directory = Files.createTempDirectory(app.cacheDir.toPath(), "building-save-")
            try {
                val primary = directory.resolve("campaign.json")
                val legacy = """{"version":2,"bestMedals":[3,0,0,0],"upgrades":{"height":1,"speed":0,"pulse":0},"brickBestScores":[1000,2200,0,0,0,0]}"""
                Files.write(primary, legacy.toByteArray(Charsets.UTF_8))
                val repo = SaveRepository(directory)
                val loaded = repo.load().save
                assertEquals(mapOf("outpost" to 1000, "hangar" to 2200), loaded.brickBestScores)
                assertEquals(2, loaded.credits)

                val updated = loaded.copy(settings = loaded.settings.copy(muted = true))
                repo.save(updated)

                assertTrue(String(Files.readAllBytes(primary), Charsets.UTF_8).contains("\"version\":3"))
                assertEquals(legacy, String(Files.readAllBytes(directory.resolve("campaign.backup.json")), Charsets.UTF_8))
                assertEquals(updated, SaveRepository(directory).load().save)
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun deviceFilesystemSupportsFlushedCampaignReplacements() {
        ActivityScenario.launch(AndroidLauncher::class.java).use { scenario ->
            lateinit var app: AndroidLauncher
            scenario.onActivity { app = it }
            val fixture = Files.createTempDirectory(app.cacheDir.toPath(), "campaign-sync-")
            val directory = fixture.resolve("new-parent").resolve("campaign")
            try {
                assertFalse("First save must create the directory chain", Files.exists(directory))
                assertTrue("Android saves should use directory fsync", "posix" in directory.fileSystem.supportedFileAttributeViews())
                val repo = SaveRepository(directory)
                val first = CampaignProgression.recordVictory(CampaignSave(), 0, 4)
                val second = CampaignProgression.recordVictory(first, 0, 0)
                repo.save(first)
                repo.save(second)
                assertEquals(second, SaveRepository(directory).load().save)
            } finally {
                fixture.toFile().deleteRecursively()
            }
        }
    }
}
