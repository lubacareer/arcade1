package io.github.lubacareer.spacepong.game

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Music
import com.badlogic.gdx.audio.Sound
import io.github.lubacareer.spacepong.progression.GameSettings

enum class SoundEffect(val assetPath: String) {
    HIT("audio/hit.wav"),
    PULSE("audio/pulse.wav"),
    GOAL("audio/goal.wav"),
    VICTORY("audio/victory.wav"),
}

/** All audio is bundled mathematical synthesis; failure to open audio is nonfatal. */
class GameAudio(private val hasAudioFocus: () -> Boolean) {
    private val sounds = mutableMapOf<SoundEffect, Sound>()
    private var music: Music? = null
    private var suspended = false
    private var lastHitAt = 0L

    init {
        for (effect in SoundEffect.entries) {
            runCatching { Gdx.audio.newSound(Gdx.files.internal(effect.assetPath)) }
                .onSuccess { sounds[effect] = it }
                .onFailure { Gdx.app.error("Audio", "Unable to load bundled ${effect.assetPath}", it) }
        }
        runCatching { Gdx.audio.newMusic(Gdx.files.internal("audio/ambient.wav")) }
            .onSuccess { music = it.also { track -> track.isLooping = true } }
            .onFailure { Gdx.app.error("Audio", "Unable to initialize ambient audio", it) }
    }

    fun apply(settings: GameSettings) {
        if (settings.muted || settings.effectsVolume == 0f) sounds.values.forEach { it.stop() }
        val track = music ?: return
        track.volume = if (settings.muted) 0f else settings.musicVolume
        if (suspended || !hasAudioFocus() || settings.muted || settings.musicVolume == 0f) {
            track.pause()
        } else if (!track.isPlaying) {
            track.play()
        }
    }

    fun effect(effect: SoundEffect, settings: GameSettings) {
        if (suspended || !hasAudioFocus() || settings.muted || settings.effectsVolume == 0f) return
        if (effect == SoundEffect.HIT) {
            val now = System.nanoTime()
            if (now - lastHitAt < 35_000_000L) return
            lastHitAt = now
        }
        sounds[effect]?.play(settings.effectsVolume * if (effect == SoundEffect.HIT) .6f else 1f)
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
