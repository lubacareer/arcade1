package io.github.lubacareer.spacepong.game

import kotlin.math.abs

/** An explicitly visual practice target; it never mutates the paused match simulation. */
class TutorialDrill(private val paddleHeight: Float = 120f, private val speed: Float = 1000f) {
    var stage = 1
        private set
    var ghostY = 360f
        private set
    private var demoRemaining = 0f
    val pulseDemoProgress: Float
        get() = if (stage == 3) (1f - demoRemaining / DEMO_SECONDS).coerceIn(0f, 1f) else -1f

    fun update(delta: Float, target: Float?, axis: Float, pulse: Boolean): Boolean {
        if (!delta.isFinite() || delta <= 0f) return false
        val elapsed = delta.coerceAtMost(.1f)
        if (stage == 3) {
            demoRemaining = (demoRemaining - elapsed).coerceAtLeast(0f)
            return demoRemaining <= .00001f
        }
        val boundedAxis = if (axis.isFinite()) axis.coerceIn(-1f, 1f) else 0f
        val goal = if (target != null && target.isFinite()) target else ghostY + boundedAxis * speed * elapsed
        val bounded = goal.coerceIn(paddleHeight / 2, 720f - paddleHeight / 2)
        ghostY += (bounded - ghostY).coerceIn(-speed * elapsed, speed * elapsed)
        if (stage == 1) {
            if (abs(ghostY - 360f) >= 80f) stage = 2
            return false
        }
        if (pulse) {
            stage = 3
            demoRemaining = DEMO_SECONDS
        }
        return false
    }

    private companion object {
        const val DEMO_SECONDS = 1f
    }
}
