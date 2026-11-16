package io.github.lubacareer.spacepong

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowInsets
import android.view.WindowManager
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import io.github.lubacareer.spacepong.game.PlatformServices
import io.github.lubacareer.spacepong.game.SafeInsets
import io.github.lubacareer.spacepong.game.SpacePongGame
import java.io.File

/** Owns platform resources; gameplay and progression remain in the shared modules. */
class AndroidLauncher :
    AndroidApplication(),
    PlatformServices {
    lateinit var game: SpacePongGame
        private set

    @Volatile private var insets = SafeInsets()

    @Volatile private var audioFocusGranted = false
    private lateinit var focusRequest: AudioFocusRequest
    private lateinit var audioManager: AudioManager

    override val saveDirectory: File get() = File(filesDir, "campaign")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { change ->
                updateAudioFocus(change == AudioManager.AUDIOFOCUS_GAIN)
            }.build()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val configuration = AndroidApplicationConfiguration().apply {
            useAccelerometer = false
            useCompass = false
            useGyroscope = false
            useImmersiveMode = true
        }
        game = SpacePongGame(this)
        initialize(game, configuration)
        window.decorView.setOnApplyWindowInsetsListener { _, value ->
            val nextInsets = if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = value.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                SafeInsets(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                SafeInsets(value.systemWindowInsetLeft, value.systemWindowInsetTop, value.systemWindowInsetRight, value.systemWindowInsetBottom)
            }
            if (insets != nextInsets) {
                insets = nextInsets
                // Insets can change without resizing the GL surface (for example transient system bars).
                postRunnable { game.resize(graphics.width, graphics.height) }
            }
            value
        }
        window.decorView.requestApplyInsets()
    }

    override fun safeInsets(): SafeInsets = insets

    override fun hasAudioFocus(): Boolean = audioFocusGranted

    override fun haptic(strong: Boolean) {
        @Suppress("DEPRECATION")
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(if (strong) 35L else 12L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun setHighRefresh(enabled: Boolean) {
        runOnUiThread {
            @Suppress("DEPRECATION")
            val screen = windowManager.defaultDisplay
            val current = screen.mode
            val desired = screen.supportedModes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .filter { it.refreshRate <= if (enabled) 120.5f else 60.5f }
                .maxByOrNull { it.refreshRate } ?: current
            window.attributes = window.attributes.apply { preferredDisplayModeId = desired.modeId }
        }
    }

    override fun onResume() {
        super.onResume()
        requestGameAudioFocus()
    }

    override fun onPause() {
        // Revoke playback authorization before releasing focus or stopping the render thread.
        updateAudioFocus(false)
        if (::audioManager.isInitialized) audioManager.abandonAudioFocusRequest(focusRequest)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            interruptGame()
        } else if (!audioFocusGranted) {
            requestGameAudioFocus()
        } else {
            // A window interruption may suspend music without releasing audio focus.
            // The game restores permitted audio while keeping interrupted matches paused.
            updateAudioFocus(true)
        }
    }

    private fun requestGameAudioFocus() {
        if (::audioManager.isInitialized && ::focusRequest.isInitialized) {
            val result = audioManager.requestAudioFocus(focusRequest)
            updateAudioFocus(result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        }
    }

    private fun updateAudioFocus(granted: Boolean) {
        audioFocusGranted = granted
        if (::game.isInitialized) {
            // Use this activity's queue, not the process-global Gdx.app during recreation.
            postRunnable { game.audioFocusChanged(granted) }
        }
    }

    private fun interruptGame() {
        if (::game.isInitialized) postRunnable { game.pauseForInterruption() }
    }
}
