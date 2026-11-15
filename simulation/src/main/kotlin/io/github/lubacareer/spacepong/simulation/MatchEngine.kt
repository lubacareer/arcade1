package io.github.lubacareer.spacepong.simulation

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Owns game rules. Neither presentation frames nor wall-clock time can change these rules. */
class MatchEngine(val config: MatchConfig = MatchConfig()) {
    var state: MatchState = createState()
        private set
    var eventListener: ((MatchEvent) -> Unit)? = null
    private var random = SeededRandom(config.seed)
    private var ai = OpponentController(config.encounter, random)
    private var hazards = HazardController(state)
    private var physics = BallPhysics(state, ::emit, ::score)
    private var pendingServe = true
    private var serveDirection = if (random.nextUnit() < 0.5) -1.0 else 1.0
    private var leftPulseHeld = false
    private var rightPulseHeld = false

    init {
        hazards.reset()
    }

    fun step(input: InputFrame = InputFrame()) {
        if (state.phase == MatchPhase.PAUSED || state.phase == MatchPhase.FINISHED) return
        state.tick++
        capturePreviousPositions()
        if (state.phase == MatchPhase.COUNTDOWN) {
            // Track button edges while the rally is frozen, without arming Pulse.
            leftPulseHeld = input.leftPulse
            rightPulseHeld = input.rightPulse
            state.countdownSeconds = max(0.0, state.countdownSeconds - Arena.STEP_SECONDS)
            if (state.countdownSeconds < TIME_EPSILON) {
                state.countdownSeconds = 0.0
                state.phase = MatchPhase.PLAYING
                if (pendingServe) serve()
            }
            return
        }
        updatePaddle(state.left, input.leftTargetY, input.leftAxis)
        if (config.mode == MatchMode.LOCAL_VERSUS) {
            updatePaddle(state.right, input.rightTargetY, input.rightAxis)
        } else {
            updatePaddle(state.right, ai.target(state), 0.0, acceleration = OpponentController.MAX_ACCELERATION)
        }
        updatePulse(state.left, input.leftPulse && !leftPulseHeld, Side.LEFT)
        updatePulse(state.right, config.mode == MatchMode.LOCAL_VERSUS && input.rightPulse && !rightPulseHeld, Side.RIGHT)
        leftPulseHeld = input.leftPulse
        rightPulseHeld = input.rightPulse
        state.rallySeconds += Arena.STEP_SECONDS
        hazards.update()
        physics.advance(Arena.STEP_SECONDS)
    }

    fun pause() {
        if (state.phase == MatchPhase.PAUSED || state.phase == MatchPhase.FINISHED) return
        state.phase = MatchPhase.PAUSED
        state.left.velocityY = 0.0
        state.right.velocityY = 0.0
        leftPulseHeld = false
        rightPulseHeld = false
    }

    fun resume() {
        if (state.phase != MatchPhase.PAUSED) return
        state.phase = MatchPhase.COUNTDOWN
        state.countdownSeconds = Arena.SERVE_SECONDS
        capturePreviousPositions()
    }

    fun restart() {
        state = createState()
        random = SeededRandom(config.seed)
        ai = OpponentController(config.encounter, random)
        hazards = HazardController(state)
        physics = BallPhysics(state, ::emit, ::score)
        pendingServe = true
        serveDirection = if (random.nextUnit() < 0.5) -1.0 else 1.0
        leftPulseHeld = false
        rightPulseHeld = false
        hazards.reset()
    }

    private fun createState(): MatchState {
        val loadout = if (config.mode == MatchMode.CAMPAIGN) config.loadout else PlayerLoadout()
        val rightSpeed = if (config.mode == MatchMode.LOCAL_VERSUS) Arena.PADDLE_SPEED else OpponentController.speed(config.encounter)
        return MatchState(
            config,
            PaddleState(
                64.0,
                Arena.PADDLE_HEIGHT * (1.0 + 0.08 * loadout.heightRank),
                Arena.PADDLE_SPEED * (1.0 + 0.08 * loadout.speedRank),
                6.0 - 0.5 * loadout.pulseRank,
            ),
            PaddleState(1216.0, Arena.PADDLE_HEIGHT, rightSpeed, 6.0),
        )
    }

    private fun updatePaddle(paddle: PaddleState, requestedTarget: Double?, requestedAxis: Double, acceleration: Double? = null) {
        val axis = if (requestedAxis.isFinite()) requestedAxis.coerceIn(-1.0, 1.0) else 0.0
        val target = when {
            requestedTarget != null && requestedTarget.isFinite() -> requestedTarget
            else -> paddle.y + axis * paddle.speed * Arena.STEP_SECONDS
        }.coerceIn(paddle.height / 2, Arena.HEIGHT - paddle.height / 2)
        val desiredVelocity = ((target - paddle.y) / Arena.STEP_SECONDS).coerceIn(-paddle.speed, paddle.speed)
        // The opponent has visible momentum: rapid changes of aim can outmaneuver it.
        val velocity = if (acceleration == null) {
            desiredVelocity
        } else {
            paddle.velocityY + (desiredVelocity - paddle.velocityY).coerceIn(-acceleration * Arena.STEP_SECONDS, acceleration * Arena.STEP_SECONDS)
        }
        val nextY = (paddle.y + velocity * Arena.STEP_SECONDS).coerceIn(paddle.height / 2, Arena.HEIGHT - paddle.height / 2)
        paddle.velocityY = (nextY - paddle.y) / Arena.STEP_SECONDS
        paddle.y = nextY
    }

    private fun updatePulse(paddle: PaddleState, pressed: Boolean, side: Side) {
        paddle.pulseArmedSeconds = max(0.0, paddle.pulseArmedSeconds - Arena.STEP_SECONDS)
        paddle.pulseCooldownSeconds = max(0.0, paddle.pulseCooldownSeconds - Arena.STEP_SECONDS)
        if (pressed && paddle.pulseCooldownSeconds <= TIME_EPSILON) {
            paddle.pulseArmedSeconds = 0.6
            paddle.pulseCooldownSeconds = paddle.pulseDuration
            emit(MatchEvent(MatchEventKind.PULSE_ARMED, side, paddle.x, paddle.y))
        }
    }

    private fun capturePreviousPositions() {
        state.ball.previousX = state.ball.x
        state.ball.previousY = state.ball.y
        state.left.previousY = state.left.y
        state.right.previousY = state.right.y
    }

    private fun serve() {
        val angle = (random.nextUnit() * 40.0 - 20.0) * Math.PI / 180.0
        state.ball.velocityX = serveDirection * Arena.INITIAL_BALL_SPEED * cos(angle)
        state.ball.velocityY = Arena.INITIAL_BALL_SPEED * sin(angle)
        pendingServe = false
    }

    private fun score(scorer: Side) {
        if (state.phase != MatchPhase.PLAYING) return
        if (scorer == Side.LEFT) {
            state.leftScore++
            if (state.isBoss) state.rightHull--
        } else {
            state.rightScore++
            if (state.isBoss) state.leftHull--
        }
        emit(MatchEvent(MatchEventKind.GOAL, scorer, state.ball.x, state.ball.y))
        val winningScore = if (config.mode == MatchMode.LOCAL_VERSUS) 7 else 5
        val finished = if (state.isBoss) {
            state.leftHull <= 0 || state.rightHull <= 0
        } else {
            state.leftScore >= winningScore || state.rightScore >= winningScore
        }
        if (finished) {
            state.winner = scorer
            state.phase = MatchPhase.FINISHED
            state.ball.velocityX = 0.0
            state.ball.velocityY = 0.0
            emit(MatchEvent(MatchEventKind.MATCH_FINISHED, scorer, state.ball.x, state.ball.y))
            return
        }
        resetRally()
        serveDirection = if (scorer == Side.RIGHT) -1.0 else 1.0
    }

    private fun resetRally() {
        state.ball.apply {
            x = Arena.WIDTH / 2
            y = Arena.HEIGHT / 2
            velocityX = 0.0
            velocityY = 0.0
            normalSpeed = Arena.INITIAL_BALL_SPEED
            pulsed = false
            pulseOwner = null
        }
        for (paddle in listOf(state.left, state.right)) {
            paddle.y = Arena.HEIGHT / 2
            paddle.previousY = paddle.y
            paddle.velocityY = 0.0
            paddle.pulseArmedSeconds = 0.0
            paddle.pulseCooldownSeconds = 0.0
        }
        state.rallySeconds = 0.0
        state.phase = MatchPhase.COUNTDOWN
        state.countdownSeconds = Arena.SERVE_SECONDS
        pendingServe = true
        ai.reset()
        hazards.reset()
        capturePreviousPositions()
    }

    private fun emit(event: MatchEvent) {
        eventListener?.invoke(event)
    }

    private companion object {
        const val TIME_EPSILON = 1e-9
    }
}

/** Small explicit generator makes replay behavior independent of Kotlin's Random implementation. */
internal class SeededRandom(seed: Long) {
    private var value = if (seed == 0L) 0x6a09e667f3bcc909L else seed
    fun nextUnit(): Double {
        value = value xor (value shl 13)
        value = value xor (value ushr 7)
        value = value xor (value shl 17)
        return (value ushr 11).toDouble() / 9007199254740992.0
    }
}
