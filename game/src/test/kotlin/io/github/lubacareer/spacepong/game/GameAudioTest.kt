package io.github.lubacareer.spacepong.game

import com.badlogic.gdx.Application
import com.badlogic.gdx.Audio
import com.badlogic.gdx.Files
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Music
import com.badlogic.gdx.audio.Sound
import com.badlogic.gdx.files.FileHandle
import io.github.lubacareer.spacepong.progression.GameSettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class GameAudioTest {
    @Test fun `muting stops effects already playing`() = withAudio(musicAvailable = true) { audio, voices ->
        audio.effect(SoundEffect.VICTORY, GameSettings())
        assertTrue(voices.any { it.playing })
        audio.apply(GameSettings(muted = true))
        assertTrue(voices.none { it.playing })
    }

    @Test fun `zero effects volume stops active effects even when ambient music failed to load`() = withAudio(musicAvailable = false) { audio, voices ->
        audio.effect(SoundEffect.GOAL, GameSettings())
        assertTrue(voices.any { it.playing })
        audio.apply(GameSettings(effectsVolume = 0f))
        assertTrue(voices.none { it.playing })
    }

    @Test fun `every sound effect plays its matching bundled WAV`() = withAudio(musicAvailable = true) { audio, voices ->
        assertEquals(setOf("audio/hit.wav", "audio/pulse.wav", "audio/goal.wav", "audio/victory.wav"), voices.map { it.path }.toSet())
        for (effect in SoundEffect.entries) {
            assertNotNull(javaClass.getResource("/${effect.assetPath}"))
            audio.effect(effect, GameSettings())
            assertEquals(listOf(effect.assetPath), voices.filter { it.playing }.map { it.path })
            audio.apply(GameSettings(muted = true))
        }
    }

    private class Voice(val path: String, var playing: Boolean = false)

    private fun withAudio(musicAvailable: Boolean, check: (GameAudio, List<Voice>) -> Unit) {
        val previousAudio = Gdx.audio
        val previousFiles = Gdx.files
        val previousApp = Gdx.app
        val voices = mutableListOf<Voice>()
        var gameAudio: GameAudio? = null
        try {
            Gdx.files = fake(Files::class.java) { _, args -> FileHandle(args.single() as String) }
            Gdx.app = fake(Application::class.java) { _, _ -> null }
            Gdx.audio = fake(Audio::class.java) { method, args ->
                when (method) {
                    "newSound" -> {
                        val voice = Voice((args.single() as FileHandle).path()).also(voices::add)
                        fake(Sound::class.java) { soundMethod, _ ->
                            when (soundMethod) {
                                "play" -> {
                                    voice.playing = true
                                    1L
                                }
                                "stop" -> {
                                    voice.playing = false
                                    null
                                }
                                else -> null
                            }
                        }
                    }
                    "newMusic" -> {
                        if (!musicAvailable) throw IllegalStateException("no music device")
                        fake(Music::class.java) { musicMethod, _ -> if (musicMethod == "isPlaying") false else null }
                    }
                    else -> null
                }
            }
            gameAudio = GameAudio { true }
            check(gameAudio, voices)
        } finally {
            gameAudio?.dispose()
            Gdx.audio = previousAudio
            Gdx.files = previousFiles
            Gdx.app = previousApp
        }
    }

    private fun <T> fake(type: Class<T>, invoke: (String, Array<out Any?>) -> Any?): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> invoke(method.name, args ?: emptyArray()) },
    )
}
