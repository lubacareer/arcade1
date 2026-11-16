package io.github.lubacareer.spacepong.simulation

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sqrt

internal class HazardController(private val state: MatchState) {
    private var asteroidCycle = -1
    private var previousPhase = HazardPhase.INACTIVE

    fun reset() {
        state.bossPhase = when {
            !state.isBoss || state.rightHull > 4 -> 1
            state.rightHull > 2 -> 2
            else -> 3
        }
        state.hazardKind = when {
            state.config.mode == MatchMode.LOCAL_VERSUS -> HazardKind.NONE
            state.config.encounter == Encounter.ASTEROID_PASSAGE -> HazardKind.ASTEROIDS
            state.config.encounter == Encounter.GRAVITY_RELAY -> HazardKind.GRAVITY
            state.isBoss && state.bossPhase == 2 -> HazardKind.ASTEROIDS
            state.isBoss && state.bossPhase == 3 -> HazardKind.GRAVITY
            else -> HazardKind.NONE
        }
        state.hazardPhase = HazardPhase.INACTIVE
        previousPhase = HazardPhase.INACTIVE
        asteroidCycle = -1
        for (bumper in state.bumpers) {
            bumper.active = false
            bumper.skippedThisCycle = false
        }
    }

    fun update() {
        when (state.hazardKind) {
            HazardKind.NONE -> state.hazardPhase = HazardPhase.INACTIVE
            HazardKind.ASTEROIDS -> updateAsteroids()
            HazardKind.GRAVITY -> updateGravity()
        }
    }

    private fun updateAsteroids() {
        val cycle = (state.rallySeconds / 9.0).toInt()
        val phaseTime = state.rallySeconds % 9.0
        val phase = phase(phaseTime, 5.0)
        if (cycle != asteroidCycle) {
            asteroidCycle = cycle
            for (bumper in state.bumpers) bumper.skippedThisCycle = false
        }
        for (bumper in state.bumpers) {
            if (phase == HazardPhase.ACTIVE && previousPhase != HazardPhase.ACTIVE) {
                val dx = state.ball.x - bumper.x
                val dy = state.ball.y - bumper.y
                val clearance = bumper.radius + Arena.BALL_RADIUS
                bumper.skippedThisCycle = dx * dx + dy * dy <= clearance * clearance
            }
            bumper.active = phase == HazardPhase.ACTIVE && !bumper.skippedThisCycle
        }
        state.hazardPhase = phase
        previousPhase = phase
    }

    private fun updateGravity() {
        state.hazardPhase = phase(state.rallySeconds % 10.0, 4.0)
        if (state.hazardPhase != HazardPhase.ACTIVE) return
        val ball = state.ball
        val dx = ball.x - Arena.WIDTH / 2
        val dy = ball.y - Arena.HEIGHT / 2
        if (dx * dx + dy * dy > 150.0 * 150.0) return
        val originalSpeed = hypot(ball.velocityX, ball.velocityY)
        if (originalSpeed == 0.0) return
        val nextY = ball.velocityY - dy.sign * 300.0 * Arena.STEP_SECONDS
        val length = hypot(ball.velocityX, nextY)
        ball.velocityY = nextY / length * originalSpeed
        // Keep trajectories traversable instead of allowing nearly vertical or reversing orbits.
        val minimumX = originalSpeed * cos(Math.toRadians(65.0))
        val nextX = max(abs(ball.velocityX / length * originalSpeed), minimumX)
        ball.velocityX = if (ball.velocityX < 0) -nextX else nextX
        ball.velocityY = ball.velocityY.sign * sqrt(max(0.0, originalSpeed * originalSpeed - nextX * nextX))
    }

    private fun phase(time: Double, activeUntil: Double): HazardPhase = when {
        time < 1.0 -> HazardPhase.WARNING
        time < activeUntil -> HazardPhase.ACTIVE
        else -> HazardPhase.INACTIVE
    }
}
