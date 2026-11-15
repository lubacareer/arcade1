package io.github.lubacareer.spacepong.simulation

import kotlin.math.max
import kotlin.math.min

/** Predicts only a current trajectory. Reaction delay and aim error remain explicit difficulty controls. */
internal class OpponentController(private val encounter: Encounter, private val random: SeededRandom) {
    private var nextReaction = 0.0
    private var targetY = Arena.HEIGHT / 2

    fun reset() {
        nextReaction = 0.0
        targetY = Arena.HEIGHT / 2
    }

    fun target(state: MatchState): Double {
        nextReaction -= Arena.STEP_SECONDS
        if (nextReaction <= 0.0) {
            nextReaction += reactionSeconds(encounter)
            val ball = state.ball
            val projectedY = if (ball.velocityX > 0.0) {
                // A short visible-trajectory horizon keeps the AI fallible. Full-flight
                // prediction otherwise guarantees a catch before almost every shot.
                val seconds = min(PREDICTION_SECONDS, max(0.0, (state.right.x - ball.x) / ball.velocityX))
                reflectedY(ball.y + ball.velocityY * seconds)
            } else {
                Arena.HEIGHT / 2
            }
            targetY = projectedY + (random.nextUnit() * 2.0 - 1.0) * aimError(encounter)
        }
        return targetY
    }

    private fun reflectedY(y: Double): Double {
        val span = Arena.HEIGHT - 2 * Arena.BALL_RADIUS
        val period = 2 * span
        val wrapped = ((y - Arena.BALL_RADIUS) % period + period) % period
        return Arena.BALL_RADIUS + if (wrapped <= span) wrapped else period - wrapped
    }

    companion object {
        const val MAX_ACCELERATION = 2000.0
        private const val PREDICTION_SECONDS = 0.12

        fun speed(encounter: Encounter): Double = when (encounter) {
            Encounter.FLIGHT_SCHOOL -> 450.0
            Encounter.ASTEROID_PASSAGE -> 525.0
            Encounter.GRAVITY_RELAY -> 600.0
            Encounter.WARDEN -> 650.0
        }

        private fun reactionSeconds(encounter: Encounter): Double = when (encounter) {
            Encounter.FLIGHT_SCHOOL -> 0.180
            Encounter.ASTEROID_PASSAGE -> 0.140
            Encounter.GRAVITY_RELAY -> 0.110
            Encounter.WARDEN -> 0.100
        }

        private fun aimError(encounter: Encounter): Double = when (encounter) {
            Encounter.FLIGHT_SCHOOL -> 65.0
            Encounter.ASTEROID_PASSAGE -> 45.0
            Encounter.GRAVITY_RELAY -> 30.0
            Encounter.WARDEN -> 25.0
        }
    }
}
