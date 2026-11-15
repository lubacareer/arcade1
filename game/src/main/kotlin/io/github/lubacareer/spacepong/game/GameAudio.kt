package io.github.lubacareer.spacepong.game

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Music
import com.badlogic.gdx.audio.Sound
import io.github.lubacareer.spacepong.progression.GameSettings

/** All audio is bundled mathematical synthesis; failure to open audio is nonfatal. */
class GameAudio(private val hasAudioFocus: () -> Boolean) {
    private val sounds = mutableMapOf<String, Sound>()
    private var music: Music? = null
    private var suspended = false
    private var lastHitAt = 0L

    init {
        for (name in listOf("hit", "pulse", "goal", "victory")) {
            runCatching { Gdx.audio.newSound(Gdx.files.internal("audio/$name.wav")) }
                .onSuccess { sounds[name] = it }
                .onFailure { Gdx.app.error("Audio", "Unable to load bundled $name effect", it) }
        }
        runCatching { Gdx.audio.newMusic(Gdx.files.internal("audio/ambient.wav")) }
            .onSuccess { music = it.also { track -> track.isLooping = true } }
            .onFailure { Gdx.app.error("Audio", "Unable to initialize ambient audio", it) }
    }

    fun apply(settings: GameSettings) {
        val track = music ?: return
        track.volume = if (settings.muted) 0f else settings.musicVolume
        if (suspended || !hasAudioFocus() || settings.muted || settings.musicVolume == 0f) {
            track.pause()
        } else if (!track.isPlaying) {
            track.play()
        }
    }

    fun effect(name: String, settings: GameSettings) {
        if (suspended || !hasAudioFocus() || settings.muted || settings.effectsVolume == 0f) return
        if (name == "hit") {
            val now = System.nanoTime()
            if (now - lastHitAt < 35_000_000L) return
            lastHitAt = now
        }
        sounds[name]?.play(settings.effectsVolume * if (name == "hit") .6f else 1f)
    }

    fun suspend() {
        suspended = true
        music?.pause()
        sounds.values.forEach { it.stop() }
    }

    fun activate(settings: GameSettings) {
        suspended = false
        apply(settings)
    }

    fun dispose() {
        music?.dispose()
        sounds.values.forEach { it.dispose() }
    }
}
