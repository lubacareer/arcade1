package io.github.lubacareer.spacepong.simulation

import kotlin.math.min

/** Decouples display cadence from gameplay and retains input edges until a simulation tick consumes them. */
class FixedStepRunner(private val engine: MatchEngine) {
    private var accumulated = 0.0
    private var pendingLeftPulse = false
    private var pendingRightPulse = false
    val alpha: Double get() = (accumulated / Arena.STEP_SECONDS).coerceIn(0.0, 1.0)

    fun clear() {
        accumulated = 0.0
        pendingLeftPulse = false
        pendingRightPulse = false
    }

    fun advance(deltaSeconds: Double, input: InputFrame = InputFrame()): Int {
        if (engine.state.phase == MatchPhase.PAUSED || engine.state.phase == MatchPhase.FINISHED) {
            clear()
            return 0
        }
        pendingLeftPulse = pendingLeftPulse || input.leftPulse
        pendingRightPulse = pendingRightPulse || input.rightPulse
        return advance(deltaSeconds) {
            val command = input.copy(leftPulse = pendingLeftPulse, rightPulse = pendingRightPulse)
            pendingLeftPulse = false
            pendingRightPulse = false
            command
        }
    }

    /** Recording playback supplies commands by simulation tick, never by display-frame number. */
    fun advance(deltaSeconds: Double, inputForTick: (Long) -> InputFrame): Int {
        if (engine.state.phase == MatchPhase.PAUSED || engine.state.phase == MatchPhase.FINISHED) {
            clear()
            return 0
        }
        if (!deltaSeconds.isFinite() || deltaSeconds <= 0.0) return 0
        accumulated = min(accumulated + deltaSeconds, MAX_STEPS * Arena.STEP_SECONDS)
        var steps = 0
        while (accumulated + 1e-10 >= Arena.STEP_SECONDS && steps < MAX_STEPS) {
            engine.step(inputForTick(engine.state.tick))
            accumulated = (accumulated - Arena.STEP_SECONDS).coerceAtLeast(0.0)
            steps++
            if (engine.state.phase == MatchPhase.FINISHED || engine.state.phase == MatchPhase.PAUSED) {
                clear()
                break
            }
        }
        return steps
    }

    private companion object {
        const val MAX_STEPS = 8
    }
}
