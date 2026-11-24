package io.github.lubacareer.spacepong.bricks

import io.github.lubacareer.spacepong.simulation.Arena
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BrickTimelineTest {
    private val tick = Arena.STEP_SECONDS
    private val catchHeight = BrickEngine.PADDLE_Y + BrickEngine.PADDLE_HEIGHT / 2 + BrickEngine.DROP_RADIUS

    private fun playing(vararg marked: Pair<Double, BrickPower>): BrickEngine {
        val targets = marked.map { (x, power) -> Brick(x - 25.0, 500.0, 50.0, 20.0, power) } +
            Brick(1000.0, 400.0, 50.0, 20.0, null)
        return BrickEngine(targets).also { game ->
            game.advance(tick, launch = true)
            marked.indices.forEach(game::brickHit)
            game.ballX = 30.0
            game.ballY = 200.0
            game.velocityX = 0.0
            game.velocityY = 120.0
        }
    }

    @Test fun `one hundred millisecond frames advance all twelve ticks without losing fractional time`() {
        val game = playing()
        game.advance(tick / 2)
        repeat(3) { game.advance(.1) }
        assertEquals(236.0, game.ballY, .000001)
        game.advance(tick / 2)
        assertEquals(237.0, game.ballY, .000001)
    }

    @Test fun `one long frame runs twelve steps then later frames can catch up`() {
        val game = playing()
        game.advance(.25)
        assertEquals(BrickPhase.PLAYING, game.phase)
        assertEquals(212.0, game.ballY, .000001)
        game.advance(tick)
        assertEquals(224.0, game.ballY, .000001)
        game.advance(tick)
        assertEquals(232.0, game.ballY, .000001)
    }

    @Test fun `overload pauses before advancing and discards debt and queued launch`() {
        val game = BrickEngine(0)
        game.advance(tick / 2, launch = true)
        game.advance(.25)
        assertEquals(BrickPhase.PAUSED, game.phase)
        game.advance(.1, launch = true)
        game.resume()
        game.advance(tick / 2)
        game.advance(tick / 2)
        assertEquals(BrickPhase.DOCKED, game.phase)
    }

    @Test fun `accumulating debt across frames triggers the same pause while playing`() {
        val game = playing()
        game.advance(.22)
        val y = game.ballY
        game.advance(.14)
        assertEquals(BrickPhase.PAUSED, game.phase)
        assertEquals(y, game.ballY)
        game.resume()
        game.advance(tick)
        assertEquals(y + 1, game.ballY, .000001)
    }

    @Test fun `a moving paddle catches an overlapping drop before it moves away`() {
        val game = playing(557.0 to BrickPower.SHIELD)
        game.drops.single().y = BrickEngine.PADDLE_Y
        game.advance(tick, axis = 1.0)
        assertTrue(game.shield)
    }

    @Test fun `a paddle moving in after the drop has passed cannot collect it`() {
        val game = playing(549.0 to BrickPower.SHIELD)
        game.drops.single().y = 61.2
        game.advance(tick, axis = -1.0)
        assertFalse(game.shield)
        assertEquals(1, game.drops.size)
    }

    @Test fun `corner pickup requires reaching the actual round surface`() {
        val game = playing(723.0 to BrickPower.SHIELD)
        game.drops.single().y = 97.0
        game.advance(tick)
        assertFalse(game.shield)
        game.advance(tick)
        assertTrue(game.shield)
    }

    private fun shieldRace(drainTime: Double, catchTime: Double): Pair<BrickEngine, List<BrickEvent>> {
        val game = playing(640.0 to BrickPower.SHIELD)
        game.drops.single().y = catchHeight + BrickEngine.DROP_SPEED * catchTime
        game.velocityY = -2000.0
        game.ballY = -BrickEngine.BALL_RADIUS - game.velocityY * drainTime
        val events = mutableListOf<BrickEvent>()
        game.eventListener = { events.add(it) }
        game.advance(tick)
        return game to events
    }

    @Test fun `a drain before a shield catch ends the rally`() {
        val (game, events) = shieldRace(.004, .005)
        assertEquals(2, game.lives)
        assertEquals(listOf(BrickEvent.LIFE_LOST), events)
        assertTrue(game.drops.isEmpty())
    }

    @Test fun `a just-in-time shield bounces the ball from its actual contact time`() {
        val (game, events) = shieldRace(.006, .005)
        assertEquals(3, game.lives)
        assertEquals(listOf(BrickEvent.PICKUP, BrickEvent.SHIELD_SAVE), events)
        // The ball is at -8 when caught, then rises for the rest of the tick.
        assertEquals(-8.0 + 2000.0 * (tick - .005), game.ballY, .000001)
    }

    @Test fun `shield wins an exact tie even at a tick endpoint`() {
        for (time in listOf(.005, tick)) {
            val (game, events) = shieldRace(time, time)
            assertEquals(3, game.lives)
            assertEquals(listOf(BrickEvent.PICKUP, BrickEvent.SHIELD_SAVE), events)
            assertTrue(game.velocityY > 0)
        }
    }

    @Test fun `simultaneous drops activate in spawn order`() {
        val game = playing(640.0 to BrickPower.SHIELD, 640.0 to BrickPower.WIDE_PADDLE)
        game.drops.forEach { it.y = catchHeight + 1.0 }
        val states = mutableListOf<Pair<Boolean, Boolean>>()
        game.eventListener = { if (it == BrickEvent.PICKUP) states.add(game.shield to (game.wideSeconds > 0)) }
        game.advance(tick)
        assertEquals(listOf(true to false, true to true), states)
    }

    @Test fun `widening is effective only from the actual catch time`() {
        for ((paddleTime, shouldBounce) in listOf(.003 to false, .005 to true, .007 to true)) {
            val game = playing(640.0 to BrickPower.WIDE_PADDLE)
            game.drops.single().y = catchHeight + 1.0
            game.ballX = game.paddleX + 102.0
            game.velocityY = -1000.0
            game.ballY = BrickEngine.PADDLE_Y + BrickEngine.PADDLE_HEIGHT / 2 + BrickEngine.BALL_RADIUS - game.velocityY * paddleTime
            game.advance(tick)
            assertEquals(shouldBounce, game.velocityY > 0, "paddle contact at $paddleTime")
        }
    }
}
