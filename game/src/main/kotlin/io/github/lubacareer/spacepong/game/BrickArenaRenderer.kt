package io.github.lubacareer.spacepong.game

import com.badlogic.gdx.Application.ApplicationType
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import io.github.lubacareer.spacepong.bricks.BrickEngine
import io.github.lubacareer.spacepong.bricks.BrickPhase
import io.github.lubacareer.spacepong.bricks.BrickPower
import io.github.lubacareer.spacepong.progression.GameSettings
import io.github.lubacareer.spacepong.simulation.Arena
import kotlin.math.ceil

/** The silhouettes and ship hardware use the same batched canvas as the existing arenas. */
internal class BrickArenaRenderer(private val levelIndex: Int, private val levelName: String) {
    val pauseButton = MenuButton("pause", 1110f, 624f, 160f, 96f, "PAUSE")
    private val accent = when (levelIndex) {
        1, 4 -> Palette.amber
        2, 5 -> Palette.pink
        else -> Palette.cyan
    }
    private var impactX = 0f
    private var impactY = 0f
    private var impactLife = 0f

    fun impact(x: Float, y: Float) {
        impactX = x
        impactY = y
        impactLife = IMPACT_DURATION
    }

    fun advance(delta: Float) {
        impactLife = (impactLife - delta).coerceAtLeast(0f)
    }

    fun draw(canvas: Canvas, engine: BrickEngine, settings: GameSettings) {
        canvas.configureAccessibility(settings.largeText, settings.highContrast)
        for ((index, brick) in engine.bricks.withIndex()) {
            if (!brick.alive) continue
            val x = brick.x.toFloat()
            val y = brick.y.toFloat()
            val w = brick.width.toFloat()
            val h = brick.height.toFloat()
            val power = brick.pickup
            val trim = if (power != null) pickupColor(power) else accent

            // Lit edges trace the grid silhouette and leave a visible seam at adjoining bricks.
            canvas.rect(x + 1f, y + 1f, w - 2f, h - 2f, trim, if (settings.highContrast) .8f else .55f)
            canvas.rect(x + 3f, y + 3f, w - 6f, h - 7f, Palette.panelLight)
            canvas.rect(x + 3f, y + h - 5f, w - 6f, 2f, trim)
            if (power != null) {
                powerIcon(canvas, power, x + w / 2f, y + h / 2f, minOf(9f, h / 3f), trim)
            } else if (!settings.lowEffects) {
                val windowY = y + h * .42f
                val lit = if (index % 5 == 0) Palette.ink else trim
                canvas.rect(x + w * .18f, windowY, w * .15f, 3f, lit, .85f)
                canvas.rect(x + w * .40f, windowY, w * .15f, 3f, trim, .65f)
            }
        }
        for (drop in engine.drops) {
            val color = pickupColor(drop.power)
            val x = drop.x.toFloat()
            val y = drop.y.toFloat()
            val radius = BrickEngine.DROP_RADIUS.toFloat()
            if (!settings.reducedMotion && !settings.lowEffects) canvas.circle(x, y, radius + 5f, color, .2f)
            canvas.circle(x, y, radius, color)
            canvas.circle(x, y, radius - 2f, Palette.panel)
            powerIcon(canvas, drop.power, x, y, radius - 3f, color)
        }
        if (engine.shield) {
            val shieldY = BrickEngine.SHIELD_Y.toFloat()
            canvas.rect(0f, shieldY, Arena.WIDTH.toFloat(), 3f, Palette.amber)
            for (i in 0 until 16) canvas.rect(12f + i * 80f, shieldY - 4f, 28f, 2f, Palette.amber, .65f)
        }
        paddle(canvas, engine)
        if (impactLife > 0f && !settings.reducedMotion && !settings.lowEffects) {
            val alpha = impactLife / IMPACT_DURATION
            val offset = 6f + 18f * (1f - alpha)
            for (dx in -1..1 step 2) {
                for (dy in -1..1 step 2) {
                    canvas.line(impactX + dx * offset, impactY + dy * offset, impactX + dx * (offset + 5f), impactY + dy * (offset + 4f), 2f, accent, alpha)
                }
            }
        }
        hud(canvas, engine)
        if (engine.phase == BrickPhase.DOCKED) dockHint(canvas)

        val ballX = engine.ballX.toFloat()
        val ballY = engine.ballY.toFloat()
        val radius = BrickEngine.BALL_RADIUS.toFloat()
        if (!settings.lowEffects && !settings.reducedMotion) canvas.circle(ballX, ballY, radius + 5f, Palette.cyan, .16f)
        canvas.circle(ballX, ballY, radius, Palette.ink)
    }

    private fun paddle(c: Canvas, engine: BrickEngine) {
        val center = engine.paddleX.toFloat()
        val y = BrickEngine.PADDLE_Y.toFloat()
        val width = engine.paddleWidth.toFloat()
        val h = BrickEngine.PADDLE_HEIGHT.toFloat()
        val left = center - width / 2f
        val right = center + width / 2f
        val color = if (engine.wideSeconds > 0.0) Palette.pink else Palette.cyan
        c.rounded(left, y - h / 2f, width, h, h / 3f, color)
        c.rect(left + 9f, y - h / 2f + 2f, width - 18f, h * .44f, Palette.panel)
        c.rect(center - 16f, y + h / 2f - 3f, 32f, 3f, Palette.ink)
        c.rect(left + 5f, y, 4f, h * .4f, Palette.ink)
        c.rect(right - 9f, y, 4f, h * .4f, Palette.ink)
    }

    private fun hud(c: Canvas, engine: BrickEngine) {
        c.rect(0f, 625f, Arena.WIDTH.toFloat(), 95f, Palette.background, .95f)
        c.rect(0f, 624f, Arena.WIDTH.toFloat(), 1f, Palette.line)
        c.text("BUILDING ${levelIndex + 1}", 32f, 697f, 15f, accent, true)
        c.text(levelName, 32f, 669f, 27f, Palette.ink, true)
        stat(c, 410f, "LIVES", engine.lives.toString())
        stat(c, 570f, "BRICKS", engine.remainingBricks.toString())
        stat(c, 745f, "SCORE", engine.score.toString())
        stat(c, 940f, "COMBO", "x${engine.combo.coerceIn(1, 4)}")
        c.rounded(pauseButton.x + 12f, pauseButton.y + 14f, pauseButton.width - 24f, pauseButton.height - 28f, 12f, Palette.panelLight)
        c.centered(pauseButton.title, pauseButton.x + pauseButton.width / 2, pauseButton.y + 59f, 19f, Palette.ink, true)
        if (engine.wideSeconds > 0 || engine.shield) {
            val wide = if (engine.wideSeconds > 0) "WIDE ${ceil(engine.wideSeconds).toInt()}s" else ""
            val shield = if (engine.shield) "SHIELD" else ""
            val label = if (wide.isNotEmpty() && shield.isNotEmpty()) "$wide   /   $shield" else wide + shield
            c.centered(label, 640f, 57f, 17f, Palette.ink, true)
        }
    }

    private fun stat(c: Canvas, x: Float, label: String, value: String) {
        c.centered(label, x, 698f, 14f, Palette.muted, true)
        c.centered(value, x, 671f, 26f, Palette.ink, true)
    }

    private fun dockHint(c: Canvas) {
        val desktop = Gdx.app.type == ApplicationType.Desktop
        val title = if (desktop) "Space to launch" else "Tap to launch"
        c.centered(title, 640f, 208f, 28f, Palette.ink, true)
        if (levelIndex == 0) {
            val hint = if (desktop) "Steer with the arrows. Aim with the paddle edge." else "Drag to steer. Aim with the paddle edge."
            c.centered(hint, 640f, 169f, 19f, Palette.muted)
            powerIcon(c, BrickPower.WIDE_PADDLE, 495f, 128f, 10f, Palette.pink)
            c.text("Wide paddle", 516f, 138f, 17f, Palette.ink)
            powerIcon(c, BrickPower.SHIELD, 679f, 128f, 10f, Palette.amber)
            c.text("Shield", 698f, 138f, 17f, Palette.ink)
        }
    }

    private fun pickupColor(power: BrickPower) = if (power == BrickPower.SHIELD) Palette.amber else Palette.pink

    private fun powerIcon(c: Canvas, power: BrickPower, x: Float, y: Float, r: Float, color: Color) {
        when (power) {
            BrickPower.WIDE_PADDLE -> {
                c.line(x - r, y, x + r, y, 2f, color)
                c.triangle(x - r, y, x - r * .45f, y + r * .5f, x - r * .45f, y - r * .5f, color)
                c.triangle(x + r, y, x + r * .45f, y - r * .5f, x + r * .45f, y + r * .5f, color)
            }
            BrickPower.SHIELD -> {
                c.rect(x - r * .63f, y, r * 1.26f, r * .75f, color)
                c.triangle(x - r * .63f, y, x + r * .63f, y, x, y - r * .8f, color)
            }
        }
    }

    private companion object {
        const val IMPACT_DURATION = .18f
    }
}
