package io.github.lubacareer.spacepong.game

import kotlin.math.abs

/** An explicitly visual practice target; it never mutates the paused match simulation. */
class TutorialDrill(private val paddleHeight: Float = 120f, private val speed: Float = 1000f) {
    var stage = 1
        private set
    var ghostY = 360f
        private set

    fun update(delta: Float, target: Float?, axis: Float, pulse: Boolean): Boolean {
        if (!delta.isFinite() || delta <= 0f) return false
        val elapsed = delta.coerceAtMost(.1f)
        val boundedAxis = if (axis.isFinite()) axis.coerceIn(-1f, 1f) else 0f
        val goal = if (target != null && target.isFinite()) target else ghostY + boundedAxis * speed * elapsed
        val bounded = goal.coerceIn(paddleHeight / 2, 720f - paddleHeight / 2)
        ghostY += (bounded - ghostY).coerceIn(-speed * elapsed, speed * elapsed)
        if (stage == 1) {
            if (abs(ghostY - 360f) >= 80f) stage = 2
            return false
        }
        return pulse
    }
}
