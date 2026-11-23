package io.github.lubacareer.spacepong.bricks

import io.github.lubacareer.spacepong.simulation.Arena
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** One attempt at one building. Input edges are retained until the next fixed simulation step. */
class BrickEngine internal constructor(val bricks: List<Brick>) {
    constructor(levelIndex: Int) : this(BrickBuildings.levels[levelIndex].build())

    private val falling = mutableListOf<BrickDrop>()
    val drops: List<BrickDrop> get() = falling
    var phase = BrickPhase.DOCKED
        private set
    var lives = 3
        private set
    var score = 0
        private set
    var combo = 0
        private set
    var remainingBricks = bricks.size
        private set
    var shield = false
        private set
    var wideSeconds = 0.0
        private set
    var paddleX = Arena.WIDTH / 2
        private set
    val paddleWidth: Double get() = if (wideSeconds > 0) WIDE_WIDTH else NORMAL_WIDTH
    var ballX = paddleX
        internal set
    var ballY = SERVE_Y
        internal set
    internal var velocityX = 0.0
    internal var velocityY = 0.0
    var eventListener: ((BrickEvent) -> Unit)? = null
    private var accumulator = 0.0
    private var launchQueued = false
    private var resumePhase = BrickPhase.DOCKED
    private val sweep = BrickSweep(this)

    fun advance(deltaSeconds: Double, targetX: Double? = null, axis: Double = 0.0, launch: Boolean = false) {
        if (!isActive() || deltaSeconds < 0 || !deltaSeconds.isFinite()) return
        launchQueued = launchQueued || (launch && phase == BrickPhase.DOCKED)
        accumulator = min(accumulator + deltaSeconds, MAX_STEPS * Arena.STEP_SECONDS)
        while (accumulator + CLOCK_EPSILON >= Arena.STEP_SECONDS && isActive()) {
            accumulator = (accumulator - Arena.STEP_SECONDS).coerceAtLeast(0.0)
            step(targetX, axis)
            launchQueued = false
        }
    }

    private fun isActive() = phase == BrickPhase.DOCKED || phase == BrickPhase.PLAYING

    private fun step(targetX: Double?, axis: Double) {
        val dt = Arena.STEP_SECONDS
        val startX = paddleX
        val limit = PADDLE_SPEED * dt
        val movement = when {
            targetX != null && targetX.isFinite() -> (targetX - startX).coerceIn(-limit, limit)
            axis.isFinite() -> axis.coerceIn(-1.0, 1.0) * limit
            else -> 0.0
        }
        paddleX = clampPaddle(startX + movement)
        if (phase == BrickPhase.DOCKED) {
            ballX = paddleX
            ballY = SERVE_Y
            if (!launchQueued) return
            phase = BrickPhase.PLAYING
            val angle = Math.toRadians(if (lives % 2 == 1) 18.0 else -18.0)
            velocityX = BALL_SPEED * sin(angle)
            velocityY = BALL_SPEED * cos(angle)
        }
        wideSeconds = (wideSeconds - dt).coerceAtLeast(0.0)
        advanceDrops(dt)
        sweep.advance(dt, startX, (paddleX - startX) / dt)
    }

    fun pause() {
        if (!isActive()) return
        resumePhase = phase
        phase = BrickPhase.PAUSED
        accumulator = 0.0
        launchQueued = false
    }

    fun resume() {
        if (phase == BrickPhase.PAUSED) phase = resumePhase
    }

    internal fun brickHit(index: Int) {
        val brick = bricks[index]
        brick.alive = false
        remainingBricks--
        combo++
        score += min(combo, MAX_MULTIPLIER) * BASE_POINTS
        brick.pickup?.let { falling.add(BrickDrop(brick.x + brick.width / 2, brick.y + brick.height / 2, it)) }
        emit(BrickEvent.BRICK_HIT)
        if (remainingBricks == 0) {
            phase = BrickPhase.WON
            emit(BrickEvent.WON)
        }
    }

    internal fun paddleHit(contactX: Double) {
        combo = 0
        val offset = ((ballX - contactX) / (paddleWidth / 2)).coerceIn(-1.0, 1.0)
        val angle = Math.toRadians(offset * MAX_RETURN_ANGLE)
        velocityX = BALL_SPEED * sin(angle)
        velocityY = BALL_SPEED * cos(angle)
        emit(BrickEvent.PADDLE_HIT)
    }

    internal fun shieldHit() {
        shield = false
        combo = 0
        velocityY = abs(velocityY)
        emit(BrickEvent.SHIELD_SAVE)
    }

    internal fun loseBall() {
        lives--
        combo = 0
        wideSeconds = 0.0
        falling.clear()
        velocityX = 0.0
        velocityY = 0.0
        phase = if (lives == 0) BrickPhase.LOST else BrickPhase.DOCKED
        ballX = paddleX
        ballY = SERVE_Y
        emit(BrickEvent.LIFE_LOST)
        if (phase == BrickPhase.LOST) emit(BrickEvent.LOST)
    }

    private fun advanceDrops(dt: Double) {
        for (index in falling.indices.reversed()) {
            val drop = falling[index]
            val oldY = drop.y
            drop.y -= DROP_SPEED * dt
            val reachesPaddle = oldY + DROP_RADIUS >= PADDLE_Y - PADDLE_HEIGHT / 2 &&
                drop.y - DROP_RADIUS <= PADDLE_Y + PADDLE_HEIGHT / 2 &&
                abs(drop.x - paddleX) <= paddleWidth / 2 + DROP_RADIUS
            if (reachesPaddle) {
                when (drop.power) {
                    BrickPower.WIDE_PADDLE -> {
                        wideSeconds = WIDE_DURATION
                        paddleX = clampPaddle(paddleX)
                    }
                    BrickPower.SHIELD -> shield = true
                }
                emit(BrickEvent.PICKUP)
            }
            if (reachesPaddle || drop.y < -DROP_RADIUS) falling.removeAt(index)
        }
    }

    private fun clampPaddle(x: Double) = x.coerceIn(paddleWidth / 2, Arena.WIDTH - paddleWidth / 2)
    internal fun emit(event: BrickEvent) = eventListener?.invoke(event)

    companion object {
        const val PADDLE_Y = 80.0
        const val PADDLE_HEIGHT = 18.0
        const val BALL_RADIUS = Arena.BALL_RADIUS
        const val SHIELD_Y = 28.0
        const val CEILING_Y = 620.0
        private const val SERVE_Y = PADDLE_Y + PADDLE_HEIGHT / 2 + BALL_RADIUS + 1
        private const val NORMAL_WIDTH = 150.0
        private const val WIDE_WIDTH = 225.0
        private const val PADDLE_SPEED = 1100.0
        private const val BALL_SPEED = 575.0
        private const val MAX_RETURN_ANGLE = 65.0
        private const val MAX_STEPS = 8
        private const val CLOCK_EPSILON = 1e-10
        private const val DROP_SPEED = 200.0
        const val DROP_RADIUS = 10.0
        private const val WIDE_DURATION = 8.0
        private const val BASE_POINTS = 100
        private const val MAX_MULTIPLIER = 4
    }
}
