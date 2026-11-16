package io.github.lubacareer.spacepong.input

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

class TouchRouterTest {
    @Test
    fun `four pointers independently drag and pulse both players`() {
        val router = TouchRouter(localVersus = true)
        router.pulseAreas(TouchRect(100f, 0f, 100f, 100f), TouchRect(1000f, 0f, 100f, 100f))
        assertTrue(router.down(8, 250f, 400f, 360f, 360f))
        assertTrue(router.down(31, 900f, 300f, 360f, 360f))
        assertTrue(router.down(19, 150f, 50f, 360f, 360f))
        assertTrue(router.down(42, 1050f, 50f, 360f, 360f))
        router.move(8, 250f, 450f)
        router.move(31, 900f, 350f)
        assertEquals(410f, router.leftTargetY)
        assertEquals(410f, router.rightTargetY)
        assertTrue(router.consumeLeftPulse())
        assertTrue(router.consumeRightPulse())
        assertFalse(router.consumeLeftPulse())
        assertFalse(router.consumeRightPulse())
    }

    @Test
    fun `starting half owns drag even when pointer crosses center`() {
        val router = TouchRouter(localVersus = true)
        router.down(7, 200f, 400f, 300f, 500f)
        router.move(7, 1100f, 600f)
        assertEquals(500f, router.leftTargetY)
        assertNull(router.rightTargetY)
        router.up(7)
        assertNull(router.leftTargetY)
    }

    @Test
    fun `touch offset avoids snap and cancellation clears stale targets and actions`() {
        val router = TouchRouter(localVersus = false)
        router.pulseAreas(TouchRect(1000f, 0f, 120f, 120f))
        router.down(1, 200f, 600f, 250f, 360f)
        assertEquals(250f, router.leftTargetY)
        router.move(1, 200f, 590f)
        assertEquals(240f, router.leftTargetY)
        router.down(2, 1050f, 50f, 250f, 360f)
        router.cancelAll()
        assertNull(router.leftTargetY)
        assertFalse(router.consumeLeftPulse())
        assertFalse(router.move(1, 200f, 200f))
    }

    @Test
    fun `ability pointer cannot become a paddle drag`() {
        val router = TouchRouter(localVersus = false)
        router.pulseAreas(TouchRect(1000f, 0f, 120f, 120f))
        router.down(9, 1050f, 50f, 250f, 360f)
        router.move(9, 50f, 600f)
        assertNull(router.leftTargetY)
        assertTrue(router.consumeLeftPulse())
        assertFalse(router.consumeLeftPulse())
    }

    @Test
    fun `duplicate and extra drag pointers do not steal ownership`() {
        val router = TouchRouter(localVersus = true)
        router.down(1, 200f, 300f, 300f, 300f)
        assertFalse(router.down(1, 1000f, 300f, 300f, 300f))
        assertFalse(router.down(2, 250f, 500f, 300f, 300f))
        router.move(1, 200f, 400f)
        assertEquals(400f, router.leftTargetY)
        assertNull(router.rightTargetY)
    }

    @Test
    fun `outside and nonfinite touches are ignored`() {
        val router = TouchRouter(localVersus = false)
        assertFalse(router.down(1, -1f, 100f, 360f, 360f))
        assertFalse(router.down(2, Float.NaN, 100f, 360f, 360f))
        assertFalse(router.down(3, 800f, 100f, 360f, 360f))
        assertFalse(router.down(4, 100f, Float.POSITIVE_INFINITY, 360f, 360f))
    }

    @Test
    fun `canceling a pulse removes only that pointer's queued action`() {
        val router = TouchRouter(localVersus = true)
        router.pulseAreas(TouchRect(100f, 0f, 100f, 100f), TouchRect(1000f, 0f, 100f, 100f))
        router.down(1, 150f, 50f, 360f, 360f)
        router.down(2, 1050f, 50f, 360f, 360f)
        router.cancel(1)
        assertFalse(router.consumeLeftPulse())
        assertTrue(router.consumeRightPulse())
        assertFalse(router.move(1, 200f, 100f))
    }

    @Test
    fun `ordinary quick tap release still preserves its queued pulse`() {
        val router = TouchRouter(localVersus = false)
        router.pulseAreas(TouchRect(1000f, 0f, 120f, 120f))
        router.down(1, 1050f, 50f, 360f, 360f)
        router.up(1)
        assertTrue(router.consumeLeftPulse())
    }

    @Test
    fun `disabled pulse areas reject new actions and discard queued unavailable actions`() {
        val router = TouchRouter(localVersus = true)
        router.pulseAreas(TouchRect(100f, 0f, 100f, 100f), TouchRect(1000f, 0f, 100f, 100f))
        router.down(1, 150f, 50f, 360f, 360f)
        router.setPulseEnabled(false, true)
        assertFalse(router.consumeLeftPulse())
        router.up(1)
        assertFalse(router.down(3, 150f, 50f, 360f, 360f))
        assertNull(router.leftTargetY)
        assertTrue(router.down(4, 1050f, 50f, 360f, 360f))
        assertTrue(router.consumeRightPulse())
    }

    @Test
    fun `canceling a later touch preserves an earlier completed pulse`() = forEachPulse {
        down(1)
        router.up(1)
        down(2)
        router.cancel(2)
        assertTrue(consume())
        assertFalse(consume())
    }

    @Test
    fun `reused pointer cancellation preserves its previous completed tap`() = forEachPulse {
        down(1)
        router.up(1)
        down(1)
        router.cancel(1)
        assertTrue(consume())
        assertFalse(consume())
    }

    @Test
    fun `releasing a consumed pulse never queues it again`() = forEachPulse {
        down(1)
        assertTrue(consume())
        router.up(1)
        assertFalse(consume())
    }

    @Test
    fun `completed and held pulses coalesce into one consumed command`() = forEachPulse {
        down(1)
        router.up(1)
        down(2)
        assertTrue(consume())
        assertFalse(consume())
        router.up(2)
        assertFalse(consume())
    }

    @Test
    fun `disabling pulse discards completed and held edges without replay after release`() = forEachPulse {
        down(1)
        router.up(1)
        down(2)
        router.setPulseEnabled(left = right, right = !right)
        router.setPulseEnabled(true, true)
        router.up(2)
        assertFalse(consume())
        down(3)
        assertTrue(consume())
    }

    @Test
    fun `cancel all clears both completed and held pulse edges`() = forEachPulse {
        down(1)
        router.up(1)
        down(2)
        router.cancelAll()
        router.up(2)
        assertFalse(consume())
        down(3)
        assertTrue(consume())
    }

    @Test
    fun `canceling a pulse preserves the opposite completed pulse and drag ownership`() {
        val router = TouchRouter(localVersus = true)
        router.pulseAreas(TouchRect(100f, 0f, 100f, 100f), TouchRect(1000f, 0f, 100f, 100f))
        assertTrue(router.down(1, 1050f, 50f, 360f, 360f))
        router.up(1)
        assertTrue(router.down(2, 150f, 50f, 360f, 360f))
        assertTrue(router.down(3, 250f, 400f, 360f, 360f))
        router.cancel(2)
        router.cancel(99)
        assertFalse(router.consumeLeftPulse())
        assertTrue(router.consumeRightPulse())
        assertTrue(router.move(3, 250f, 450f))
        assertEquals(410f, router.leftTargetY)
    }

    private fun forEachPulse(check: PulseFixture.() -> Unit) {
        assertAll(
            "solo and both versus players",
            listOf(false to false, true to false, true to true).map { (local, right) ->
                Executable { PulseFixture(local, right).check() }
            },
        )
    }

    private class PulseFixture(local: Boolean, val right: Boolean) {
        val router = TouchRouter(localVersus = local)
        private val x = if (!local || right) 1050f else 150f

        init {
            router.pulseAreas(
                TouchRect(if (local) 100f else 1000f, 0f, 100f, 100f),
                if (local) TouchRect(1000f, 0f, 100f, 100f) else null,
            )
        }

        fun down(pointer: Int) {
            assertTrue(router.down(pointer, x, 50f, 360f, 360f))
        }

        fun consume(): Boolean = if (right) router.consumeRightPulse() else router.consumeLeftPulse()
    }
}
