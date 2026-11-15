package io.github.lubacareer.spacepong.game

import io.github.lubacareer.spacepong.progression.GameSettings
import io.github.lubacareer.spacepong.simulation.*
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Particle and trail buffers are bounded and reused throughout a match. */
class ArenaRenderer(private val canvas: Canvas) {
    private val random = Random(440)
    private val px = FloatArray(100)
    private val py = FloatArray(100)
    private val vx = FloatArray(100)
    private val vy = FloatArray(100)
    private val life = FloatArray(100)
    private val trailX = FloatArray(24)
    private val trailY = FloatArray(24)
    private var trailCursor = 0
    private var trailCount = 0
    private var nextParticle = 0
    private var flash = 0f
    private var previousTick = -1L
    val pauseButton = MenuButton("pause", 1070f, 606f, 160f, 96f, "PAUSE")
    val soloPulse = MenuButton("pulse-left", 1008f, 26f, 180f, 132f, "PULSE")
    val versusLeftPulse = MenuButton("pulse-left", 168f, 26f, 180f, 132f, "PULSE")
    val versusRightPulse = MenuButton("pulse-right", 932f, 26f, 180f, 132f, "PULSE")

    fun event(event: MatchEvent, settings: GameSettings) {
        if (event.kind == MatchEventKind.GOAL) {
            trailCount = 0
            if (settings.screenShake && !settings.reducedMotion) flash = .18f
        }
        if (settings.reducedMotion || settings.lowEffects) return
        val count = if (event.kind == MatchEventKind.GOAL) 28 else 12
        repeat(count) {
            val i = nextParticle++ % life.size
            px[i] = event.x.toFloat()
            py[i] = event.y.toFloat()
            val angle = random.nextFloat() * Math.PI * 2
            val speed = 70 + random.nextFloat() * 190
            vx[i] = cos(angle).toFloat() * speed
            vy[i] = sin(angle).toFloat() * speed
            life[i] = .35f + random.nextFloat() * .25f
        }
    }

    fun shakeOffsetX(): Float = sin(flash * 140f) * flash * 15f
    fun shakeOffsetY(): Float = cos(flash * 170f) * flash * 10f

    fun reset() {
        trailCount = 0
        trailCursor = 0
        life.fill(0f)
        flash = 0f
        previousTick = -1
    }

    fun draw(state: MatchState, alpha: Float, delta: Float, settings: GameSettings, local: Boolean, tutorial: Int, tutorialGhostY: Float = 360f) {
        val ballX = lerp(state.ball.previousX, state.ball.x, alpha)
        val ballY = lerp(state.ball.previousY, state.ball.y, alpha)
        if (state.tick != previousTick) {
            if (state.phase == MatchPhase.PLAYING) {
                trailX[trailCursor] = ballX
                trailY[trailCursor] = ballY
                trailCursor = (trailCursor + 1) % trailX.size
                trailCount = (trailCount + 1).coerceAtMost(trailX.size)
            }
            previousTick = state.tick
        }
        canvas.line(26f, 10f, 1254f, 10f, 2f, Palette.line)
        canvas.line(26f, 710f, 1254f, 710f, 2f, Palette.line)
        for (i in 0..11) canvas.line(640f, i * 60f + 16, 640f, i * 60f + 35, 1f, Palette.line, .6f)
        canvas.line(20f, 32f, 20f, 688f, 2f, Palette.cyan, .3f)
        canvas.line(1260f, 32f, 1260f, 688f, 2f, Palette.pink, .3f)
        hazards(state, settings)
        if (!settings.lowEffects && !settings.reducedMotion) {
            for (j in 0 until trailCount) {
                val i = (trailCursor - 1 - j + trailX.size) % trailX.size
                val intensity = (trailCount - j).toFloat() / trailX.size
                canvas.circle(
                    trailX[i],
                    trailY[i],
                    3f + intensity * 5f,
                    if (state.ball.pulsed) Palette.amber else Palette.cyan,
                    intensity * .35f,
                )
            }
        }
        paddle(state.left, alpha, Palette.cyan, settings)
        paddle(state.right, alpha, if (settings.highContrast) Palette.amber else Palette.pink, settings)
        particles(delta, settings)
        hud(state, local, settings)
        if (state.phase == MatchPhase.COUNTDOWN && tutorial == 0) {
            canvas.circle(640f, 360f, 58f, Palette.background, .9f)
            canvas.centered(ceil(state.countdownSeconds).toInt().coerceAtLeast(1).toString(), 640f, 395f, 60f, Palette.ink, true)
            canvas.centered("GET READY", 640f, 278f, 16f, Palette.cyan, true)
        }
        if (tutorial > 0) {
            practiceHologram(tutorialGhostY, state.left.height.toFloat())
            canvas.rounded(378f, 222f, 524f, 238f, 20f, Palette.panel, .96f)
            canvas.centered("FLIGHT SCHOOL / $tutorial OF 2", 640f, 433f, 17f, Palette.cyan, true)
            canvas.centered(if (tutorial == 1) "Steer the hologram" else "Arm a Pulse Return", 640f, 385f, 32f, Palette.ink, true)
            canvas.wrap(
                if (tutorial == 1) {
                    "Drag on the left to move the hologram. W / S on keyboard."
                } else {
                    "Tap PULSE on the right. Time it just before a return. Keyboard: left Shift."
                },
                415f,
                329f,
                450f,
                22f,
            )
            canvas.centered("Move / Pulse to continue, or tap SKIP.", 640f, 250f, 16f, Palette.muted)
        }
        // The ball is always the final arena shape so HUD/control effects never hide it.
        if (state.phase == MatchPhase.PLAYING || state.phase == MatchPhase.COUNTDOWN) {
            if (!settings.lowEffects) {
                canvas.circle(ballX, ballY, 27f, if (state.ball.pulsed) Palette.amber else Palette.cyan, .09f)
                canvas.circle(ballX, ballY, 18f, Palette.ink, .08f)
            }
            canvas.circle(ballX, ballY, 10f, if (state.ball.pulsed) Palette.amber else Palette.ink)
            canvas.circle(ballX - 2f, ballY + 2f, 3f, Palette.background, .15f)
        }
        if (flash > 0f) {
            canvas.rect(0f, 0f, 1280f, 720f, Palette.cyan, flash * .25f)
            flash = (flash - delta).coerceAtLeast(0f)
        }
    }

    private fun practiceHologram(y: Float, height: Float) {
        val x = 106f
        canvas.line(x, 60f, x, 660f, 1f, Palette.cyan, .15f)
        canvas.line(x - 9f, y - height / 2, x + 9f, y - height / 2, 2f, Palette.cyan, .85f)
        canvas.line(x - 9f, y + height / 2, x + 9f, y + height / 2, 2f, Palette.cyan, .85f)
        for (segment in 0 until 6) {
            val bottom = y - height / 2 + segment * height / 6
            canvas.line(x - 9f, bottom, x - 9f, bottom + height / 10, 2f, Palette.cyan, .85f)
            canvas.line(x + 9f, bottom, x + 9f, bottom + height / 10, 2f, Palette.cyan, .85f)
        }
        canvas.text("PRACTICE", x + 23f, (y + 12f).coerceIn(80f, 608f), 16f, Palette.cyan, true)
    }

    private fun paddle(p: PaddleState, alpha: Float, color: com.badlogic.gdx.graphics.Color, settings: GameSettings) {
        val x = p.x.toFloat()
        val y = lerp(p.previousY, p.y, alpha)
        val height = p.height.toFloat()
        if (!settings.lowEffects) canvas.rounded(x - 18f, y - height / 2 - 5, 36f, height + 10, 9f, color, .08f)
        canvas.rounded(x - 9f, y - height / 2, 18f, height, 7f, color)
        canvas.rect(x - 3f, y - height / 2 + 10, 6f, height - 20, Palette.ink, .55f)
        if (p.pulseArmedSeconds > 0) {
            canvas.rounded(x - 18f, y - height / 2 - 8, 36f, height + 16, 8f, Palette.amber, .25f)
        }
    }

    private fun hazards(state: MatchState, settings: GameSettings) {
        if (state.hazardKind == HazardKind.ASTEROIDS) {
            for (bumper in state.bumpers) {
                val x = bumper.x.toFloat()
                val y = bumper.y.toFloat()
                val active = bumper.active
                val warning = state.hazardPhase == HazardPhase.WARNING
                if (active || warning) {
                    canvas.circle(x, y, 32f, if (active) Palette.amber else Palette.line, if (active) .8f else .3f)
                    canvas.ring(x, y, if (warning) 44f else 33f, Palette.amber, if (warning) .6f else 1f, 2f)
                    canvas.line(x - 12, y - 6, x + 9, y + 14, 3f, Palette.background)
                    canvas.circle(x - 12, y + 12, 6f, Palette.background, .7f)
                } else {
                    canvas.ring(x, y, 32f, Palette.line, .35f)
                }
            }
        } else if (state.hazardKind == HazardKind.GRAVITY) {
            val active = state.hazardPhase == HazardPhase.ACTIVE
            val warning = state.hazardPhase == HazardPhase.WARNING
            canvas.circle(640f, 360f, 150f, Palette.pink, if (active) .055f else .012f)
            for (i in 1..4) {
                canvas.ring(
                    640f,
                    360f,
                    i * 37.5f,
                    if (active || warning) Palette.pink else Palette.line,
                    if (active) .45f else .2f,
                    if (settings.highContrast) 2f else 1f,
                )
            }
            if (warning) canvas.centered("GRAVITY INCOMING", 640f, 185f, 15f, Palette.amber, true)
        }
    }

    private fun hud(state: MatchState, local: Boolean, settings: GameSettings) {
        canvas.rounded(370f, 632f, 540f, 72f, 12f, Palette.background, .86f)
        val left = if (state.isBoss) state.leftHull else state.leftScore
        val right = if (state.isBoss) state.rightHull else state.rightScore
        canvas.centered(left.toString(), 506f, 695f, 42f, Palette.cyan, true)
        canvas.centered(right.toString(), 775f, 695f, 42f, Palette.pink, true)
        canvas.centered(
            if (state.isBoss) {
                "HULL / PHASE ${state.bossPhase}"
            } else if (local) {
                "FIRST TO 7"
            } else {
                "FIRST TO 5"
            },
            640f,
            679f,
            14f,
            Palette.muted,
            true,
        )
        canvas.rounded(pauseButton.x, pauseButton.y, pauseButton.width, pauseButton.height, 12f, Palette.panel, .85f)
        canvas.centered("PAUSE", pauseButton.x + pauseButton.width / 2, pauseButton.y + 60f, 20f, Palette.ink, true)
        if (local) {
            pulse(versusLeftPulse, state.left, Palette.cyan, settings)
            pulse(versusRightPulse, state.right, Palette.pink, settings)
        } else {
            pulse(soloPulse, state.left, Palette.cyan, settings)
        }
    }

    private fun pulse(button: MenuButton, p: PaddleState, color: com.badlogic.gdx.graphics.Color, settings: GameSettings) {
        val armed = p.pulseArmedSeconds > 0
        val ready = p.pulseCooldownSeconds <= 0
        canvas.rounded(button.x, button.y, button.width, button.height, 20f, if (armed) Palette.amber else Palette.panel, .85f)
        canvas.ring(button.x + button.width / 2, button.y + 81, 24f, if (armed) Palette.background else color, if (ready || armed) 1f else .35f, 2f)
        canvas.triangle(
            button.x + 83,
            button.y + 93,
            button.x + 102,
            button.y + 81,
            button.x + 83,
            button.y + 69,
            if (armed) Palette.background else color,
            if (ready || armed) 1f else .35f,
        )
        val label = if (armed) {
            "ARMED"
        } else if (ready) {
            "PULSE"
        } else {
            "%.1fs".format(java.util.Locale.ROOT, p.pulseCooldownSeconds)
        }
        canvas.centered(
            label,
            button.x + button.width / 2,
            button.y + 41,
            if (settings.largeText) 22f else 19f,
            if (armed) Palette.background else color,
            true,
        )
        if (!ready) {
            canvas.rect(
                button.x + 18f,
                button.y + 12f,
                (button.width - 36) * (1 - p.pulseCooldownSeconds / p.pulseDuration).toFloat().coerceIn(0f, 1f),
                3f,
                color,
            )
        }
    }

    private fun particles(delta: Float, settings: GameSettings) {
        for (i in life.indices) {
            if (life[i] <= 0) continue
            life[i] -= delta
            px[i] += vx[i] * delta
            py[i] += vy[i] * delta
            if (!settings.lowEffects && !settings.reducedMotion && life[i] > 0) {
                canvas.circle(px[i], py[i], 2f, Palette.cyan, (life[i] * 1.5f).coerceAtMost(1f))
            }
        }
    }

    private fun lerp(before: Double, after: Double, alpha: Float) = (before + (after - before) * alpha).toFloat()
}
