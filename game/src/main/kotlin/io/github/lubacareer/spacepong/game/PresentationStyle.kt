package io.github.lubacareer.spacepong.game

import com.badlogic.gdx.graphics.Color

/** One policy feeds all menu and gameplay typography and semantic palette colors. */
class PresentationStyle(val largeText: Boolean = false, val highContrast: Boolean = false) {
    fun fontSize(base: Float): Float = if (!largeText) base else base * if (base > 32f) 1.07f else 1.16f

    fun color(normal: Color): Color {
        if (!highContrast) return normal
        return when (normal) {
            Palette.background -> background
            Palette.panel -> panel
            Palette.panelLight -> panelLight
            Palette.line -> outline
            Palette.ink -> ink
            Palette.muted -> secondary
            Palette.cyan -> cyan
            Palette.amber -> amber
            Palette.pink -> pink
            else -> normal
        }
    }

    private companion object {
        val background = Color.valueOf("02060C")
        val panel = Color.valueOf("08121C")
        val panelLight = Color.valueOf("102131")
        val outline = Color.valueOf("96ABB8")
        val ink = Color.WHITE
        val secondary = Color.valueOf("E2EFF8")
        val cyan = Color.valueOf("8FFFFF")
        val amber = Color.valueOf("FFE692")
        val pink = Color.valueOf("FFB6E1")
    }
}
