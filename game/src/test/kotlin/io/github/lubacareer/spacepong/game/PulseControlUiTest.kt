package io.github.lubacareer.spacepong.game

import io.github.lubacareer.spacepong.simulation.MatchPhase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PulseControlUiTest {
    @Test fun `countdown and pause never advertise an actionable Pulse`() {
        for (phase in listOf(MatchPhase.COUNTDOWN, MatchPhase.PAUSED, MatchPhase.FINISHED)) {
            val control = PulseControlUi.create(phase, 0, 0.0, 0.0)
            assertFalse(control.enabled)
            assertNotEquals("PULSE", control.label)
        }
        assertTrue(PulseControlUi.create(MatchPhase.PLAYING, 0, 0.0, 0.0).enabled)
    }

    @Test fun `armed and recharging controls remain informative without accepting another action`() {
        val armed = PulseControlUi.create(MatchPhase.PLAYING, 0, .4, 5.8)
        assertFalse(armed.enabled)
        assertEquals("ARMED", armed.label)
        assertTrue(armed.emphasized)
        val cooling = PulseControlUi.create(MatchPhase.PLAYING, 0, 0.0, 2.3)
        assertFalse(cooling.enabled)
        assertTrue(cooling.label.contains("2.3"))
    }

    @Test fun `only the tutorial Pulse step enables its practice control`() {
        assertFalse(PulseControlUi.create(MatchPhase.PAUSED, 1, 0.0, 0.0).enabled)
        assertTrue(PulseControlUi.create(MatchPhase.PAUSED, 2, 0.0, 0.0).enabled)
        assertFalse(PulseControlUi.create(MatchPhase.PAUSED, 3, 0.0, 0.0).enabled)
    }
}
