package io.github.lubacareer.spacepong.simulation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CountdownPulseTest {
    @Test fun `release during a scored countdown permits a fresh pulse on the first playing tick`() {
        val engine = MatchEngine(MatchConfig(mode = MatchMode.LOCAL_VERSUS))
        repeat(180) { engine.step() }
        engine.state.ball.apply {
            x = 1289.0
            y = 20.0
            velocityX = 1000.0
            velocityY = 0.0
        }
        engine.step(InputFrame(leftPulse = true, rightPulse = true))
        assertEquals(1, engine.state.leftScore)
        assertEquals(MatchPhase.COUNTDOWN, engine.state.phase)

        repeat(180) { engine.step(InputFrame(leftPulse = false, rightPulse = false)) }
        assertEquals(MatchPhase.PLAYING, engine.state.phase)
        engine.step(InputFrame(leftPulse = true, rightPulse = true))
        assertTrue(engine.state.left.pulseArmedSeconds > 0.0)
        assertTrue(engine.state.right.pulseArmedSeconds > 0.0)
    }

    @Test fun `press during countdown is tracked without arming or carrying an edge into play`() {
        val engine = MatchEngine(MatchConfig(mode = MatchMode.LOCAL_VERSUS))
        var arms = 0
        engine.eventListener = { if (it.kind == MatchEventKind.PULSE_ARMED) arms++ }
        val held = InputFrame(leftPulse = true, rightPulse = true)
        repeat(180) { engine.step(held) }
        assertEquals(MatchPhase.PLAYING, engine.state.phase)
        assertEquals(0, arms)
        engine.step(held)
        assertEquals(0, arms)
        assertEquals(0.0, engine.state.left.pulseCooldownSeconds)
        assertEquals(0.0, engine.state.right.pulseCooldownSeconds)

        engine.step(InputFrame())
        engine.step(held)
        assertEquals(2, arms)
    }
}
