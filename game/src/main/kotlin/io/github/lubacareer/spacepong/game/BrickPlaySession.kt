package io.github.lubacareer.spacepong.game

import com.badlogic.gdx.Input
import io.github.lubacareer.spacepong.bricks.BrickBuildings
import io.github.lubacareer.spacepong.bricks.BrickEngine
import io.github.lubacareer.spacepong.bricks.BrickEvent
import io.github.lubacareer.spacepong.bricks.BrickPhase
import io.github.lubacareer.spacepong.progression.GameSettings
import io.github.lubacareer.spacepong.simulation.Arena

internal class BrickPlaySession(
    levelIndex: Int,
    private val onFeedback: (SoundEffect, Boolean?) -> Unit = { _, _ -> },
) {
    private val engine = BrickEngine(levelIndex)
    private val arena = BrickArenaRenderer(levelIndex, BrickBuildings.levels[levelIndex].name)
    val pauseButton get() = arena.pauseButton
    val score get() = engine.score
    val lives get() = engine.lives
    val paddleX get() = engine.paddleX
    val phaseName get() = engine.phase.name
    val paused get() = engine.phase == BrickPhase.PAUSED
    val won: Boolean?
        get() = when (engine.phase) {
            BrickPhase.WON -> true
            BrickPhase.LOST -> false
            else -> null
        }

    private var owner: Int? = null
    private var startX = 0f
    private var startY = 0f
    private var startPaddle = 0.0
    private var target: Double? = null
    private var tapCandidate = false
    private var launchPending = false
    private var left = false
    private var right = false
    private var spaceHeld = false
    private val canPlay get() = engine.phase == BrickPhase.DOCKED || engine.phase == BrickPhase.PLAYING

    init {
        engine.eventListener = { event ->
            when (event) {
                BrickEvent.BRICK_HIT -> {
                    arena.impact(engine.ballX.toFloat(), engine.ballY.toFloat())
                    onFeedback(SoundEffect.HIT, null)
                }
                BrickEvent.PADDLE_HIT -> onFeedback(SoundEffect.HIT, false)
                BrickEvent.WALL_HIT -> onFeedback(SoundEffect.HIT, null)
                BrickEvent.PICKUP, BrickEvent.SHIELD_SAVE -> onFeedback(SoundEffect.PULSE, false)
                BrickEvent.LIFE_LOST -> onFeedback(SoundEffect.GOAL, true)
                BrickEvent.LOST -> Unit // The final miss already emitted LIFE_LOST.
                BrickEvent.WON -> onFeedback(SoundEffect.VICTORY, true)
            }
        }
    }

    fun update(delta: Float) {
        if (!canPlay || delta <= 0f || !delta.isFinite()) return
        val axis = (if (right) 1.0 else 0.0) - (if (left) 1.0 else 0.0)
        arena.advance(delta)
        engine.advance(delta.toDouble(), target, axis, launchPending)
        launchPending = false
    }

    fun draw(canvas: Canvas, settings: GameSettings) = arena.draw(canvas, engine, settings)

    fun down(pointer: Int, x: Float, y: Float): Boolean {
        if (!canPlay || owner != null || x !in 0f..Arena.WIDTH.toFloat() || y !in 0f..Arena.HEIGHT.toFloat()) return false
        owner = pointer
        startX = x
        startY = y
        startPaddle = engine.paddleX
        target = startPaddle
        tapCandidate = engine.phase == BrickPhase.DOCKED
        return true
    }

    fun drag(pointer: Int, x: Float, y: Float): Boolean {
        if (owner != pointer) return false
        val dx = x - startX
        val dy = y - startY
        if (dx * dx + dy * dy > TAP_SLOP * TAP_SLOP) tapCandidate = false
        target = startPaddle + dx
        return true
    }

    fun up(pointer: Int, x: Float, y: Float): Boolean {
        if (!drag(pointer, x, y)) return false
        if (tapCandidate && engine.phase == BrickPhase.DOCKED) launchPending = true
        // Without a held arrow, retain the last drag position for gestures released between simulation ticks.
        if (left || right) target = null
        owner = null
        tapCandidate = false
        return true
    }

    fun cancel(pointer: Int): Boolean {
        if (owner != pointer) return false
        owner = null
        target = null
        tapCandidate = false
        return true
    }

    fun keyDown(keycode: Int): Boolean {
        if (!canPlay) return false
        when (keycode) {
            Input.Keys.LEFT -> {
                left = true
                if (owner == null) target = null
            }
            Input.Keys.RIGHT -> {
                right = true
                if (owner == null) target = null
            }
            Input.Keys.SPACE -> {
                if (!spaceHeld && engine.phase == BrickPhase.DOCKED) launchPending = true
                spaceHeld = true
            }
            else -> return false
        }
        return true
    }

    fun keyUp(keycode: Int): Boolean {
        when (keycode) {
            Input.Keys.LEFT -> left = false
            Input.Keys.RIGHT -> right = false
            Input.Keys.SPACE -> spaceHeld = false
            else -> return false
        }
        return true
    }

    fun clearInput() {
        owner = null
        target = null
        tapCandidate = false
        launchPending = false
        left = false
        right = false
        spaceHeld = false
    }

    fun pause() {
        clearInput()
        engine.pause()
    }

    fun resume() {
        clearInput()
        engine.resume()
    }

    private companion object {
        const val TAP_SLOP = 16f
    }
}
