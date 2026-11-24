package io.github.lubacareer.spacepong.game

import com.badlogic.gdx.Input
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BrickPlaySessionTest {
    private val play = BrickPlaySession(0)

    @Test fun `a tap launches even between rendered frames`() {
        assertTrue(play.down(2, 60f, 120f))
        assertTrue(play.up(2, 60f, 120f))
        play.update(.001f)
        assertEquals("DOCKED", play.phaseName)
        play.update(.009f)
        assertEquals("PLAYING", play.phaseName)
    }

    @Test fun `a drag moves relatively without launching or jumping to the finger`() {
        val origin = play.paddleX
        play.down(0, 50f, 74f)
        play.update(.05f)
        assertEquals(origin, play.paddleX)
        play.drag(0, 120f, 74f)
        play.up(0, 120f, 74f)
        repeat(4) { play.update(.05f) }
        assertEquals(origin + 70, play.paddleX, .01)
        assertEquals("DOCKED", play.phaseName)
    }

    @Test fun `drag returning to origin never becomes a tap`() {
        play.down(1, 640f, 100f)
        play.drag(1, 640f, 160f)
        play.up(1, 640f, 100f)
        play.update(.03f)
        assertEquals("DOCKED", play.phaseName)
    }

    @Test fun `unowned and cancelled touches cannot steer or launch`() {
        val origin = play.paddleX
        assertFalse(play.down(0, -1f, 200f))
        assertTrue(play.down(1, 600f, 100f))
        assertFalse(play.down(2, 40f, 100f))
        assertFalse(play.drag(2, 1200f, 100f))
        assertFalse(play.up(2, 40f, 100f))
        assertTrue(play.cancel(1))
        assertFalse(play.up(1, 600f, 100f))
        play.update(.05f)
        assertEquals(origin, play.paddleX)
        assertEquals("DOCKED", play.phaseName)
    }

    @Test fun `pause clears queued touches and keyboard steering before resume`() {
        val origin = play.paddleX
        play.down(0, 400f, 130f)
        play.up(0, 400f, 130f)
        play.keyDown(Input.Keys.RIGHT)
        play.pause()
        assertEquals("PAUSED", play.phaseName)
        assertTrue(play.paused)
        assertFalse(play.keyDown(Input.Keys.SPACE))
        play.update(.08f)
        play.resume()
        assertFalse(play.paused)
        play.update(.08f)
        assertEquals(origin, play.paddleX)
        assertEquals("DOCKED", play.phaseName)
        assertNull(play.won)
    }

    @Test fun `keyboard steers after a drag and space launches`() {
        play.down(0, 600f, 90f)
        play.up(0, 660f, 90f)
        repeat(3) { play.update(.05f) }
        val origin = play.paddleX
        assertTrue(play.keyDown(Input.Keys.LEFT))
        play.update(.05f)
        assertTrue(play.paddleX < origin)
        play.keyUp(Input.Keys.LEFT)
        val afterLeft = play.paddleX
        play.update(.05f)
        assertEquals(afterLeft, play.paddleX)
        assertFalse(play.keyDown(Input.Keys.Q))
        assertTrue(play.keyDown(Input.Keys.SPACE))
        play.update(.03f)
        assertEquals("PLAYING", play.phaseName)
    }

    @Test fun `a held arrow resumes immediately after an intervening tap`() {
        play.keyDown(Input.Keys.LEFT)
        play.update(.05f)
        val beforeTouch = play.paddleX
        play.down(0, 300f, 100f)
        play.up(0, 300f, 100f)
        play.update(.05f)
        assertTrue(play.paddleX < beforeTouch)
        assertEquals("PLAYING", play.phaseName)
    }

    @Test fun `a key repeat cannot interrupt the captured drag and a held arrow resumes on release`() {
        val origin = play.paddleX
        play.keyDown(Input.Keys.RIGHT)
        play.down(1, 600f, 90f)
        play.drag(1, 525f, 90f)
        play.keyDown(Input.Keys.RIGHT)
        play.update(.05f)
        val duringDrag = play.paddleX
        assertTrue(duringDrag < origin)
        play.up(1, 525f, 90f)
        play.update(.05f)
        assertTrue(play.paddleX > duringDrag)
        assertEquals("DOCKED", play.phaseName)
    }

    @Test fun `cancelling a drag with both arrows down respects remaining held direction`() {
        val origin = play.paddleX
        play.keyDown(Input.Keys.RIGHT)
        play.keyDown(Input.Keys.LEFT)
        play.down(1, 300f, 100f)
        play.drag(1, 390f, 100f)
        play.cancel(1)
        play.update(.05f)
        assertEquals(origin, play.paddleX)
        play.keyUp(Input.Keys.RIGHT)
        play.update(.05f)
        assertTrue(play.paddleX < origin)
        assertEquals("DOCKED", play.phaseName)
    }

    @Test fun `each lost life emits exactly one impact including the end of the attempt`() {
        val impacts = mutableListOf<SoundEffect>()
        val session = BrickPlaySession(0) { sound, strong ->
            if (strong == true) impacts += sound
        }
        repeat(3) {
            assertEquals("DOCKED", session.phaseName)
            assertTrue(session.keyDown(Input.Keys.SPACE))
            session.update(.02f)
            session.keyUp(Input.Keys.SPACE)
            // Park at the far edge after launch so the ball can fall past the paddle.
            val steer = if (session.paddleX > 640) Input.Keys.LEFT else Input.Keys.RIGHT
            session.keyDown(steer)
            var frames = 0
            while (session.phaseName == "PLAYING" && frames++ < 1000) session.update(.05f)
            session.keyUp(steer)
        }
        assertEquals(false, session.won)
        assertEquals(List(3) { SoundEffect.GOAL }, impacts)
    }
}
