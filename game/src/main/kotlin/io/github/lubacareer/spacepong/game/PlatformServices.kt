package io.github.lubacareer.spacepong.game

import java.io.File

data class SafeInsets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

/** Platform effects are deliberately separated from deterministic game rules. */
interface PlatformServices {
    val saveDirectory: File
    fun hasAudioFocus(): Boolean = true
    fun haptic(strong: Boolean)
    fun setHighRefresh(enabled: Boolean)
    fun safeInsets(): SafeInsets = SafeInsets()
}

data class GameDiagnostics(
    val page: String,
    val matchPhase: String?,
    val encounter: String?,
    val leftScore: Int,
    val rightScore: Int,
    val leftHull: Int,
    val rightHull: Int,
    val leftPaddleY: Double = 0.0,
    val rightPaddleY: Double = 0.0,
    val leftPulseCooldown: Double = 0.0,
    val rightPulseCooldown: Double = 0.0,
    val tutorialStep: Int = 0,
    val tutorialGhostY: Double = 360.0,
)

/** Read-only screen-pixel hit bounds, with a top-left origin, for accessibility/testing adapters. */
data class UiTarget(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val enabled: Boolean,
)
