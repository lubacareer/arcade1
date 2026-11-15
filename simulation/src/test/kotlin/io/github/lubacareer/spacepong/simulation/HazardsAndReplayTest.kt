package io.github.lubacareer.spacepong.simulation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.hypot

class HazardsAndReplayTest {
    private fun playing(encounter: Encounter = Encounter.FLIGHT_SCHOOL, seed: Long = 1L): MatchEngine = MatchEngine(MatchConfig(encounter = encounter, seed = seed)).also { repeat(180) { _ -> it.step() } }

    @Test fun `asteroids warn before becoming active and never materialize around ball`() {
        val engine = playing(Encounter.ASTEROID_PASSAGE)
        engine.step()
        assertEquals(HazardPhase.WARNING, engine.state.hazardPhase)
        assertTrue(engine.state.bumpers.none { it.active })
        engine.state.rallySeconds = 0.999
        engine.state.ball.apply {
            x = 640.0
            y = 240.0
            velocityX = 420.0
            velocityY = 0.0
        }
        engine.step()
        assertEquals(HazardPhase.ACTIVE, engine.state.hazardPhase)
        assertFalse(engine.state.bumpers[0].active)
        assertTrue(engine.state.bumpers[0].skippedThisCycle)
        assertTrue(engine.state.bumpers[1].active)
        engine.state.ball.x = 800.0
        engine.step()
        assertFalse(engine.state.bumpers[0].active)
        engine.state.rallySeconds = 9.01
        engine.step()
        assertFalse(engine.state.bumpers[0].skippedThisCycle)
    }

    @Test fun `active bumper sweeps reflect without tunneling`() {
        val engine = playing(Encounter.ASTEROID_PASSAGE)
        engine.state.rallySeconds = 1.1
        engine.state.ball.apply {
            x = 590.0
            y = 240.0
            velocityX = 1000.0
            velocityY = 0.0
        }
        engine.step()
        assertTrue(engine.state.ball.velocityX < 0)
        assertTrue(engine.state.ball.x < 598.0)
    }

    @Test fun `diagonal bumper reflection cannot trap the ball in a vertical orbit`() {
        val engine = playing(Encounter.ASTEROID_PASSAGE)
        val offset = 42.0 / kotlin.math.sqrt(2.0)
        engine.state.rallySeconds = 1.2
        engine.state.ball.apply {
            x = 640.0 - offset - 1.0
            y = 240.0 - offset
            velocityX = 420.0
            velocityY = 0.0
        }
        engine.step()
        val speed = hypot(engine.state.ball.velocityX, engine.state.ball.velocityY)
        assertEquals(420.0, speed, 0.000001)
        assertTrue(abs(engine.state.ball.velocityX) >= speed * kotlin.math.cos(Math.toRadians(65.0)) - 0.000001)
    }

    @Test fun `gravity bends toward center without reversing horizontal motion or increasing speed`() {
        val engine = playing(Encounter.GRAVITY_RELAY)
        engine.state.rallySeconds = 1.1
        engine.state.ball.apply {
            x = 600.0
            y = 410.0
            velocityX = 420.0
            velocityY = 0.0
        }
        engine.step()
        assertEquals(HazardPhase.ACTIVE, engine.state.hazardPhase)
        assertTrue(engine.state.ball.velocityY < 0)
        assertTrue(engine.state.ball.velocityX > 0)
        assertEquals(420.0, hypot(engine.state.ball.velocityX, engine.state.ball.velocityY), 0.000001)
    }

    @Test fun `pulse persists through own paddle contact until an opponent returns it`() {
        val engine = playing()
        engine.step(InputFrame(leftPulse = true))
        engine.state.ball.apply {
            x = 85.0
            y = 360.0
            velocityX = -420.0
            velocityY = 0.0
        }
        engine.step()
        assertTrue(engine.state.ball.pulsed)
        // A bumper can reflect a shot back to its owner's paddle before the opponent touches it.
        engine.state.ball.apply {
            x = 85.0
            y = 360.0
            velocityX = -546.0
            velocityY = 0.0
        }
        engine.step()
        assertTrue(engine.state.ball.pulsed)
        assertEquals(567.84, hypot(engine.state.ball.velocityX, engine.state.ball.velocityY), 0.00001)
    }

    @Test fun `edge paddle contact remains finite and speed capped`() {
        val engine = playing()
        engine.state.ball.apply {
            x = 85.0
            y = 429.0
            velocityX = -850.0
            velocityY = 0.0
            normalSpeed = 850.0
        }
        engine.step(InputFrame(leftPulse = true))
        assertTrue(engine.state.ball.velocityX > 0)
        assertEquals(1000.0, hypot(engine.state.ball.velocityX, engine.state.ball.velocityY), 0.00001)
        assertTrue(abs(engine.state.ball.velocityY / engine.state.ball.velocityX) <= kotlin.math.tan(Math.toRadians(65.0)) + 1e-9)
    }

    @Test fun `frame schedules reproduce the same tick recording`() {
        fun run(fps: Int): MatchState {
            val engine = MatchEngine(MatchConfig(seed = 42L))
            val runner = FixedStepRunner(engine)
            repeat(fps * 10) {
                runner.advance(1.0 / fps) { tick ->
                    InputFrame(leftTargetY = if ((tick / 120) % 2L == 0L) 180.0 else 540.0, leftPulse = tick % 360L == 0L)
                }
            }
            return engine.state
        }
        val reference = run(120)
        for (fps in listOf(30, 60)) {
            val actual = run(fps)
            assertEquals(reference.tick, actual.tick)
            assertEquals(reference.ball.x, actual.ball.x, 1e-9)
            assertEquals(reference.ball.y, actual.ball.y, 1e-9)
            assertEquals(reference.leftScore, actual.leftScore)
            assertEquals(reference.rightScore, actual.rightScore)
        }
    }

    @Test fun `runner caps catchup and clears input while paused`() {
        val engine = playing()
        val runner = FixedStepRunner(engine)
        val tick = engine.state.tick
        assertEquals(8, runner.advance(10.0))
        assertEquals(tick + 8, engine.state.tick)
        assertEquals(0.0, runner.alpha, 1e-9)
        engine.pause()
        assertEquals(0, runner.advance(1.0, InputFrame(leftPulse = true)))
        engine.resume()
        repeat(180) { engine.step() }
        runner.advance(Arena.STEP_SECONDS)
        assertEquals(0.0, engine.state.left.pulseArmedSeconds)
    }

    @Test fun `pulse remains queued across zero step frames and consumes only once`() {
        val engine = playing()
        val runner = FixedStepRunner(engine)
        assertEquals(0, runner.advance(Arena.STEP_SECONDS / 3, InputFrame(leftPulse = true)))
        assertEquals(0.0, engine.state.left.pulseArmedSeconds)
        assertEquals(1, runner.advance(Arena.STEP_SECONDS * 2 / 3))
        assertEquals(0.6, engine.state.left.pulseArmedSeconds, 1e-9)
        runner.advance(Arena.STEP_SECONDS * 4)
        assertEquals(6.0 - 4 * Arena.STEP_SECONDS, engine.state.left.pulseCooldownSeconds, 1e-9)
    }

    @Test fun `reference pilot can clear the chapter without upgrades`() {
        for (encounter in Encounter.entries) {
            for (seed in listOf(1L, 42L, 7147L + encounter.ordinal)) {
                val engine = playing(encounter, seed)
                var ticks = 0
                var returns = 0
                engine.eventListener = { event -> if (event.kind == MatchEventKind.PADDLE_HIT && event.side == Side.LEFT) returns++ }
                val aimOffsets = doubleArrayOf(54.0, -54.0, 35.0, -35.0, 58.0, -58.0)
                while (engine.state.phase != MatchPhase.FINISHED && ticks++ < 120 * 240) {
                    val ball = engine.state.ball
                    val offset = aimOffsets[returns % aimOffsets.size]
                    val target = ball.y + if (ball.velocityX < 0.0) offset else 0.0
                    engine.step(InputFrame(leftTargetY = target, leftPulse = ball.velocityX < 0.0 && ball.x < 230.0 && engine.state.left.pulseCooldownSeconds == 0.0))
                }
                assertEquals(Side.LEFT, engine.state.winner, "Encounter $encounter seed $seed: ${engine.state.leftScore}-${engine.state.rightScore} after $ticks ticks")
            }
        }
    }

    @Test fun `sustained play stays finite and bounded for every encounter`() {
        for (encounter in Encounter.entries) {
            val engine = playing(encounter)
            repeat(36000) {
                if (engine.state.phase == MatchPhase.FINISHED) engine.restart()
                engine.step(InputFrame(leftTargetY = engine.state.ball.y))
                assertTrue(engine.state.ball.x.isFinite() && engine.state.ball.y.isFinite())
                assertTrue(engine.state.ball.velocityX.isFinite() && engine.state.ball.velocityY.isFinite())
                assertTrue(engine.state.left.y >= engine.state.left.height / 2)
                assertTrue(engine.state.left.y <= 720.0 - engine.state.left.height / 2)
            }
        }
    }
}
