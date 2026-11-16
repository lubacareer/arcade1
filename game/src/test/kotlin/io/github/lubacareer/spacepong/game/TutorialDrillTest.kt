package io.github.lubacareer.spacepong.game

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TutorialDrillTest {
    @Test fun `drag practice moves visibly at paddle speed before advancing`() {
        val drill = TutorialDrill()
        drill.update(.02f, 650f, 0f, false)
        assertEquals(380f, drill.ghostY, .01f)
        assertEquals(1, drill.stage)
        repeat(3) { drill.update(.02f, 650f, 0f, false) }
        assertEquals(2, drill.stage)
        assertFalse(drill.update(.02f, 650f, 0f, true))
        assertEquals(3, drill.stage)
        assertTrue(drill.pulseDemoProgress >= 0f)
        repeat(9) { assertFalse(drill.update(.1f, null, 0f, false)) }
        assertTrue(drill.update(.1f, null, 0f, false))
    }

    @Test fun `tap and early pulse cannot skip movement practice`() {
        val drill = TutorialDrill()
        assertFalse(drill.update(.02f, 360f, 0f, true))
        assertEquals(1, drill.stage)
        assertEquals(360f, drill.ghostY)
    }

    @Test fun `keyboard practice obeys paddle bounds and rejects invalid timing`() {
        val drill = TutorialDrill()
        drill.update(Float.NaN, null, 1f, false)
        assertEquals(360f, drill.ghostY)
        repeat(100) { drill.update(.02f, null, 1f, false) }
        assertEquals(660f, drill.ghostY, .01f)
    }
}
