package io.github.lubacareer.spacepong

import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.badlogic.gdx.Gdx
import io.github.lubacareer.spacepong.game.GameDiagnostics
import io.github.lubacareer.spacepong.game.UiTarget
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class AndroidSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)

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

    private fun awaitPage(app: AndroidLauncher, page: String): GameDiagnostics {
        val deadline = SystemClock.uptimeMillis() + 10000
        var state = onGame { app.game.diagnostics() }
        while (state.page != page && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
            state = onGame { app.game.diagnostics() }
        }
        assertEquals(page, state.page)
        return state
    }

    private fun tap(app: AndroidLauncher, id: String) {
        val deadline = SystemClock.uptimeMillis() + 10000
        var target: UiTarget? = null
        while (target == null && SystemClock.uptimeMillis() < deadline) {
            target = onGame { app.game.uiTargets().firstOrNull { it.id == id && it.enabled } }
            if (target == null) SystemClock.sleep(50)
        }
        val found = checkNotNull(target) { "Missing enabled target: $id" }
        assertTrue(device.click((found.x + found.width / 2).toInt(), (found.y + found.height / 2).toInt()))
        SystemClock.sleep(150)
    }

    private fun dismissSystemTutorial() {
        device.findObject(By.text(Pattern.compile("got it", Pattern.CASE_INSENSITIVE)))?.click()
        SystemClock.sleep(150)
    }

    private fun awaitWindowFocus(app: AndroidLauncher) {
        val deadline = SystemClock.uptimeMillis() + 10000
        var focused = false
        while (!focused && SystemClock.uptimeMillis() < deadline) {
            device.findObject(By.text(Pattern.compile("got it", Pattern.CASE_INSENSITIVE)))?.click()
            instrumentation.runOnMainSync { focused = app.hasWindowFocus() }
            if (!focused) SystemClock.sleep(50)
        }
        assertTrue("The resumed window must receive input before tapping Resume", focused)
    }

    @Test fun launchesOfflineWithLandscapeMenuAndSurvivesBackground() {
        ActivityScenario.launch(AndroidLauncher::class.java).use { scenario ->
            var activity: AndroidLauncher? = null
            scenario.onActivity { activity = it }
            val app = activity!!
            SystemClock.sleep(1500)
            dismissSystemTutorial()
            assertTrue(device.displayWidth > device.displayHeight)
            assertTrue("Menu must expose usable targets", onGame { app.game.uiTargets().isNotEmpty() })
            val permissions = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
            assertFalse(permissions.contains("android.permission.INTERNET"))
            assertFalse(permissions.contains("com.android.vending.BILLING"))
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            SystemClock.sleep(500)
            assertNotNull(onGame { app.game.diagnostics().page })
        }
    }

    @Test fun localVersusSupportsFourPointersAndExplicitResume() {
        ActivityScenario.launch(AndroidLauncher::class.java).use { scenario ->
            var activity: AndroidLauncher? = null
            scenario.onActivity { activity = it }
            val app = activity!!
            SystemClock.sleep(1200)
            dismissSystemTutorial()
            if (onGame { app.game.diagnostics().page } == "MAP") tap(app, "home")
            tap(app, "local")
            awaitPage(app, "MATCH")
            val playingDeadline = SystemClock.uptimeMillis() + 15000
            while (onGame { app.game.diagnostics().matchPhase } != "PLAYING" && SystemClock.uptimeMillis() < playingDeadline) {
                SystemClock.sleep(50)
            }
            assertEquals("PLAYING", onGame { app.game.diagnostics().matchPhase })
            val before = onGame { app.game.diagnostics() }
            val targets = onGame { app.game.uiTargets() }
            val leftPulse = targets.first { it.id == "pulse-left" }
            val rightPulse = targets.first { it.id == "pulse-right" }
            val xs = floatArrayOf(
                device.displayWidth * .25f,
                device.displayWidth * .75f,
                leftPulse.x + leftPulse.width / 2,
                rightPulse.x + rightPulse.width / 2,
            )
            val ys = floatArrayOf(
                device.displayHeight * .5f,
                device.displayHeight * .5f,
                leftPulse.y + leftPulse.height / 2,
                rightPulse.y + rightPulse.height / 2,
            )
            val downTime = SystemClock.uptimeMillis()
            inject(downTime, MotionEvent.ACTION_DOWN, xs, ys, 1)
            inject(downTime, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), xs, ys, 2)
            inject(downTime, MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), xs, ys, 3)
            inject(downTime, MotionEvent.ACTION_POINTER_DOWN or (3 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), xs, ys, 4)
            ys[0] = device.displayHeight * .3f
            ys[1] = device.displayHeight * .7f
            inject(downTime, MotionEvent.ACTION_MOVE, xs, ys, 4)
            SystemClock.sleep(250)
            val after = onGame { app.game.diagnostics() }
            assertTrue("Left drag must move its paddle", after.leftPaddleY > before.leftPaddleY + 30)
            assertTrue("Right drag must move its paddle", after.rightPaddleY < before.rightPaddleY - 30)
            assertTrue("Left Pulse must activate", after.leftPulseCooldown > 0)
            assertTrue("Right Pulse must activate", after.rightPulseCooldown > 0)
            inject(downTime, MotionEvent.ACTION_CANCEL, xs, ys, 4)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            awaitWindowFocus(app)
            val paused = awaitPage(app, "PAUSE")
            SystemClock.sleep(350)
            val stillPaused = onGame { app.game.diagnostics() }
            assertEquals(paused.leftScore, stillPaused.leftScore)
            assertEquals(paused.rightScore, stillPaused.rightScore)
            assertEquals("PAUSED", stillPaused.matchPhase)
            tap(app, "resume")
            awaitPage(app, "MATCH")
            assertEquals("COUNTDOWN", onGame { app.game.diagnostics().matchPhase })
        }
    }

    private fun inject(downTime: Long, action: Int, xs: FloatArray, ys: FloatArray, count: Int) {
        val properties = Array(count) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(count) { index ->
            MotionEvent.PointerCoords().apply {
                x = xs[index]
                y = ys[index]
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, count, properties,
            coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }
}
