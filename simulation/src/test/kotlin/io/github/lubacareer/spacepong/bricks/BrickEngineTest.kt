package io.github.lubacareer.spacepong.bricks

import io.github.lubacareer.spacepong.simulation.Arena
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs

class BrickEngineTest {
    private val tick = Arena.STEP_SECONDS

    private fun block(x: Double = 610.0, y: Double = 400.0, pickup: BrickPower? = null) = Brick(x, y, 60.0, 24.0, pickup)
    private fun engine(vararg bricks: Brick): BrickEngine = BrickEngine(bricks.toList()).also { it.advance(tick, launch = true) }
    private fun BrickEngine.ball(x: Double, y: Double, vx: Double, vy: Double) {
        ballX = x
        ballY = y
        velocityX = vx
        velocityY = vy
    }
    private fun BrickEngine.hit(index: Int) {
        val brick = bricks[index]
        ball(brick.x + brick.width / 2, brick.y - BrickEngine.BALL_RADIUS - 1, 0.0, 800.0)
        advance(tick)
    }
    private fun BrickEngine.miss() {
        ball(22.0, 2.0, 0.0, -2200.0)
        advance(tick)
    }

    @Test fun `six ordered levels have unique silhouettes marked pickups and fresh mutable state`() {
        assertEquals(listOf("Outpost", "Hangar", "Twin Towers", "Observatory", "Launch Gantry", "Orbital Citadel"), BrickBuildings.levels.map { it.name })
        val maps = BrickBuildings.levels.indices.map { index ->
            val game = BrickEngine(index)
            assertEquals(3, game.lives)
            assertEquals(BrickPhase.DOCKED, game.phase)
            assertEquals(game.bricks.size, game.remainingBricks)
            assertTrue(game.bricks.all { it.x >= 0 && it.x + it.width <= Arena.WIDTH && it.y > 200 && it.y + it.height <= 600 })
            assertEquals(BrickPower.entries.toSet(), game.bricks.mapNotNull { it.pickup }.toSet())
            game.bricks.map { it.x to it.y }
        }
        assertEquals(6, maps.distinct().size)
        val first = BrickEngine(0)
        first.advance(tick, launch = true)
        first.hit(first.bricks.lastIndex)
        assertTrue(BrickEngine(0).bricks.all { it.alive })
    }

    @Test fun `subtick launch retained until the simulation steps and docked paddle clamped`() {
        val game = BrickEngine(0)
        repeat(160) { game.advance(tick, targetX = -500.0) }
        assertEquals(game.paddleWidth / 2, game.paddleX, 0.0001)
        assertEquals(game.paddleX, game.ballX, 0.0001)
        game.advance(tick * .4, launch = true)
        assertEquals(BrickPhase.DOCKED, game.phase)
        game.advance(tick * .6)
        assertEquals(BrickPhase.PLAYING, game.phase)
        assertTrue(game.velocityY > 0)
    }

    @Test fun `pause discards pending launch and freezes play`() {
        val game = BrickEngine(0)
        game.advance(tick / 2, launch = true)
        game.pause()
        game.advance(tick, launch = true)
        game.resume()
        game.advance(tick)
        assertEquals(BrickPhase.DOCKED, game.phase)
        game.advance(tick, launch = true)
        val y = game.ballY
        game.pause()
        repeat(6) { game.advance(tick) }
        assertEquals(BrickPhase.PAUSED, game.phase)
        assertEquals(y, game.ballY)
        game.resume()
        game.advance(tick)
        assertTrue(game.ballY > y)
    }

    @Test fun `fast face shot removes the first brick and reflects within the tick`() {
        val game = engine(block(), block(y = 460.0))
        game.ball(640.0, 280.0, 0.0, 22000.0)
        game.advance(tick)
        assertEquals(listOf(false, true), game.bricks.map { it.alive })
        assertTrue(game.velocityY < 0 && game.ballY < 390)
        assertEquals(100, game.score)
    }

    @Test fun `right-angle corner graze uses the rounded ball not an expanded square`() {
        val game = engine(block(), block(1000.0))
        game.ball(600.2, 390.2, 30.0, 30.0)
        game.advance(tick)
        assertTrue(game.bricks.first().alive)
        game.ball(602.8, 392.8, 150.0, 150.0)
        game.advance(tick)
        assertFalse(game.bricks.first().alive)
        assertTrue(game.velocityX < 0 && game.velocityY < 0)
    }

    @Test fun `seam picks a single brick and consumes exactly one hit`() {
        val game = engine(block(580.0), block(640.0))
        game.ball(640.0, 360.0, 0.0, 6000.0)
        game.advance(tick)
        assertEquals(listOf(false, true), game.bricks.map { it.alive })
        assertEquals(1, game.remainingBricks)
        assertEquals(100, game.score)
    }

    @Test fun `inside corner destroys a single brick and deflects away from both faces`() {
        val game = engine(block(640.0, 380.0), block(580.0, 404.0))
        game.ball(625.0, 389.0, 800.0, 800.0)
        game.advance(tick)
        assertEquals(1, game.remainingBricks)
        assertEquals(100, game.score)
        assertTrue(game.velocityX < 0 && game.velocityY < 0)
    }

    @Test fun `final brick wins immediately even when the remaining tick could drain the ball`() {
        val game = engine(block(160.0))
        val events = mutableListOf<BrickEvent>()
        game.eventListener = { events.add(it) }
        game.ball(190.0, 375.0, 0.0, 120000.0)
        game.advance(tick)
        repeat(30) { game.advance(tick) }
        assertEquals(BrickPhase.WON, game.phase)
        assertEquals(3, game.lives)
        assertEquals(listOf(BrickEvent.BRICK_HIT, BrickEvent.WON), events)
    }

    @Test fun `three missed balls finish a level without resurrecting broken bricks`() {
        val game = engine(block(), block(900.0))
        game.hit(0)
        repeat(2) { index ->
            game.miss()
            assertEquals(BrickPhase.DOCKED, game.phase)
            assertEquals(2 - index, game.lives)
            assertEquals(0, game.combo)
            assertEquals(1, game.remainingBricks)
            game.advance(tick, launch = true)
        }
        game.miss()
        game.advance(tick, launch = true)
        assertEquals(0, game.lives)
        assertEquals(BrickPhase.LOST, game.phase)
        assertEquals(100, game.score)
    }

    @Test fun `combo scoring caps at four and paddle edges steer and reset the chain`() {
        val game = engine(*Array(7) { block(110.0 + it * 100) })
        repeat(5) { game.hit(it) }
        assertEquals(1400, game.score)
        assertEquals(5, game.combo)
        game.ball(game.paddleX - game.paddleWidth * .44, BrickEngine.PADDLE_Y + BrickEngine.PADDLE_HEIGHT / 2 + BrickEngine.BALL_RADIUS + 1, 0.0, -800.0)
        game.advance(tick)
        assertTrue(game.velocityX < -150.0 && game.velocityY > 0)
        assertEquals(0, game.combo)
        game.hit(5)
        assertEquals(1500, game.score)
    }

    @Test fun `side and ceiling bounces preserve a combo`() {
        val game = engine(block(), block(100.0))
        game.hit(0)
        game.ball(BrickEngine.BALL_RADIUS + .2, 590.0, -800.0, 100.0)
        game.advance(tick)
        assertTrue(game.velocityX > 0)
        game.ball(30.0, BrickEngine.CEILING_Y - BrickEngine.BALL_RADIUS - .2, 50.0, 800.0)
        game.advance(tick)
        assertTrue(game.velocityY < 0)
        assertEquals(1, game.combo)
    }

    @Test fun `marked brick drops descend to the paddle and wide effects refresh for eight seconds`() {
        val game = engine(block(y = 180.0, pickup = BrickPower.WIDE_PADDLE), block(y = 210.0, pickup = BrickPower.WIDE_PADDLE), block(900.0))
        val normalWidth = game.paddleWidth
        game.hit(0)
        assertEquals(BrickPower.WIDE_PADDLE, game.drops.single().power)
        game.ball(150.0, 220.0, 0.0, 1.0)
        repeat(120) { game.advance(tick) }
        assertTrue(game.paddleWidth > normalWidth)
        assertTrue(game.wideSeconds in 6.5..8.0)
        repeat(240) { game.advance(tick) }
        game.hit(1)
        game.ball(150.0, 220.0, 0.0, 1.0)
        repeat(120) { game.advance(tick) }
        assertTrue(game.wideSeconds in 6.5..8.0)
        repeat(960) { game.advance(tick) }
        assertEquals(0.0, game.wideSeconds, 0.00001)
        assertEquals(normalWidth, game.paddleWidth)
    }

    @Test fun `shield absorbs only one miss and resets the combo`() {
        val game = engine(block(y = 180.0, pickup = BrickPower.SHIELD), block(y = 210.0, pickup = BrickPower.SHIELD), block(900.0))
        repeat(2) { index ->
            game.hit(index)
            game.ball(150.0, 200.0, 0.0, 1.0)
            repeat(120) { game.advance(tick) }
            assertTrue(game.shield)
        }
        game.ball(30.0, 65.0, 0.0, -8000.0)
        game.advance(tick)
        assertFalse(game.shield)
        assertEquals(0, game.combo)
        assertEquals(3, game.lives)
        assertTrue(game.velocityY > 0)
        game.miss()
        assertEquals(2, game.lives)
    }

    @Test fun `late shield catch saves a ball already below the shield line`() {
        val game = engine(block(y = 180.0, pickup = BrickPower.SHIELD), block(900.0))
        game.hit(0)
        game.ball(150.0, 220.0, 0.0, 1.0)
        val paddleTop = BrickEngine.PADDLE_Y + BrickEngine.PADDLE_HEIGHT / 2
        val initialHeight = game.drops.single().y
        game.advance(tick)
        val stepDistance = initialHeight - game.drops.single().y
        var steps = 0
        while (steps++ < 120 && game.drops.single().y - BrickEngine.DROP_RADIUS > paddleTop + stepDistance) game.advance(tick)
        game.ball(30.0, 5.0, 0.0, -3000.0)
        game.advance(tick)
        assertEquals(3, game.lives)
        assertFalse(game.shield)
        assertTrue(game.velocityY > 0)
    }

    @Test fun `wide paddle expires exactly eight seconds after catch`() {
        val game = engine(block(y = 180.0, pickup = BrickPower.WIDE_PADDLE), block(900.0))
        val initialWidth = game.paddleWidth
        game.hit(0)
        game.ball(150.0, 220.0, 0.0, 1.0)
        var steps = 0
        while (steps++ < 120 && game.drops.isNotEmpty()) game.advance(tick)
        repeat(959) { game.advance(tick) }
        assertTrue(game.paddleWidth > initialWidth)
        game.advance(tick)
        assertEquals(initialWidth, game.paddleWidth)
    }

    @Test fun `different display cadences produce the same gameplay`() {
        val fast = BrickEngine(2)
        val slow = BrickEngine(2)
        repeat(240) { fast.advance(tick, launch = it == 0) }
        repeat(60) { slow.advance(tick * 4, launch = it == 0) }
        assertEquals(fast.score, slow.score)
        assertTrue(abs(fast.ballX - slow.ballX) < .00001)
        assertTrue(abs(fast.ballY - slow.ballY) < .00001)
        assertEquals(fast.bricks.map { it.alive }, slow.bricks.map { it.alive })
    }
}
