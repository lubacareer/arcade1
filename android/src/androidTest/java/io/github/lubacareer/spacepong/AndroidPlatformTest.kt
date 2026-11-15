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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
}
