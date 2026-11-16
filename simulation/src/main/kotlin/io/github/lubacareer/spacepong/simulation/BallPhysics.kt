package io.github.lubacareer.spacepong.simulation

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Sweeps the ball through the tick, resolving contacts in time order with a bounded work budget. */
internal class BallPhysics(
    private val state: MatchState,
    private val emit: (MatchEvent) -> Unit,
    private val goal: (Side) -> Unit,
) {
    private val ball get() = state.ball

    fun advance(seconds: Double) {
        ball.y = ball.y.coerceIn(Arena.BALL_RADIUS, Arena.HEIGHT - Arena.BALL_RADIUS)
        var remaining = seconds
        var elapsed = 0.0
        var contacts = 0
        while (remaining > EPSILON && contacts < MAX_CONTACTS && state.phase == MatchPhase.PLAYING) {
            var earliest = remaining + 1.0
            var collision = Collision.NONE
            var bumperIndex = -1
            fun consider(time: Double, candidate: Collision, index: Int = -1) {
                if (time >= -EPSILON && time <= remaining + EPSILON && time < earliest) {
                    earliest = max(0.0, time)
                    collision = candidate
                    bumperIndex = index
                }
            }
            if (ball.velocityX < 0) {
                consider(paddleTime(state.left, elapsed), Collision.LEFT_PADDLE)
                consider((-Arena.BALL_RADIUS - ball.x) / ball.velocityX, Collision.LEFT_GOAL)
            } else if (ball.velocityX > 0) {
                consider(paddleTime(state.right, elapsed), Collision.RIGHT_PADDLE)
                consider((Arena.WIDTH + Arena.BALL_RADIUS - ball.x) / ball.velocityX, Collision.RIGHT_GOAL)
            }
            if (ball.velocityY < 0) consider((Arena.BALL_RADIUS - ball.y) / ball.velocityY, Collision.WALL)
            if (ball.velocityY > 0) consider((Arena.HEIGHT - Arena.BALL_RADIUS - ball.y) / ball.velocityY, Collision.WALL)
            for (index in state.bumpers.indices) {
                if (state.bumpers[index].active) consider(bumperTime(state.bumpers[index]), Collision.BUMPER, index)
            }
            if (collision == Collision.NONE) {
                move(remaining)
                return
            }
            move(earliest)
            remaining -= earliest
            elapsed += earliest
            when (collision) {
                Collision.LEFT_GOAL -> {
                    goal(Side.RIGHT)
                    return
                }
                Collision.RIGHT_GOAL -> {
                    goal(Side.LEFT)
                    return
                }
                Collision.LEFT_PADDLE -> returnFromPaddle(state.left, Side.LEFT, elapsed)
                Collision.RIGHT_PADDLE -> returnFromPaddle(state.right, Side.RIGHT, elapsed)
                Collision.WALL -> {
                    ball.velocityY = -ball.velocityY
                    ball.y = ball.y.coerceIn(Arena.BALL_RADIUS, Arena.HEIGHT - Arena.BALL_RADIUS)
                    emit(MatchEvent(MatchEventKind.WALL_HIT, null, ball.x, ball.y))
                }
                Collision.BUMPER -> reflectBumper(state.bumpers[bumperIndex])
                Collision.NONE -> Unit
            }
            // A tiny separation avoids repeatedly solving the same zero-time surface contact.
            ball.x += ball.velocityX * EPSILON
            ball.y += ball.velocityY * EPSILON
            contacts++
        }
        // When pathological geometry consumes the contact budget, discard residual time safely.
        ball.y = ball.y.coerceIn(Arena.BALL_RADIUS, Arena.HEIGHT - Arena.BALL_RADIUS)
    }

    private fun move(seconds: Double) {
        ball.x += ball.velocityX * seconds
        ball.y += ball.velocityY * seconds
    }

    private fun paddleTime(paddle: PaddleState, elapsed: Double): Double {
        val halfWidth = Arena.PADDLE_WIDTH / 2 + Arena.BALL_RADIUS
        val halfHeight = paddle.height / 2 + Arena.BALL_RADIUS
        val paddleY = paddle.previousY + paddle.velocityY * elapsed
        val relativeY = ball.y - paddleY
        val relativeVelocityY = ball.velocityY - paddle.velocityY
        val x1 = (paddle.x - halfWidth - ball.x) / ball.velocityX
        val x2 = (paddle.x + halfWidth - ball.x) / ball.velocityX
        val nearX = min(x1, x2)
        val farX = max(x1, x2)
        val nearY: Double
        val farY: Double
        if (abs(relativeVelocityY) < EPSILON) {
            if (abs(relativeY) > halfHeight) return Double.POSITIVE_INFINITY
            nearY = Double.NEGATIVE_INFINITY
            farY = Double.POSITIVE_INFINITY
        } else {
            val y1 = (-halfHeight - relativeY) / relativeVelocityY
            val y2 = (halfHeight - relativeY) / relativeVelocityY
            nearY = min(y1, y2)
            farY = max(y1, y2)
        }
        val enter = max(nearX, nearY)
        val leave = min(farX, farY)
        return if (leave >= max(0.0, enter)) max(0.0, enter) else Double.POSITIVE_INFINITY
    }

    private fun returnFromPaddle(paddle: PaddleState, side: Side, elapsed: Double) {
        ball.normalSpeed = min(Arena.MAX_NORMAL_SPEED, ball.normalSpeed * 1.04)
        val paddleY = paddle.previousY + paddle.velocityY * elapsed
        val contact = ((ball.y - paddleY) / (paddle.height / 2)).coerceIn(-1.0, 1.0)
        val movement = (paddle.velocityY / paddle.speed).coerceIn(-1.0, 1.0)
        val angle = Math.toRadians((contact * 55.0 + movement * 10.0).coerceIn(-65.0, 65.0))
        if (paddle.pulseArmedSeconds > 0.0) {
            ball.pulseOwner = side
        } else if (ball.pulseOwner != side) {
            ball.pulseOwner = null
        }
        ball.pulsed = ball.pulseOwner != null
        val speed = if (ball.pulsed) min(Arena.MAX_PULSE_SPEED, ball.normalSpeed * 1.25) else ball.normalSpeed
        paddle.pulseArmedSeconds = 0.0
        ball.velocityX = (if (side == Side.LEFT) 1.0 else -1.0) * cos(angle) * speed
        ball.velocityY = sin(angle) * speed
        ball.x = paddle.x + (if (side == Side.LEFT) 1.0 else -1.0) * (Arena.PADDLE_WIDTH / 2 + Arena.BALL_RADIUS)
        emit(MatchEvent(MatchEventKind.PADDLE_HIT, side, ball.x, ball.y))
    }

    private fun bumperTime(bumper: BumperState): Double {
        val dx = ball.x - bumper.x
        val dy = ball.y - bumper.y
        val radius = bumper.radius + Arena.BALL_RADIUS
        val approach = dx * ball.velocityX + dy * ball.velocityY
        if (approach >= 0) return Double.POSITIVE_INFINITY
        val c = dx * dx + dy * dy - radius * radius
        if (c <= 0) return 0.0
        val speedSquared = ball.velocityX * ball.velocityX + ball.velocityY * ball.velocityY
        val discriminant = approach * approach - speedSquared * c
        if (discriminant < 0 || speedSquared == 0.0) return Double.POSITIVE_INFINITY
        return (-approach - sqrt(discriminant)) / speedSquared
    }

    private fun reflectBumper(bumper: BumperState) {
        val dx = ball.x - bumper.x
        val dy = ball.y - bumper.y
        val distance = sqrt(dx * dx + dy * dy)
        val normalX = if (distance > EPSILON) dx / distance else 1.0
        val normalY = if (distance > EPSILON) dy / distance else 0.0
        val incomingX = ball.velocityX
        val dot = ball.velocityX * normalX + ball.velocityY * normalY
        ball.velocityX -= 2 * dot * normalX
        ball.velocityY -= 2 * dot * normalY
        // Exact diagonal reflections can otherwise create a permanent vertical orbit.
        val speed = sqrt(ball.velocityX * ball.velocityX + ball.velocityY * ball.velocityY)
        val minimumX = speed * cos(Math.toRadians(65.0))
        if (abs(ball.velocityX) < minimumX) {
            val direction = if (abs(ball.velocityX) > EPSILON) ball.velocityX else incomingX
            var correctedX = if (direction < 0.0) -minimumX else minimumX
            val correctedY = (if (ball.velocityY < 0.0) -1.0 else 1.0) * sqrt(max(0.0, speed * speed - minimumX * minimumX))
            // Keep the corrected velocity pointing out of the collision surface.
            if (correctedX * normalX + correctedY * normalY <= 0.0) correctedX = -correctedX
            ball.velocityX = correctedX
            ball.velocityY = correctedY
        }
        ball.x = bumper.x + normalX * (bumper.radius + Arena.BALL_RADIUS + EPSILON)
        ball.y = bumper.y + normalY * (bumper.radius + Arena.BALL_RADIUS + EPSILON)
        emit(MatchEvent(MatchEventKind.BUMPER_HIT, null, ball.x, ball.y))
    }

    private enum class Collision { NONE, LEFT_PADDLE, RIGHT_PADDLE, WALL, BUMPER, LEFT_GOAL, RIGHT_GOAL }
    private companion object {
        const val EPSILON = 1e-9
        const val MAX_CONTACTS = 8
    }
}
