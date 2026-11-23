package io.github.lubacareer.spacepong.game

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.viewport.FitViewport
import io.github.lubacareer.spacepong.bricks.BrickBuildings
import io.github.lubacareer.spacepong.input.TouchRect
import io.github.lubacareer.spacepong.input.TouchRouter
import io.github.lubacareer.spacepong.progression.*
import io.github.lubacareer.spacepong.simulation.*
import java.io.IOException

class SpacePongGame(private val platform: PlatformServices) : ApplicationAdapter() {
    private val camera = OrthographicCamera()
    private val viewport = FitViewport(1280f, 720f, camera)
    private val point = Vector2()
    private val impactProjection = Matrix4()
    private val flow = UiFlow()
    private val heldKeys = BooleanArray(256)
    private val pressedButtons = mutableMapOf<Int, Pair<Page, MenuButton>>()
    private lateinit var canvas: Canvas
    private lateinit var menus: MenuRenderer
    private lateinit var arena: ArenaRenderer
    private lateinit var audio: GameAudio
    private lateinit var repository: SaveRepository
    private var save = CampaignSave()
    private var readOnly = false
    private var notice: String? = null
    private lateinit var commits: PendingCampaignCommit
    private lateinit var settingsCommit: CampaignSettingsCommit
    private var pendingReset: CampaignSave? = null
    private val pendingSave: CampaignSave? get() = commits.pending
    private var engine: MatchEngine? = null
    private var runner: FixedStepRunner? = null
    private var router = TouchRouter(false)
    private var building: BrickPlaySession? = null
    private var selectedBuilding = 0
    private var buildingResult: BuildingResult? = null
    private var local = false
    private var selectedEncounter = 0
    private var result: ResultSummary? = null
    private var resultHandled = false
    private var drill: TutorialDrill? = null
    private val tutorial: Int get() = drill?.stage ?: 0
    private var leftPulse = false
    private var rightPulse = false
    private var elapsed = 0f
    private var initialized = false
    private val skipTutorial = MenuButton("tutorial-skip", 40f, 24f, 180f, 96f, "SKIP")
    private val retrySave = MenuButton("save-retry", 920f, 560f, 290f, 96f, "RETRY SAVE")
    private var currentButtons: List<MenuButton> = emptyList()
    private var renderedButtonsPage: Page? = null

    override fun create() {
        canvas = Canvas()
        menus = MenuRenderer(canvas)
        arena = ArenaRenderer(canvas)
        audio = GameAudio { platform.hasAudioFocus() }
        repository = SaveRepository(platform.saveDirectory.toPath())
        commits = PendingCampaignCommit { repository.save(it) }
        settingsCommit = CampaignSettingsCommit({ next -> commits.attempt(save, next) }, audio::apply, platform::setHighRefresh)
        try {
            val loaded = repository.load()
            save = loaded.save
            notice = loaded.notice
            readOnly = loaded.readOnly
        } catch (error: IOException) {
            readOnly = true
            notice = "Storage is unavailable. Local duels still work; campaign saves are protected."
            Gdx.app.error("Save", "Unable to read campaign", error)
        }
        Gdx.input.inputProcessor = Controls()
        Gdx.input.setCatchKey(Input.Keys.BACK, true)
        platform.setHighRefresh(save.settings.highRefresh)
        audio.apply(save.settings)
        initialized = true
        resize(Gdx.graphics.width, Gdx.graphics.height)
    }

    override fun resize(width: Int, height: Int) {
        if (!initialized) return
        val insets = platform.safeInsets()
        val safeWidth = (width - insets.left - insets.right).coerceAtLeast(1)
        val safeHeight = (height - insets.top - insets.bottom).coerceAtLeast(1)
        viewport.update(safeWidth, safeHeight, true)
        viewport.setScreenPosition(viewport.screenX + insets.left, viewport.screenY + insets.bottom)
        viewport.apply()
        canvas.projection(camera.combined)
    }

    override fun render() {
        if (!initialized) return
        val delta = Gdx.graphics.deltaTime.coerceIn(0f, .1f)
        elapsed += delta
        updateMatch(delta)
        Gdx.gl.glClearColor(.023f, .051f, .106f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        viewport.apply()
        canvas.projection(camera.combined)
        canvas.configureAccessibility(save.settings.largeText, save.settings.highContrast)
        canvas.background(elapsed, save.settings.reducedMotion, save.settings.lowEffects)
        if (flow.page == Page.MATCH || flow.page == Page.PAUSE) building?.draw(canvas, save.settings)
        val currentEngine = engine
        if ((flow.page == Page.MATCH || flow.page == Page.PAUSE) && currentEngine != null) {
            if (save.settings.screenShake && !save.settings.reducedMotion) {
                canvas.projection(impactProjection.set(camera.combined).translate(arena.shakeOffsetX(), arena.shakeOffsetY(), 0f))
            }
            arena.draw(
                currentEngine.state,
                runner?.alpha?.toFloat() ?: 1f,
                if (flow.page == Page.MATCH) delta else 0f,
                save.settings,
                local,
                if (flow.page == Page.MATCH) tutorial else 0,
                drill?.ghostY ?: 360f,
                drill?.pulseDemoProgress ?: -1f,
            )
            canvas.projection(camera.combined)
        }
        if (flow.page == Page.MATCH) {
            currentButtons = building?.let { listOf(it.pauseButton) } ?: matchButtons()
            if (tutorial > 0) {
                canvas.rounded(skipTutorial.x, skipTutorial.y, skipTutorial.width, skipTutorial.height, 12f, Palette.panel, .9f)
                canvas.centered("SKIP", skipTutorial.x + skipTutorial.width / 2, skipTutorial.y + 59, 22f, Palette.muted, true)
            }
        } else {
            menus.render(flow.page, save, flow.focused, result, notice, readOnly, elapsed, building != null, buildingResult)
            currentButtons = menus.buttons
            if (commits.canRetry) {
                canvas.rounded(retrySave.x, retrySave.y, retrySave.width, retrySave.height, 12f, Palette.amber)
                canvas.centered("RETRY SAVE", retrySave.x + retrySave.width / 2, retrySave.y + 46, 21f, Palette.background, true)
                currentButtons = menus.buttons + retrySave
            }
        }
        renderedButtonsPage = flow.page
        canvas.finish()
    }

    private fun activeButtons(): List<MenuButton> = buttonsForPage(renderedButtonsPage, flow.page, currentButtons)

    private fun matchButtons(): List<MenuButton> = if (tutorial > 0) listOf(arena.pauseButton, skipTutorial) else listOf(arena.pauseButton)

    private fun updateMatch(delta: Float) {
        if (flow.page != Page.MATCH) return
        building?.let { session ->
            session.update(delta)
            session.won?.let { won ->
                buildingResult = BuildingResult(selectedBuilding, won, session.score)
                if (won) persist(CampaignProgression.recordBrickVictory(save, selectedBuilding, session.score))
                clearInput()
                flow.show(Page.RESULTS)
            }
            return
        }
        val active = engine ?: return
        syncPulseAvailability()
        val touchLeftPulse = router.consumeLeftPulse()
        val touchRightPulse = router.consumeRightPulse()
        val lp = pulseAvailable(Side.LEFT) && (leftPulse || touchLeftPulse)
        val rp = pulseAvailable(Side.RIGHT) && (rightPulse || touchRightPulse)
        leftPulse = false
        rightPulse = false
        val leftAxis = axis(Input.Keys.W, Input.Keys.S)
        val rightAxis = axis(Input.Keys.UP, Input.Keys.DOWN)
        if (tutorial > 0) {
            if (drill?.update(delta, router.leftTargetY, leftAxis.toFloat(), lp) == true) finishTutorial()
            return
        }
        runner?.advance(
            delta.toDouble(),
            InputFrame(
                leftTargetY = router.leftTargetY?.toDouble(),
                rightTargetY = if (local) router.rightTargetY?.toDouble() else null,
                leftAxis = leftAxis,
                rightAxis = if (local) rightAxis else 0.0,
                leftPulse = lp,
                rightPulse = local && rp,
            ),
        )
        if (active.state.phase == MatchPhase.FINISHED && !resultHandled) finishMatch()
    }

    private fun pulseAvailable(side: Side): Boolean {
        val state = engine?.state ?: return false
        if (flow.page != Page.MATCH || side == Side.RIGHT && !local) return false
        val paddle = if (side == Side.LEFT) state.left else state.right
        return PulseControlUi.create(
            state.phase,
            if (side == Side.LEFT) tutorial else 0,
            paddle.pulseArmedSeconds,
            paddle.pulseCooldownSeconds,
        ).enabled
    }

    private fun syncPulseAvailability() {
        val leftEnabled = pulseAvailable(Side.LEFT)
        val rightEnabled = pulseAvailable(Side.RIGHT)
        router.setPulseEnabled(leftEnabled, rightEnabled)
        if (!leftEnabled) leftPulse = false
        if (!rightEnabled) rightPulse = false
    }

    private fun axis(positive: Int, negative: Int) = (if (heldKeys[positive]) 1.0 else 0.0) - (if (heldKeys[negative]) 1.0 else 0.0)

    private fun startBuilding(index: Int) {
        if (pendingSave != null || readOnly || !save.isBuildingUnlocked(index)) {
            notice = commits.error ?: notice ?: "This building is unavailable."
            openMenu(Page.BUILDINGS)
            return
        }
        abandonMatch()
        local = false
        selectedBuilding = index
        building = BrickPlaySession(index) { sound, strong ->
            audio.effect(sound, save.settings)
            if (strong != null && save.settings.haptics) platform.haptic(strong)
        }
        notice = null
        flow.show(Page.MATCH)
        audio.activate(save.settings)
    }

    private fun openMenu(page: Page) {
        abandonMatch()
        flow.show(page)
        audio.activate(save.settings)
    }

    private fun startMatch(encounter: Int, localVersus: Boolean = false) {
        if (!localVersus && pendingSave != null) {
            notice = commits.error ?: "Save your pending progress before starting another encounter."
            flow.show(Page.MAP)
            return
        }
        if (!localVersus && (!save.isUnlocked(encounter) || readOnly)) {
            notice = if (readOnly) "Campaign changes are unavailable for this save." else "Complete the previous encounter first."
            flow.show(Page.MAP)
            return
        }
        abandonMatch()
        local = localVersus
        selectedEncounter = encounter
        result = null
        resultHandled = false
        val ranks = if (local) UpgradeRanks() else save.upgrades
        engine = MatchEngine(
            MatchConfig(
                encounter = Encounter.entries[encounter],
                mode = if (local) MatchMode.LOCAL_VERSUS else MatchMode.CAMPAIGN,
                seed = 7147L + encounter,
                loadout = PlayerLoadout(ranks.height, ranks.speed, ranks.pulse),
            ),
        )
        engine!!.eventListener = { event -> onMatchEvent(event) }
        runner = FixedStepRunner(engine!!)
        router = TouchRouter(local)
        val left = if (local) arena.versusLeftPulse else arena.soloPulse
        val right = if (local) arena.versusRightPulse else null
        router.pulseAreas(
            TouchRect(left.x, left.y, left.width, left.height),
            right?.let { TouchRect(it.x, it.y, it.width, it.height) },
        )
        arena.reset()
        drill = if (!local && encounter == 0 && !save.tutorialSeen) {
            TutorialDrill(engine!!.state.left.height.toFloat(), engine!!.state.left.speed.toFloat())
        } else {
            null
        }
        if (tutorial > 0) engine!!.pause()
        if (pendingSave == null) notice = null
        flow.show(Page.MATCH)
        audio.activate(save.settings)
    }

    private fun finishTutorial() {
        drill = null
        val saved = persist(save.copy(tutorialSeen = true))
        audio.effect("pulse", save.settings)
        clearInput()
        if (saved) engine?.resume() else flow.show(Page.PAUSE)
        runner?.clear()
    }

    private fun onMatchEvent(event: MatchEvent) {
        arena.event(event, save.settings)
        when (event.kind) {
            MatchEventKind.PADDLE_HIT, MatchEventKind.BUMPER_HIT, MatchEventKind.WALL_HIT -> audio.effect("hit", save.settings)
            MatchEventKind.PULSE_ARMED -> audio.effect("pulse", save.settings)
            MatchEventKind.GOAL -> {
                audio.effect("goal", save.settings)
                if (save.settings.haptics) platform.haptic(true)
            }
            MatchEventKind.MATCH_FINISHED -> audio.effect("victory", save.settings)
        }
        if (event.kind == MatchEventKind.PADDLE_HIT && event.side == Side.LEFT && save.settings.haptics) platform.haptic(false)
    }

    private fun finishMatch() {
        val state = engine!!.state
        resultHandled = true
        val won = state.winner == Side.LEFT
        var earned = 0
        var medals = 0
        if (!local && won) {
            val next = CampaignProgression.recordVictory(save, selectedEncounter, if (state.isBoss) 3 - state.leftHull else state.rightScore)
            earned = next.credits - save.credits
            medals = next.bestMedals[selectedEncounter]
            if (!persist(next)) earned = 0
        }
        val score = when {
            local -> if (won) "Player one wins." else "Player two wins."
            state.isBoss -> "YOUR HULL  ${state.leftHull}   /   WARDEN  ${state.rightHull}"
            else -> "${state.leftScore}  :  ${state.rightScore}"
        }
        result = ResultSummary(won, local, score, earned, medals, selectedEncounter)
        clearInput()
        flow.show(Page.RESULTS)
    }

    private fun persist(next: CampaignSave): Boolean {
        if (readOnly) {
            notice = notice ?: "This save cannot be changed. Your existing progress has been preserved."
            return false
        }
        if (settingsCommit.attempt(save, next)) {
            result = result?.withCommittedRewards(save, next)
            save = next
            if (pendingReset == next) pendingReset = null
            notice = null
            return true
        }
        if (commits.requiresReload) readOnly = true
        notice = commits.error
        return false
    }

    private fun action(id: String) {
        when {
            id.startsWith("building-") -> startBuilding(id.substringAfter('-').toInt())
            id.startsWith("encounter-") -> startMatch(id.substringAfter('-').toInt())
            id.startsWith("buy-") -> {
                val track = UpgradeTrack.entries[id.substringAfter('-').toInt()]
                UpgradePurchase.candidate(save, track, readOnly, pendingSave != null)?.let { persist(it) }
            }
            id.startsWith("setting-") -> changeSetting(id.removePrefix("setting-"))
            else -> when (id) {
                "buildings" -> openMenu(Page.BUILDINGS)
                "classic" -> openMenu(Page.CLASSIC)
                "continue" -> openMenu(Page.MAP)
                "map" -> openMenu(
                    when {
                        building != null -> Page.BUILDINGS
                        local -> Page.CLASSIC
                        else -> Page.MAP
                    },
                )
                "home" -> {
                    val previous = flow.page
                    if (previous == Page.CONFIRM_NEW || previous == Page.CONFIRM_BRICKS) {
                        if (!flow.cancelReset(commits, pendingReset)) {
                            notice = commits.error
                            return
                        }
                        pendingReset = null
                        notice = commits.error
                    }
                    openMenu(
                        when (previous) {
                            Page.CONFIRM_BRICKS -> Page.BUILDINGS
                            Page.CONFIRM_NEW -> Page.CLASSIC
                            else -> Page.START
                        },
                    )
                }
                "local" -> startMatch(0, true)
                "new-bricks" -> flow.show(Page.CONFIRM_BRICKS)
                "new" -> flow.show(Page.CONFIRM_NEW)
                "confirm-new" -> {
                    val brickReset = flow.page == Page.CONFIRM_BRICKS
                    val reset = if (brickReset) CampaignProgression.resetBricks(save) else CampaignProgression.resetClassic(save)
                    if (persist(reset)) {
                        openMenu(if (brickReset) Page.BUILDINGS else Page.MAP)
                    } else if (commits.pending == reset) {
                        pendingReset = reset
                    }
                }
                "upgrades" -> flow.show(Page.UPGRADES)
                "respec" -> persist(CampaignProgression.respec(save))
                "settings" -> flow.openSettings()
                "settings-back" -> {
                    flow.closeSettings()
                    if (flow.page != Page.PAUSE) audio.activate(save.settings)
                }
                "pause" -> pauseForInterruption()
                "resume" -> {
                    if (!local && pendingSave != null) {
                        notice = commits.error
                        return
                    }
                    clearInput()
                    building?.resume()
                    if (tutorial == 0) engine?.resume()
                    runner?.clear()
                    flow.show(Page.MATCH)
                    audio.activate(save.settings)
                }
                "retry" -> if (building != null) startBuilding(selectedBuilding) else startMatch(selectedEncounter, local)
                "tutorial-skip" -> finishTutorial()
                "result-next" -> {
                    val current = result ?: return
                    when {
                        current.local -> {
                            abandonMatch()
                            flow.show(Page.START)
                        }
                        current.won && current.encounter == 3 -> flow.show(Page.COMPLETE)
                        current.won -> startMatch(current.encounter + 1)
                        else -> {
                            abandonMatch()
                            flow.show(Page.MAP)
                        }
                    }
                }
                "save-retry" -> if (commits.canRetry) {
                    pendingSave?.let {
                        val isReset = pendingReset == it
                        if (persist(it) && isReset) openMenu(if (flow.page == Page.CONFIRM_BRICKS) Page.BUILDINGS else Page.MAP)
                    }
                }
            }
        }
    }

    private fun changeSetting(key: String) {
        val s = save.settings
        val next = when (key) {
            "music" -> s.copy(musicVolume = cycleVolume(s.musicVolume))
            "effects" -> s.copy(effectsVolume = cycleVolume(s.effectsVolume))
            "muted" -> s.copy(muted = !s.muted)
            "haptics" -> s.copy(haptics = !s.haptics)
            "reduced" -> s.copy(reducedMotion = !s.reducedMotion)
            "shake" -> s.copy(screenShake = !s.screenShake)
            "contrast" -> s.copy(highContrast = !s.highContrast)
            "text" -> s.copy(largeText = !s.largeText)
            "low" -> s.copy(lowEffects = !s.lowEffects)
            "refresh" -> s.copy(highRefresh = !s.highRefresh)
            else -> s
        }
        persist(save.copy(settings = next))
    }

    private fun cycleVolume(value: Float) = ((kotlin.math.round(value * 5).toInt() + 1) % 6) / 5f

    private fun abandonMatch() {
        building?.pause()
        building = null
        buildingResult = null
        result = null
        local = false
        engine?.pause()
        engine = null
        runner = null
        drill = null
        clearInput()
    }

    /** Call on the render thread (Android adapters post here after OS interruption callbacks). */
    fun pauseForInterruption() {
        if (!initialized) return
        building?.pause()
        engine?.pause()
        runner?.clear()
        flow.interrupt()
        clearInput()
        audio.suspend()
    }

    /** Focus gain restores audio for an already active page; it never resumes match simulation. */
    fun audioFocusChanged(granted: Boolean) {
        if (!initialized) return
        if (!granted) {
            pauseForInterruption()
        } else if (audioMayActivateOnFocus(flow.page)) {
            audio.activate(save.settings)
        }
    }

    override fun pause() = pauseForInterruption()

    override fun resume() {
        if (!initialized) return
        clearInput()
        if (flow.page != Page.PAUSE && flow.page != Page.MATCH) audio.activate(save.settings)
    }

    private fun clearInput() {
        heldKeys.fill(false)
        building?.clearInput()
        router.cancelAll()
        pressedButtons.clear()
        leftPulse = false
        rightPulse = false
    }

    fun diagnostics(): GameDiagnostics {
        val state = engine?.state
        return GameDiagnostics(
            flow.page.name, building?.phaseName ?: state?.phase?.name, building?.let { BrickBuildings.levels[selectedBuilding].name } ?: state?.config?.encounter?.name,
            state?.leftScore ?: 0, state?.rightScore ?: 0, state?.leftHull ?: 0, state?.rightHull ?: 0,
            state?.left?.y ?: 0.0, state?.right?.y ?: 0.0, state?.left?.pulseCooldownSeconds ?: 0.0, state?.right?.pulseCooldownSeconds ?: 0.0,
            tutorial, (drill?.ghostY ?: 360f).toDouble(),
            building?.lives ?: 0, building?.score ?: 0, building?.paddleX ?: 0.0,
        )
    }

    fun uiTargets(): List<UiTarget> {
        if (renderedButtonsPage != flow.page) return emptyList()
        val targets = if (flow.page == Page.MATCH && building == null) {
            currentButtons + if (local) {
                listOf(arena.versusLeftPulse.copy(enabled = pulseAvailable(Side.LEFT)), arena.versusRightPulse.copy(enabled = pulseAvailable(Side.RIGHT)))
            } else {
                listOf(arena.soloPulse.copy(enabled = pulseAvailable(Side.LEFT)))
            }
        } else {
            currentButtons
        }
        return targets.map { button ->
            val bottomLeft = viewport.project(Vector2(button.x, button.y))
            val topRight = viewport.project(Vector2(button.x + button.width, button.y + button.height))
            UiTarget(
                button.id,
                bottomLeft.x,
                Gdx.graphics.height - topRight.y,
                topRight.x - bottomLeft.x,
                topRight.y - bottomLeft.y,
                button.enabled,
            )
        }
    }

    override fun dispose() {
        if (!initialized) return
        audio.dispose()
        canvas.dispose()
        initialized = false
    }

    private inner class Controls : InputAdapter() {
        private fun coordinates(x: Int, y: Int): Vector2 = viewport.unproject(point.set(x.toFloat(), y.toFloat()))

        override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            if (renderedButtonsPage != flow.page) return false
            val p = coordinates(screenX, screenY)
            if (p.x !in 0f..1280f || p.y !in 0f..720f) return false
            val hit = activeButtons().lastOrNull { it.enabled && it.contains(p.x, p.y) }
            if (hit != null) {
                pressedButtons[pointer] = flow.page to hit
                return true
            }
            if (flow.page == Page.MATCH) {
                building?.let { return it.down(pointer, p.x, p.y) }
                val state = engine?.state ?: return false
                syncPulseAvailability()
                return router.down(pointer, p.x, p.y, leftDragOrigin(drill, state.left.y.toFloat()), state.right.y.toFloat())
            }
            return false
        }

        override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
            if (pressedButtons.containsKey(pointer)) return true
            val p = coordinates(screenX, screenY)
            return flow.page == Page.MATCH && (building?.drag(pointer, p.x, p.y) ?: router.move(pointer, p.x, p.y))
        }

        override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            val p = coordinates(screenX, screenY)
            building?.up(pointer, p.x, p.y)
            router.up(pointer)
            val captured = pressedButtons.remove(pointer) ?: return true
            if (captured.first == flow.page && captured.second.contains(p.x, p.y)) action(captured.second.id)
            return true
        }

        override fun touchCancelled(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            pressedButtons.remove(pointer)
            building?.cancel(pointer)
            router.cancel(pointer)
            return true
        }

        override fun keyDown(keycode: Int): Boolean {
            if (keycode in heldKeys.indices) heldKeys[keycode] = true
            if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.BACK) {
                when (flow.page) {
                    Page.MATCH -> pauseForInterruption()
                    Page.SETTINGS -> action("settings-back")
                    Page.PAUSE -> action("resume")
                    Page.UPGRADES, Page.RESULTS, Page.COMPLETE -> action("map")
                    Page.MAP -> action("classic")
                    else -> action("home")
                }
                return true
            }
            if (flow.page == Page.MATCH) {
                building?.let { return it.keyDown(keycode) }
                if (keycode == Input.Keys.SHIFT_LEFT && pulseAvailable(Side.LEFT)) leftPulse = true
                if (keycode == Input.Keys.SHIFT_RIGHT && pulseAvailable(Side.RIGHT)) rightPulse = true
            } else {
                val buttons = activeButtons()
                when (keycode) {
                    Input.Keys.TAB, Input.Keys.DOWN, Input.Keys.RIGHT -> flow.nextFocus(buttons.map { it.enabled }, 1)
                    Input.Keys.UP, Input.Keys.LEFT -> flow.nextFocus(buttons.map { it.enabled }, -1)
                    Input.Keys.ENTER, Input.Keys.SPACE -> buttons.getOrNull(flow.focused)?.takeIf { it.enabled }?.let { action(it.id) }
                }
            }
            return true
        }

        override fun keyUp(keycode: Int): Boolean {
            building?.keyUp(keycode)
            if (keycode in heldKeys.indices) heldKeys[keycode] = false
            return true
        }
    }
}

internal fun ResultSummary.withCommittedRewards(previous: CampaignSave, next: CampaignSave): ResultSummary {
    if (!won || local) return this
    val medalGain = next.bestMedals[encounter] - previous.bestMedals[encounter]
    return if (medalGain > 0) copy(earned = medalGain, medals = next.bestMedals[encounter]) else this
}

internal fun audioMayActivateOnFocus(page: Page): Boolean = page != Page.PAUSE && page != Page.SETTINGS

internal fun UiFlow.cancelReset(commits: PendingCampaignCommit, pendingReset: CampaignSave?): Boolean {
    if (pendingReset != null && !commits.cancelPending(pendingReset)) return false
    show(Page.START)
    return true
}

internal fun leftDragOrigin(drill: TutorialDrill?, paddleY: Float): Float = drill?.ghostY ?: paddleY
