package io.github.lubacareer.spacepong.simulation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.hypot

class MatchEngineTest {
    private fun playing(config: MatchConfig = MatchConfig(mode = MatchMode.LOCAL_VERSUS)): MatchEngine = MatchEngine(config).also { repeat(180) { _ -> it.step() } }

    @Test fun `serve waits then starts at the specified speed`() {
        val engine = MatchEngine()
        repeat(179) { engine.step() }
        assertEquals(MatchPhase.COUNTDOWN, engine.state.phase)
        assertEquals(640.0, engine.state.ball.x)
        engine.step()
        assertEquals(MatchPhase.PLAYING, engine.state.phase)
        assertEquals(420.0, hypot(engine.state.ball.velocityX, engine.state.ball.velocityY), 0.00001)
    }

    @Test fun `movement is speed limited and clamped inside arena`() {
        val engine = playing()
        engine.step(InputFrame(leftTargetY = 10000.0))
        assertEquals(360.0 + 1000.0 / 120, engine.state.left.y, 0.00001)
        repeat(100) { engine.step(InputFrame(leftTargetY = 10000.0)) }
        assertEquals(660.0, engine.state.left.y, 0.00001)
        engine.step(InputFrame(leftTargetY = Double.NaN, leftAxis = Double.POSITIVE_INFINITY))
        assertTrue(engine.state.left.y.isFinite())
    }

    @Test fun `swept paddle collision catches a ball crossing the entire paddle in one tick`() {
        val engine = playing()
        engine.state.ball.apply {
            x = 140.0
            y = 360.0
            velocityX = -20000.0
            velocityY = 0.0
        }
        engine.step()
        assertTrue(engine.state.ball.velocityX > 0)
        assertEquals(0, engine.state.rightScore)
        assertEquals(436.8, hypot(engine.state.ball.velocityX, engine.state.ball.velocityY), 0.00001)
    }

    @Test fun `wall collision resolves remaining tick without leaving bounds`() {
        val engine = playing()
        engine.state.ball.apply {
            x = 600.0
            y = 708.0
            velocityX = 100.0
            velocityY = 900.0
        }
        engine.step()
        assertTrue(engine.state.ball.velocityY < 0)
        assertTrue(engine.state.ball.y in 10.0..710.0)
    }

    @Test fun `missed paddle awards one goal and resets the rally`() {
        val engine = playing()
        engine.state.ball.apply {
            x = 0.0
            y = 20.0
            velocityX = -1000.0
            velocityY = 0.0
        }
        repeat(4) { engine.step() }
        assertEquals(1, engine.state.rightScore)
        assertEquals(MatchPhase.COUNTDOWN, engine.state.phase)
        assertEquals(640.0, engine.state.ball.x)
        repeat(180) { engine.step() }
        assertTrue(engine.state.ball.velocityX < 0)
    }

    @Test fun `pulse arms once and boosts exactly the next return`() {
        val engine = playing()
        engine.step(InputFrame(leftPulse = true))
        assertEquals(6.0, engine.state.left.pulseCooldownSeconds, 0.00001)
        engine.state.ball.apply {
            x = 85.0
            y = 360.0
            velocityX = -420.0
            velocityY = 0.0
        }
        engine.step(InputFrame(leftPulse = true))
        assertTrue(engine.state.ball.pulsed)
        assertEquals(546.0, hypot(engine.state.ball.velocityX, engine.state.ball.velocityY), 0.00001)
        assertEquals(0.0, engine.state.left.pulseArmedSeconds)
        engine.state.ball.apply {
            x = 1195.0
            y = 360.0
            velocityX = 546.0
            velocityY = 0.0
        }
        engine.step()
        assertFalse(engine.state.ball.pulsed)
        assertEquals(454.272, hypot(engine.state.ball.velocityX, engine.state.ball.velocityY), 0.00001)
    }

    @Test fun `missed pulse expires while its cooldown continues`() {
        val engine = playing()
        engine.step(InputFrame(leftPulse = true))
        repeat(73) { engine.step() }
        assertEquals(0.0, engine.state.left.pulseArmedSeconds)
        assertTrue(engine.state.left.pulseCooldownSeconds > 5.0)
    }

    @Test fun `pause freezes state and resume preserves rally behind a countdown`() {
        val engine = playing()
        engine.step(InputFrame(leftPulse = true))
        val x = engine.state.ball.x
        val cooldown = engine.state.left.pulseCooldownSeconds
        val tick = engine.state.tick
        engine.pause()
        repeat(1000) { engine.step(InputFrame(leftTargetY = 0.0)) }
        assertEquals(x, engine.state.ball.x)
        assertEquals(tick, engine.state.tick)
        assertEquals(cooldown, engine.state.left.pulseCooldownSeconds)
        engine.resume()
        assertEquals(MatchPhase.COUNTDOWN, engine.state.phase)
        repeat(180) { engine.step() }
        assertEquals(MatchPhase.PLAYING, engine.state.phase)
        assertEquals(x, engine.state.ball.x)
    }

    @Test fun `local versus ignores every campaign upgrade`() {
        val engine = MatchEngine(MatchConfig(mode = MatchMode.LOCAL_VERSUS, loadout = PlayerLoadout(2, 2, 2)))
        assertEquals(120.0, engine.state.left.height)
        assertEquals(engine.state.right.height, engine.state.left.height)
        assertEquals(1000.0, engine.state.left.speed)
        assertEquals(6.0, engine.state.left.pulseDuration)
    }

    @Test fun `campaign upgrades affect only the human paddle`() {
        val engine = MatchEngine(MatchConfig(loadout = PlayerLoadout(2, 2, 2)))
        assertEquals(139.2, engine.state.left.height, 0.00001)
        assertEquals(1160.0, engine.state.left.speed, 0.00001)
        assertEquals(5.0, engine.state.left.pulseDuration)
        assertEquals(120.0, engine.state.right.height)
    }

    @Test fun `local match ends exactly once at seven and restart restores seed`() {
        val engine = playing()
        var finishes = 0
        engine.eventListener = { if (it.kind == MatchEventKind.MATCH_FINISHED) finishes++ }
        repeat(7) {
            engine.state.phase = MatchPhase.PLAYING
            engine.state.ball.apply {
                x = 1289.0
                y = 0.0
                velocityX = 1000.0
                velocityY = 0.0
            }
            engine.step()
        }
        assertEquals(Side.LEFT, engine.state.winner)
        assertEquals(MatchPhase.FINISHED, engine.state.phase)
        repeat(100) { engine.step() }
        assertEquals(1, finishes)
        engine.restart()
        assertEquals(0, engine.state.leftScore)
        assertEquals(MatchPhase.COUNTDOWN, engine.state.phase)
        assertNull(engine.state.winner)
    }

    @Test fun `AI never exceeds movement speed and ignores human right commands`() {
        val engine = playing(MatchConfig())
        engine.state.ball.apply {
            y = 700.0
            velocityX = 420.0
            velocityY = 0.0
        }
        val previous = engine.state.right.y
        engine.step(InputFrame(rightTargetY = 10000.0, rightPulse = true))
        assertTrue(abs(engine.state.right.y - previous) <= 450.0 / 120 + 0.00001)
        assertEquals(0.0, engine.state.right.pulseCooldownSeconds)
    }

    @Test fun `boss changes phase only at goal boundaries and loses hull`() {
        val engine = playing(MatchConfig(encounter = Encounter.WARDEN))
        repeat(2) {
            engine.state.phase = MatchPhase.PLAYING
            engine.state.ball.apply {
                x = 1289.0
                y = 0.0
                velocityX = 1000.0
                velocityY = 0.0
            }
            engine.step()
        }
        assertEquals(4, engine.state.rightHull)
        assertEquals(2, engine.state.bossPhase)
        assertEquals(HazardKind.ASTEROIDS, engine.state.hazardKind)
        repeat(4) {
            engine.state.phase = MatchPhase.PLAYING
            engine.state.ball.apply {
                x = 1289.0
                y = 0.0
                velocityX = 1000.0
                velocityY = 0.0
            }
            engine.step()
        }
        assertEquals(Side.LEFT, engine.state.winner)
        assertEquals(0, engine.state.rightHull)
    }

    @Test fun `boss player loses after three goals`() {
        val engine = playing(MatchConfig(encounter = Encounter.WARDEN))
        repeat(3) {
            engine.state.phase = MatchPhase.PLAYING
            engine.state.ball.apply {
                x = -9.0
                y = 0.0
                velocityX = -1000.0
                velocityY = 0.0
            }
            engine.step()
        }
        assertEquals(Side.RIGHT, engine.state.winner)
        assertEquals(0, engine.state.leftHull)
    }
}
