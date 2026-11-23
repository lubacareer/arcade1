package io.github.lubacareer.spacepong.game

import com.badlogic.gdx.graphics.Color
import io.github.lubacareer.spacepong.bricks.BrickBuildings
import io.github.lubacareer.spacepong.bricks.BrickLevel
import io.github.lubacareer.spacepong.progression.CampaignSave
import kotlin.math.cos
import kotlin.math.sin

data class MenuButton(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val title: String,
    val enabled: Boolean = true,
    val primary: Boolean = false,
) {
    fun contains(px: Float, py: Float): Boolean = px >= x && px <= x + width && py >= y && py <= y + height
}

data class ResultSummary(
    val won: Boolean,
    val local: Boolean,
    val score: String,
    val earned: Int,
    val medals: Int,
    val encounter: Int,
)

data class BuildingResult(val building: Int, val won: Boolean, val score: Int)

class MenuRenderer(private val canvas: Canvas) {
    val buttons = mutableListOf<MenuButton>()
    private var focused = -1
    private val names = arrayOf("Flight School", "Asteroid Passage", "Gravity Relay", "The Warden")
    private val labels = arrayOf("LEARN THE SIGNAL", "THREAD THE DEBRIS", "BEND THE ORBIT", "BREAK THE SILENCE")

    fun render(
        page: Page,
        save: CampaignSave,
        focus: Int,
        result: ResultSummary?,
        notice: String?,
        readOnly: Boolean,
        time: Float,
        brickMatch: Boolean,
        buildingResult: BuildingResult?,
    ) {
        buttons.clear()
        focused = focus
        canvas.configureAccessibility(save.settings.largeText, save.settings.highContrast)
        when (page) {
            Page.START -> home()
            Page.CLASSIC -> classic(save, time)
            Page.BUILDINGS -> buildings(save, readOnly)
            Page.MAP -> map(save, readOnly)
            Page.UPGRADES -> upgrades(save, readOnly)
            Page.SETTINGS -> settings(save)
            Page.PAUSE -> pause(brickMatch)
            Page.RESULTS -> if (buildingResult != null) buildingResults(buildingResult, save) else results(result)
            Page.COMPLETE -> complete()
            Page.CONFIRM_NEW, Page.CONFIRM_BRICKS -> confirmNew(readOnly, page == Page.CONFIRM_BRICKS)
            Page.MATCH -> Unit
        }
        if (notice != null) canvas.wrap(notice, 64f, 35f, 1120f, 15f, Palette.amber)
    }

    private fun header(kicker: String, title: String, subtitle: String) {
        canvas.text(kicker, 64f, 662f, 16f, Palette.cyan, true)
        canvas.text(title, 64f, 622f, 48f, Palette.ink, true)
        canvas.text(subtitle, 66f, 548f, 21f, Palette.muted)
    }

    private fun button(
        id: String,
        title: String,
        x: Float,
        y: Float,
        width: Float = 320f,
        height: Float = 96f,
        enabled: Boolean = true,
        primary: Boolean = false,
    ) {
        val selected = focused == buttons.size
        val color = if (primary) Palette.cyan else Palette.panelLight
        canvas.rounded(
            x - 2,
            y - 2,
            width + 4,
            height + 4,
            14f,
            if (selected) Palette.amber else Palette.line,
            if (enabled) 1f else .45f,
        )
        canvas.rounded(x, y, width, height, 12f, color, if (enabled) 1f else .45f)
        canvas.centered(
            title,
            x + width / 2,
            y + height / 2 + canvas.scaledTextSize(14f),
            22f,
            if (primary) {
                Palette.background
            } else if (enabled) {
                Palette.ink
            } else {
                Palette.muted
            },
            true,
            maxWidth = width - 16f,
        )
        buttons += MenuButton(id, x, y, width, height, title, enabled, primary)
    }

    private fun home() {
        canvas.text("Space Pong", 76f, 584f, 80f, Palette.ink, true)
        canvas.wrap("Aim your returns and break through six orbital buildings.", 80f, 450f, 495f, 28f)
        button("buildings", "BRICK BUILDINGS", 80f, 250f, 470f, primary = true)
        button("classic", "CLASSIC PONG", 80f, 136f, 285f)
        button("settings", "SETTINGS", 379f, 136f, 171f)
        val preview = BrickBuildings.levels[2]
        skyline(preview, 914f, 450f, 458f, 165f)
        canvas.line(823f, 189f, 937f, 351f, 2f, Palette.cyan, .55f)
        canvas.circle(937f, 351f, 9f, Palette.ink)
        canvas.rounded(758f, 172f, 130f, 13f, 6f, Palette.cyan)
    }

    private fun skyline(level: BrickLevel, x: Float, y: Float, width: Float, height: Float, dim: Boolean = false) {
        val rows = level.rows
        val cell = minOf(width / rows.first().length, height / (rows.size * .56f))
        val stepY = cell * .56f
        val left = x - rows.first().length * cell / 2
        val top = y + rows.size * stepY / 2
        rows.forEachIndexed { row, cells ->
            cells.forEachIndexed { column, tile ->
                if (tile != '.') {
                    val color = when (tile) {
                        'W' -> Palette.pink
                        'S' -> Palette.amber
                        else -> Palette.cyan
                    }
                    canvas.rect(left + column * cell, top - (row + 1) * stepY, cell * .92f, stepY * .87f, color, if (dim) .35f else .9f)
                }
            }
        }
    }

    private fun buildings(save: CampaignSave, readOnly: Boolean) {
        header("BRICK BUILDINGS", "Choose a building", "Clear every brick to unlock the next building.")
        BrickBuildings.levels.forEachIndexed { index, level ->
            val x = 64f + (index % 3) * 390f
            val y = 324f - (index / 3) * 174f
            val unlocked = save.isBuildingUnlocked(index)
            val best = save.brickBestScores[index]
            val status = when {
                !unlocked -> "LOCKED"
                best > 0 -> "BEST  $best"
                else -> "PLAY"
            }
            button("building-$index", level.name, x, y, 366f, 100f, enabled = unlocked && !readOnly, primary = unlocked && best == 0)
            skyline(level, x + 56f, y + 126f, 96f, 39f, !unlocked)
            canvas.text(status, x + 124f, y + 140f, 18f, if (unlocked) Palette.amber else Palette.muted, true)
        }
        button("home", "MAIN MENU", 64f, 40f, 280f)
        if (save.brickBestScores.any { it > 0 }) button("new-bricks", "RESET BUILDINGS", 896f, 40f, 314f, enabled = !readOnly)
    }

    private fun buildingResults(result: BuildingResult, save: CampaignSave) {
        val name = BrickBuildings.levels[result.building].name
        canvas.centered(name, 640f, 619f, 29f, Palette.cyan, true)
        val last = result.building == BrickBuildings.levels.lastIndex
        val title = when {
            !result.won -> "Out of lives"
            last -> "All six buildings cleared!"
            else -> "Building cleared!"
        }
        canvas.centered(title, 640f, 529f, 56f, Palette.ink, true)
        canvas.centered("Score   ${result.score}", 640f, 427f, 36f, Palette.ink, true)
        val best = save.brickBestScores[result.building]
        if (best > 0) canvas.centered("Best clear   $best", 640f, 364f, 25f, Palette.amber)
        if (result.won && !last) {
            button("building-${result.building + 1}", "NEXT BUILDING", 654f, 207f, 330f, enabled = save.isBuildingUnlocked(result.building + 1), primary = true)
            button("retry", "PLAY AGAIN", 298f, 207f, 330f)
        } else {
            button("retry", "TRY AGAIN", 472f, 207f, 336f, primary = !result.won)
        }
        button("buildings", "BUILDING SELECT", 472f, 89f, 336f)
    }

    private fun classic(save: CampaignSave, time: Float) {
        canvas.text("CLASSIC PONG", 72f, 658f, 16f, Palette.cyan, true)
        canvas.text("SPACE", 66f, 588f, 90f, Palette.ink, true)
        canvas.text("PONG", 66f, 479f, 90f, Palette.cyan, true)
        canvas.text("F I R S T   C O N T A C T", 74f, 360f, 21f, Palette.amber)
        canvas.wrap("One paddle. Four encounters.\nA signal worth chasing.", 76f, 311f, 510f, 24f)
        button("continue", if (save.bestMedals.any { it > 0 }) "CONTINUE FLIGHT  >" else "ENTER THE SECTOR  >", 76f, 149f, 438f, 96f, primary = true)
        button("local", "LOCAL DUEL", 76f, 42f, 210f, 96f)
        button("home", "MAIN MENU", 304f, 42f, 210f, 96f)
        button("new", "NEW FLIGHT", 1036f, 604f, 180f, 96f)
        hero(938f, 366f, time, save.settings.reducedMotion)
        canvas.text("01 / THE ORION REACH", 750f, 143f, 18f, Palette.cyan, true)
        canvas.text("FREE FOREVER. ALL SKILL.", 750f, 107f, 17f, Palette.muted)
    }

    private fun hero(x: Float, y: Float, time: Float, reduced: Boolean) {
        canvas.circle(x, y, 218f, Palette.cyan, .025f)
        canvas.ring(x, y, 202f, Palette.line, .7f)
        canvas.ring(x, y, 158f, Palette.line, .5f)
        canvas.ring(x, y, 118f, Palette.cyan, .15f)
        for (i in 0 until 48) {
            val angle = i * Math.PI / 24
            val inner = if (i % 4 == 0) 208f else 213f
            canvas.line(
                x + cos(angle).toFloat() * inner,
                y + sin(angle).toFloat() * inner,
                x + cos(angle).toFloat() * 220f,
                y + sin(angle).toFloat() * 220f,
                1f,
                Palette.line,
            )
        }
        val moving = if (reduced) 0.8f else time * .5f
        val bx = x + cos(moving) * 115f
        val by = y + sin(moving * 1.4f) * 70f
        canvas.line(x - 140, y - 65, bx, by, 2f, Palette.cyan, .35f)
        canvas.rounded(x - 161, y - 80, 12f, 120f, 6f, Palette.cyan)
        canvas.rounded(x + 149, y - 20, 12f, 120f, 6f, Palette.pink)
        canvas.circle(bx, by, 36f, Palette.cyan, .08f)
        canvas.circle(bx, by, 22f, Palette.cyan, .13f)
        canvas.circle(bx, by, 10f, Palette.ink)
        canvas.text("YOU", x - 185, y - 110, 14f, Palette.cyan)
        canvas.text("UNKNOWN", x + 90, y + 137, 14f, Palette.pink)
    }

    private fun map(save: CampaignSave, readOnly: Boolean) {
        header("CHAPTER 01", "The Orion Reach", "Follow the signal. Every victory opens the next encounter.")
        canvas.text("${save.credits} CREDITS", 1030f, 650f, 22f, Palette.amber, true)
        for (i in 0..3) {
            val x = 64f + i * 292f
            val unlocked = save.isUnlocked(i)
            canvas.rounded(x, 188f, 272f, 340f, 18f, Palette.panel, if (unlocked) 1f else .5f)
            canvas.text("0${i + 1}", x + 22f, 478f, 18f, if (unlocked) Palette.cyan else Palette.muted, true)
            symbol(i, x + 136f, 425f, if (unlocked) Palette.cyan else Palette.line)
            canvas.centered(names[i], x + 136f, 362f, 23f, if (unlocked) Palette.ink else Palette.muted, true, maxWidth = 248f)
            canvas.centered(labels[i], x + 136f, 328f, 12f, Palette.muted)
            for (m in 0..2) {
                canvas.circle(
                    x + 112f + m * 24f,
                    302f,
                    6f,
                    if (save.bestMedals[i] > m) Palette.amber else Palette.line,
                )
            }
            button(
                "encounter-$i",
                if (unlocked) "LAUNCH" else "LOCKED",
                x + 16f,
                199f,
                240f,
                96f,
                enabled = unlocked && !readOnly,
                primary = unlocked && save.bestMedals[i] == 0,
            )
        }
        button("upgrades", "UPGRADE BAY", 64f, 73f, 300f)
        button("home", "MAIN MENU", 938f, 73f, 274f)
        canvas.text("Medals reward mastery. Replays are always free.", 398f, 120f, 18f, Palette.muted)
    }

    private fun symbol(index: Int, x: Float, y: Float, color: Color) {
        when (index) {
            0 -> {
                canvas.ring(x, y, 38f, color, .6f)
                canvas.line(x - 44f, y, x + 44f, y, 2f, color, .6f)
                canvas.line(x, y - 44f, x, y + 44f, 2f, color, .6f)
                canvas.circle(x, y, 8f, color)
            }
            1 -> {
                canvas.circle(x - 22, y + 15, 22f, color, .7f)
                canvas.circle(x + 25, y - 15, 17f, color, .45f)
                canvas.circle(x + 16, y + 29, 8f, color)
            }
            2 -> {
                canvas.ring(x, y, 41f, color, .35f)
                canvas.ring(x, y, 28f, color, .65f)
                canvas.ring(x, y, 14f, color)
                canvas.circle(x, y, 5f, color)
            }
            else -> {
                canvas.triangle(x - 37, y + 31, x + 37, y + 31, x, y - 42, color, .5f)
                canvas.line(x - 30, y + 25, x + 30, y + 25, 3f, color)
                canvas.line(x - 30, y + 25, x, y - 34, 3f, color)
                canvas.line(x + 30, y + 25, x, y - 34, 3f, color)
                canvas.circle(x, y + 3, 7f, Palette.background)
            }
        }
    }

    private fun upgrades(save: CampaignSave, readOnly: Boolean) {
        header("SHIP SYSTEMS", "The upgrade bay", "Earned in flight. Respec at any time. Local duels always use equal kits.")
        canvas.text("${save.credits} CREDITS AVAILABLE", 902f, 650f, 19f, Palette.amber, true)
        val ranks = intArrayOf(save.upgrades.height, save.upgrades.speed, save.upgrades.pulse)
        val titles = arrayOf("DEFLECTOR", "THRUSTERS", "CAPACITOR")
        val details = arrayOf("A wider paddle.\n+8% / +16% height.", "Reach the return faster.\n+8% / +16% speed.", "More chances to pulse.\n5.5s / 5s cooldown.")
        for (i in 0..2) {
            val x = 64f + i * 390f
            canvas.rounded(x, 204f, 366f, 300f, 18f, Palette.panel)
            canvas.text(titles[i], x + 26f, 465f, 26f, Palette.cyan, true)
            canvas.wrap(details[i], x + 26f, 410f, 314f, 23f)
            for (rank in 0..1) {
                canvas.rounded(
                    x + 28 + rank * 154,
                    332f,
                    140f,
                    8f,
                    4f,
                    if (ranks[i] > rank) Palette.amber else Palette.line,
                )
            }
            val cost = ranks[i] + 1
            val enabled = ranks[i] < 2 && save.credits >= cost && !readOnly
            button(
                "buy-$i",
                if (ranks[i] == 2) "FULLY UPGRADED" else "UPGRADE / $cost CREDIT${if (cost > 1) "S" else ""}",
                x + 20f,
                221f,
                326f,
                96f,
                enabled,
                enabled,
            )
        }
        button("respec", "RESPEC / FULL REFUND", 64f, 82f, 366f, enabled = !readOnly && ranks.any { it > 0 })
        button("map", "BACK TO SECTOR", 844f, 82f, 366f)
    }

    private fun settings(save: CampaignSave) {
        header("FLIGHT PREFERENCES", "Make it yours", "Tap volumes to cycle levels. Your preferences stay on this device.")
        val s = save.settings
        val entries = listOf(
            Triple("music", "MUSIC VOLUME", "${(s.musicVolume * 100).toInt()}%"),
            Triple("effects", "EFFECTS VOLUME", "${(s.effectsVolume * 100).toInt()}%"),
            Triple("muted", "MUTE ALL AUDIO", onOff(s.muted)),
            Triple("haptics", "HAPTICS", onOff(s.haptics)),
            Triple("reduced", "REDUCED MOTION", onOff(s.reducedMotion)),
            Triple("shake", "SCREEN SHAKE", onOff(s.screenShake)),
            Triple("contrast", "HIGH CONTRAST", onOff(s.highContrast)),
            Triple("text", "LARGER TEXT", onOff(s.largeText)),
            Triple("low", "LOW EFFECTS", onOff(s.lowEffects)),
            Triple("refresh", "HIGH REFRESH RATE", onOff(s.highRefresh)),
        )
        entries.forEachIndexed { index, (id, label, value) ->
            val x = 64f + (index % 2) * 584f
            val y = 420f - (index / 2) * 100f
            button("setting-$id", "$label   $value", x, y, 560f, 96f)
        }
        button("settings-back", "DONE", 1000f, 594f, 212f, 96f, primary = true)
    }

    private fun onOff(value: Boolean) = if (value) "ON" else "OFF"

    private fun pause(bricks: Boolean) {
        canvas.rect(0f, 0f, 1280f, 720f, Palette.background, .89f)
        canvas.centered("SIGNAL HELD", 640f, 582f, 17f, Palette.cyan, true)
        canvas.centered("Take a breath.", 640f, 530f, 54f, Palette.ink, true)
        canvas.centered("Your flight is paused. Resume when you are ready.", 640f, 450f, 23f, Palette.muted)
        button("resume", "RESUME FLIGHT", 430f, 310f, 420f, primary = true)
        button("retry", if (bricks) "RESTART BUILDING" else "RESTART MATCH", 430f, 198f, 420f)
        button("settings", "SETTINGS", 430f, 86f, 200f, 96f)
        button("map", "LEAVE MATCH", 650f, 86f, 200f, 96f)
    }

    private fun results(result: ResultSummary?) {
        if (result == null) return
        val title = if (result.local) {
            result.score
        } else if (result.won) {
            "Signal acquired."
        } else {
            "Flight interrupted."
        }
        canvas.centered(if (result.local) "LOCAL DUEL COMPLETE" else names[result.encounter].uppercase(), 640f, 623f, 18f, Palette.cyan, true)
        canvas.centered(title, 640f, 564f, 58f, Palette.ink, true)
        if (!result.local) {
            canvas.centered(result.score, 640f, 481f, 30f, Palette.muted)
            for (i in 0..2) {
                canvas.ring(560f + i * 80, 376f, 26f, if (result.medals > i) Palette.amber else Palette.line, 1f, 2f)
                if (result.medals > i) canvas.circle(560f + i * 80, 376f, 13f, Palette.amber)
            }
            canvas.centered(
                if (result.earned > 0) {
                    "+${result.earned} CREDITS EARNED"
                } else if (result.won) {
                    "ENCOUNTER COMPLETE"
                } else {
                    "No credits lost. Every retry is free."
                },
                640f,
                314f,
                23f,
                if (result.earned > 0) Palette.amber else Palette.muted,
            )
        } else {
            canvas.centered("Equal kits. One more round?", 640f, 420f, 26f, Palette.muted)
        }
        button("retry", if (result.local) "REMATCH" else "RETRY", 288f, 172f, 320f)
        button(
            "result-next",
            if (result.local) {
                "MAIN MENU"
            } else if (result.won && result.encounter == 3) {
                "CHAPTER COMPLETE"
            } else if (result.won) {
                "NEXT ENCOUNTER"
            } else {
                "SECTOR MAP"
            },
            640f,
            172f,
            352f,
            primary = true,
        )
        if (!result.local) button("map", "SECTOR MAP", 480f, 70f, 320f, 96f)
    }

    private fun complete() {
        canvas.centered("CHAPTER 01 / COMPLETE", 640f, 630f, 20f, Palette.cyan, true)
        symbol(3, 640f, 510f, Palette.amber)
        canvas.centered("The silence breaks.", 640f, 410f, 60f, Palette.ink, true)
        canvas.centered("You found the signal beyond the Orion Reach.", 640f, 324f, 26f, Palette.muted)
        canvas.centered("Return to master every encounter. Every flight remains free.", 640f, 276f, 23f, Palette.muted)
        button("map", "RETURN TO THE SECTOR", 430f, 144f, 420f, primary = true)
    }

    private fun confirmNew(readOnly: Boolean, bricks: Boolean) {
        if (bricks) {
            header("BRICK BUILDINGS", "Reset building progress?", "This clears all six building scores and unlocks.")
            button("confirm-new", "RESET BUILDINGS", 64f, 218f, 360f, enabled = !readOnly)
            button("home", "KEEP MY PROGRESS", 454f, 218f, 360f, primary = true)
            return
        }
        header("NEW CAMPAIGN", "Start a new flight?", "This replaces medals and upgrades on this device. Your settings stay.")
        canvas.wrap(
            if (readOnly) {
                "This save was created by a newer game version. Update the game before changing progress."
            } else {
                "All earned credits and encounter unlocks will be reset. Local versus is always available."
            },
            64f,
            430f,
            800f,
            28f,
        )
        button("confirm-new", "RESET CAMPAIGN", 64f, 218f, 360f, enabled = !readOnly, primary = true)
        button("home", "KEEP MY PROGRESS", 454f, 218f, 360f)
    }
}
