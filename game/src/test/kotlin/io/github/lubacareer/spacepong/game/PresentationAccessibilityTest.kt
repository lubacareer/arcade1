package io.github.lubacareer.spacepong.game

import com.badlogic.gdx.graphics.Color
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.pow

class PresentationAccessibilityTest {
    @Test fun `larger text improves hints body HUD and headings`() {
        val normal = PresentationStyle()
        val accessible = PresentationStyle(largeText = true)
        for (base in listOf(14f, 20f, 24f, 42f, 48f, 90f)) {
            assertTrue(accessible.fontSize(base) > normal.fontSize(base), "Text size $base must respond")
            assertTrue(accessible.fontSize(base) <= base * 1.2f, "Keep the known landscape layout bounded")
        }
    }

    @Test fun `high contrast makes secondary text and control outlines distinguishable`() {
        val style = PresentationStyle(highContrast = true)
        for (surface in listOf(Palette.background, Palette.panel, Palette.panelLight)) {
            assertTrue(contrast(style.color(Palette.muted), style.color(surface)) >= 7.0)
            assertTrue(contrast(style.color(Palette.line), style.color(surface)) >= 3.0)
        }
        assertTrue(contrast(style.color(Palette.background), style.color(Palette.cyan)) >= 7.0)
    }

    @Test fun `high contrast retains distinct bright hazard colors on the arena`() {
        val style = PresentationStyle(highContrast = true)
        for (hazard in listOf(Palette.amber, Palette.pink, Palette.cyan)) {
            assertTrue(contrast(style.color(hazard), style.color(Palette.background)) >= 7.0)
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val first = luminance(a)
        val second = luminance(b)
        return (maxOf(first, second) + .05) / (minOf(first, second) + .05)
    }

    private fun luminance(color: Color): Double {
        fun linear(value: Float): Double = if (value <= .04045f) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        return .2126 * linear(color.r) + .7152 * linear(color.g) + .0722 * linear(color.b)
    }
}
