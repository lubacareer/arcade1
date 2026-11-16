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
        audio.effect("victory", GameSettings())
        assertTrue(voices.any { it.playing })
        audio.apply(GameSettings(muted = true))
        assertTrue(voices.none { it.playing })
    }

    @Test fun `zero effects volume stops active effects even when ambient music failed to load`() = withAudio(musicAvailable = false) { audio, voices ->
        audio.effect("goal", GameSettings())
        assertTrue(voices.any { it.playing })
        audio.apply(GameSettings(effectsVolume = 0f))
        assertTrue(voices.none { it.playing })
    }

    private class Voice(var playing: Boolean = false)

    private fun withAudio(musicAvailable: Boolean, check: (GameAudio, List<Voice>) -> Unit) {
        val previousAudio = Gdx.audio
        val previousFiles = Gdx.files
        val previousApp = Gdx.app
        val voices = mutableListOf<Voice>()
        var gameAudio: GameAudio? = null
        try {
            Gdx.files = fake(Files::class.java) { FileHandle("fixture.wav") }
            Gdx.app = fake(Application::class.java) { null }
            Gdx.audio = fake(Audio::class.java) { method ->
                when (method) {
                    "newSound" -> {
                        val voice = Voice().also(voices::add)
                        fake(Sound::class.java) { soundMethod ->
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
                        fake(Music::class.java) { musicMethod -> if (musicMethod == "isPlaying") false else null }
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

    private fun <T> fake(type: Class<T>, invoke: (String) -> Any?): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> invoke(method.name) },
    )
}
