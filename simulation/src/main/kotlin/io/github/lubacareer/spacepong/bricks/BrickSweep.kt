package io.github.lubacareer.spacepong.bricks

import io.github.lubacareer.spacepong.bricks.CircleSweep.Companion.TIME_EPSILON
import io.github.lubacareer.spacepong.simulation.Arena
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Ball and pickup events share one timeline. Geometry queries reuse their scratch storage. */
internal class BrickSweep(private val game: BrickEngine) {
    private val radius = BrickEngine.BALL_RADIUS
    private val ball = CircleSweep(radius)
    private val drop = CircleSweep(BrickEngine.DROP_RADIUS, collectTouching = true)
    private var kind = Surface.NONE
    private var index = -1
    private var time = 0.0
    private var normalX = 0.0
    private var normalY = 0.0
    private var secondX = 0.0
    private var secondY = 0.0
    private var limit = 0.0

    fun advance(dt: Double, paddleTarget: Double) {
        var remaining = dt
        var ballContacts = 0
        // Re-query zero-time contacts even at the tick endpoint: a just-caught shield may save a draining ball.
        while (game.phase == BrickPhase.PLAYING) {
            kind = Surface.NONE
            time = Double.POSITIVE_INFINITY
            limit = remaining
            val velocity = if (remaining > 0) (game.clampPaddle(paddleTarget) - game.paddleX) / remaining else 0.0
            if (game.wideSeconds > 0 && game.wideSeconds <= remaining) offer(game.wideSeconds, Surface.WIDTH_EXPIRES)
            checkDrops(velocity)
            val ballActive = ballContacts < MAX_CONTACTS
            if (ballActive) checkBall(velocity)
            if (kind == Surface.NONE) {
                game.moveActors(remaining, velocity, ballActive)
                return
            }
            game.moveActors(time, velocity, ballActive)
            remaining -= time
            when (kind) {
                Surface.DROP -> game.catchDrop(index)
                Surface.WIDTH_EXPIRES -> Unit // Moving to this instant already expired the wide paddle.
                else -> {
                    resolveBall()
                    ballContacts++
                }
            }
        }
    }

    private fun checkDrops(paddleVelocity: Double) {
        // Scan spawn order before ball contacts so exact ties are deterministic and can save the ball.
        for (i in game.drops.indices) {
            val pickup = game.drops[i]
            drop.path(pickup.x - game.paddleX, pickup.y, -paddleVelocity, -BrickEngine.DROP_SPEED, limit)
            if (drop.hits(-game.paddleWidth / 2, BrickEngine.PADDLE_Y - BrickEngine.PADDLE_HEIGHT / 2, game.paddleWidth, BrickEngine.PADDLE_HEIGHT)) {
                offer(drop.time, Surface.DROP, i = i)
            }
        }
    }

    private fun checkBall(paddleVelocity: Double) {
        ball.path(game.ballX, game.ballY, game.velocityX, game.velocityY, limit)
        for (i in game.bricks.indices) {
            val brick = game.bricks[i]
            if (brick.alive && ball.hits(brick.x, brick.y, brick.width, brick.height)) {
                offer(ball.time, Surface.BRICK, ball.normalX, ball.normalY, i)
            }
        }
        checkBoundaries(paddleVelocity)
    }

    private fun resolveBall() {
        when (kind) {
            Surface.BRICK -> {
                reflect(normalX, normalY)
                reflect(secondX, secondY)
                avoidFlatOrbit()
                game.brickHit(index)
            }
            Surface.WALL -> {
                reflect(normalX, normalY)
                game.emit(BrickEvent.WALL_HIT)
            }
            Surface.PADDLE -> game.paddleHit(game.paddleX)
            Surface.SHIELD -> game.shieldHit()
            Surface.DRAIN -> game.loseBall()
            else -> error("Expected a ball contact.")
        }
    }

    private fun checkBoundaries(paddleVelocity: Double) {
        val vx = game.velocityX
        val vy = game.velocityY
        if (vx < 0) offer((radius - game.ballX) / vx, Surface.WALL, 1.0, 0.0)
        if (vx > 0) offer((Arena.WIDTH - radius - game.ballX) / vx, Surface.WALL, -1.0, 0.0)
        if (vy > 0) offer((BrickEngine.CEILING_Y - radius - game.ballY) / vy, Surface.WALL, 0.0, -1.0)
        if (vy >= 0) return

        val paddleTime = (BrickEngine.PADDLE_Y + BrickEngine.PADDLE_HEIGHT / 2 + radius - game.ballY) / vy
        val relativeX = game.ballX - game.paddleX + (vx - paddleVelocity) * paddleTime
        if (abs(relativeX) <= game.paddleWidth / 2 + radius) offer(paddleTime, Surface.PADDLE)
        if (game.shield) {
            // A pickup can reach the paddle while a missed ball is already below the shield.
            val shieldTime = (BrickEngine.SHIELD_Y + radius - game.ballY) / vy
            offer(max(0.0, shieldTime), Surface.SHIELD)
        }
        offer((-radius - game.ballY) / vy, Surface.DRAIN)
    }

    private fun offer(t: Double, candidate: Surface, nx: Double = 0.0, ny: Double = 0.0, i: Int = -1) {
        if (t < -TIME_EPSILON || t > limit + TIME_EPSILON || t > time + TIME_EPSILON) return
        // In a concave seam both exposed faces can be reached simultaneously. Break the first brick in
        // authored order, but deflect away from both faces so the next iteration cannot award a second hit.
        if (abs(t - time) <= TIME_EPSILON) {
            if (kind == Surface.BRICK && candidate == Surface.BRICK && index != i) {
                secondX = nx
                secondY = ny
            }
            return
        }
        time = t.coerceIn(0.0, limit)
        kind = candidate
        index = i
        normalX = nx
        normalY = ny
        secondX = 0.0
        secondY = 0.0
    }

    private fun reflect(nx: Double, ny: Double) {
        val approach = game.velocityX * nx + game.velocityY * ny
        if (approach >= 0) return
        game.velocityX -= 2 * approach * nx
        game.velocityY -= 2 * approach * ny
    }

    /** A glancing brick corner can send the ball horizontally into a permanent wall-to-wall orbit. */
    private fun avoidFlatOrbit() {
        val vx = game.velocityX
        val vy = game.velocityY
        val speedSquared = vx * vx + vy * vy
        val minimumY = sqrt(speedSquared) * MIN_VERTICAL_FRACTION
        if (abs(vy) >= minimumY) return
        val correctedX = Math.copySign(sqrt(max(0.0, speedSquared - minimumY * minimumY)), vx)
        var correctedY = Math.copySign(minimumY, vy)
        if (correctedX * normalX + correctedY * normalY < 0 || correctedX * secondX + correctedY * secondY < 0) {
            correctedY = -correctedY
        }
        game.velocityX = correctedX
        game.velocityY = correctedY
    }

    private enum class Surface { NONE, DROP, WIDTH_EXPIRES, WALL, BRICK, PADDLE, SHIELD, DRAIN }

    private companion object {
        const val MIN_VERTICAL_FRACTION = 0.15
        const val MAX_CONTACTS = 8
    }
}
