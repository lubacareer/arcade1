package io.github.lubacareer.spacepong.desktop

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import io.github.lubacareer.spacepong.game.PlatformServices
import io.github.lubacareer.spacepong.game.SpacePongGame
import java.io.File

fun main(args: Array<String>) {
    val smoke = args.contains("--smoke")
    val platform = object : PlatformServices {
        override val saveDirectory = if (smoke) File("../desktop/build/smoke-save") else File(System.getProperty("user.home"), ".space-pong")
        override fun haptic(strong: Boolean) = Unit
        override fun setHighRefresh(enabled: Boolean) {
            (Gdx.graphics as? Lwjgl3Graphics)?.setForegroundFPS(if (enabled) 120 else 60)
        }
    }
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Space Pong")
        setInitialVisible(!smoke)
        setWindowedMode(1280, 720)
        setWindowSizeLimits(640, 360, -1, -1)
        useVsync(true)
        setForegroundFPS(60)
        setIdleFPS(30)
        setPauseWhenMinimized(!smoke)
        setPauseWhenLostFocus(!smoke)
    }
    val game = SpacePongGame(platform)
    val listener = if (!smoke) {
        game
    } else {
        object : ApplicationListener {
            private var frames = 0
            override fun create() = game.create()
            override fun resize(width: Int, height: Int) = game.resize(width, height)
            override fun pause() = game.pause()
            override fun resume() = game.resume()
            override fun dispose() = game.dispose()
            override fun render() {
                game.render()
                if (++frames == 120) {
                    val pixels = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.width, Gdx.graphics.height)
                    try {
                        PixmapIO.writePNG(Gdx.files.local("../artifacts/desktop-menu.png"), pixels, 6, true)
                    } finally {
                        pixels.dispose()
                    }
                    Gdx.app.exit()
                }
            }
        }
    }
    Lwjgl3Application(listener, config)
}
