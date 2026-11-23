package io.github.lubacareer.spacepong.bricks

import io.github.lubacareer.spacepong.simulation.Arena
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Continuous moving-circle tests. Query storage is reused across ticks and across candidate bricks. */
internal class BrickSweep(private val game: BrickEngine) {
    private val radius = BrickEngine.BALL_RADIUS
    private var kind = Surface.NONE
    private var index = -1
    private var time = 0.0
    private var normalX = 0.0
    private var normalY = 0.0
    private var secondX = 0.0
    private var secondY = 0.0
    private var limit = 0.0

    fun advance(dt: Double, startPaddleX: Double, paddleVelocity: Double) {
        var remaining = dt
        var contacts = 0
        while (remaining > TIME_EPSILON && contacts < MAX_CONTACTS && game.phase == BrickPhase.PLAYING) {
            kind = Surface.NONE
            time = Double.POSITIVE_INFINITY
            limit = remaining
            for (i in game.bricks.indices) {
                val brick = game.bricks[i]
                if (brick.alive) checkBrick(i, brick)
            }
            val elapsed = dt - remaining
            checkBoundaries(startPaddleX + elapsed * paddleVelocity, paddleVelocity)
            if (kind == Surface.NONE) {
                move(remaining)
                return
            }
            move(time)
            remaining -= time
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
                Surface.PADDLE -> game.paddleHit(startPaddleX + (elapsed + time) * paddleVelocity)
                Surface.SHIELD -> game.shieldHit()
                Surface.DRAIN -> game.loseBall()
                Surface.NONE -> Unit
            }
            contacts++
        }
    }

    private fun checkBoundaries(paddleX: Double, paddleVelocity: Double) {
        val vx = game.velocityX
        val vy = game.velocityY
        if (vx < 0) offer((radius - game.ballX) / vx, Surface.WALL, 1.0, 0.0)
        if (vx > 0) offer((Arena.WIDTH - radius - game.ballX) / vx, Surface.WALL, -1.0, 0.0)
        if (vy > 0) offer((BrickEngine.CEILING_Y - radius - game.ballY) / vy, Surface.WALL, 0.0, -1.0)
        if (vy >= 0) return

        val paddleTime = (BrickEngine.PADDLE_Y + BrickEngine.PADDLE_HEIGHT / 2 + radius - game.ballY) / vy
        val relativeX = game.ballX - paddleX + (vx - paddleVelocity) * paddleTime
        if (abs(relativeX) <= game.paddleWidth / 2 + radius) offer(paddleTime, Surface.PADDLE)
        if (game.shield) {
            // A pickup can reach the paddle while a missed ball is already below the shield.
            val shieldTime = (BrickEngine.SHIELD_Y + radius - game.ballY) / vy
            offer(max(0.0, shieldTime), Surface.SHIELD)
        }
        offer((-radius - game.ballY) / vy, Surface.DRAIN)
    }

    private fun checkBrick(i: Int, brick: Brick) {
        val x = game.ballX
        val y = game.ballY
        val vx = game.velocityX
        val vy = game.velocityY
        val endX = x + vx * limit
        val endY = y + vy * limit
        val left = brick.x
        val right = left + brick.width
        val bottom = brick.y
        val top = bottom + brick.height
        if (max(x, endX) + radius < left || min(x, endX) - radius > right ||
            max(y, endY) + radius < bottom || min(y, endY) - radius > top
        ) {
            return
        }

        // Face spans use the actual brick extent. The four quarter circles fill the remaining rounded corners.
        if (vx != 0.0) {
            val nx = if (vx > 0) -1.0 else 1.0
            val faceX = if (vx > 0) left - radius else right + radius
            val t = (faceX - x) / vx
            val atY = y + vy * t
            if (atY in bottom..top) offer(t, Surface.BRICK, nx, 0.0, i)
        }
        if (vy != 0.0) {
            val ny = if (vy > 0) -1.0 else 1.0
            val faceY = if (vy > 0) bottom - radius else top + radius
            val t = (faceY - y) / vy
            val atX = x + vx * t
            if (atX in left..right) offer(t, Surface.BRICK, 0.0, ny, i)
        }
        checkCorner(i, left, bottom, -1, -1)
        checkCorner(i, left, top, -1, 1)
        checkCorner(i, right, bottom, 1, -1)
        checkCorner(i, right, top, 1, 1)
    }

    private fun checkCorner(i: Int, cx: Double, cy: Double, xSign: Int, ySign: Int) {
        val dx = game.ballX - cx
        val dy = game.ballY - cy
        val vx = game.velocityX
        val vy = game.velocityY
        val approach = dx * vx + dy * vy
        if (approach >= 0) return
        val speedSquared = vx * vx + vy * vy
        val distanceTerm = dx * dx + dy * dy - radius * radius
        val discriminant = approach * approach - speedSquared * distanceTerm
        if (discriminant <= 0) return
        // The conjugate form retains precision on the last fraction of a tick before contact.
        val t = distanceTerm / (-approach + sqrt(discriminant))
        if (t < -TIME_EPSILON || t > limit + TIME_EPSILON) return
        val nx = (dx + vx * t) / radius
        val ny = (dy + vy * t) / radius
        if (nx * xSign >= 0 && ny * ySign >= 0) offer(t, Surface.BRICK, nx, ny, i)
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

    private fun move(seconds: Double) {
        game.ballX += game.velocityX * seconds
        game.ballY += game.velocityY * seconds
    }

    private enum class Surface { NONE, WALL, BRICK, PADDLE, SHIELD, DRAIN }

    private companion object {
        const val TIME_EPSILON = 1e-10
        const val MIN_VERTICAL_FRACTION = 0.15
        const val MAX_CONTACTS = 8
    }
}
