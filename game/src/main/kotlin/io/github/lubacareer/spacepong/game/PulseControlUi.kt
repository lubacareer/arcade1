package io.github.lubacareer.spacepong.game

import io.github.lubacareer.spacepong.simulation.MatchPhase
import java.util.Locale

data class PulseUiState(val enabled: Boolean, val label: String, val emphasized: Boolean = false)

/** The displayed Pulse affordance and input acceptance share the same availability rules. */
object PulseControlUi {
    fun create(phase: MatchPhase, tutorialStage: Int, armedSeconds: Double, cooldownSeconds: Double): PulseUiState {
        if (tutorialStage > 0) {
            return when (tutorialStage) {
                1 -> PulseUiState(false, "MOVE FIRST")
                2 -> PulseUiState(true, "TRY PULSE")
                else -> PulseUiState(false, "BOOST!", emphasized = true)
            }
        }
        if (phase != MatchPhase.PLAYING) {
            return PulseUiState(
                false,
                when (phase) {
                    MatchPhase.COUNTDOWN -> "GET READY"
                    MatchPhase.PAUSED -> "PAUSED"
                    else -> "FINISHED"
                },
            )
        }
        if (armedSeconds > 0) return PulseUiState(false, "ARMED", emphasized = true)
        if (cooldownSeconds > 0) return PulseUiState(false, String.format(Locale.ROOT, "%.1fs", cooldownSeconds))
        return PulseUiState(true, "PULSE")
    }
}
