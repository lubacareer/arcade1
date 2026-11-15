package io.github.lubacareer.spacepong.game

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Matrix4
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

object Palette {
    val background = Color.valueOf("060D1B")
    val panel = Color.valueOf("0D1B2D")
    val panelLight = Color.valueOf("12263C")
    val line = Color.valueOf("233C52")
    val ink = Color.valueOf("E8F2FA")
    val muted = Color.valueOf("90A9BC")
    val cyan = Color.valueOf("63E8E3")
    val amber = Color.valueOf("FFC685")
    val pink = Color.valueOf("EC8BC5")
}

/** Small reusable immediate canvas; transitions batch geometry and text by drawing mode. */
class Canvas {
    private val shapes = ShapeRenderer()
    private val batch = SpriteBatch()
    private val body = BitmapFont(Gdx.files.internal("fonts/body.fnt"))
    private val display = BitmapFont(Gdx.files.internal("fonts/display.fnt"))
    private val glyph = GlyphLayout()
    private val tint = Color()
    private val cornerCos = FloatArray(49) { cos(it * Math.PI / 24).toFloat() }
    private val cornerSin = FloatArray(49) { sin(it * Math.PI / 24).toFloat() }
    private var drawing = 0
    private val random = Random(321)
    private val stars = FloatArray(160 * 4).also { array ->
        for (i in 0 until 160) {
            array[i * 4] = random.nextFloat() * 1280
            array[i * 4 + 1] = random.nextFloat() * 720
            array[i * 4 + 2] = .7f + random.nextFloat() * 1.7f
            array[i * 4 + 3] = .15f + random.nextFloat() * .7f
        }
    }

    init {
        body.region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        display.region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
    }

    fun projection(matrix: Matrix4) {
        finish()
        shapes.projectionMatrix = matrix
        batch.projectionMatrix = matrix
    }

    private fun geometry() {
        if (drawing == 1) return
        finish()
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        drawing = 1
    }

    private fun typography() {
        if (drawing == 2) return
        finish()
        batch.begin()
        drawing = 2
    }

    fun finish() {
        if (drawing == 1) shapes.end()
        if (drawing == 2) batch.end()
        drawing = 0
    }

    fun rect(x: Float, y: Float, width: Float, height: Float, color: Color, alpha: Float = 1f) {
        geometry()
        shapes.color = tint.set(color).mul(1f, 1f, 1f, alpha)
        shapes.rect(x, y, width, height)
    }

    fun rounded(x: Float, y: Float, width: Float, height: Float, radius: Float, color: Color, alpha: Float = 1f) {
        if (width <= 0f || height <= 0f) return
        geometry()
        shapes.color = tint.set(color).mul(1f, 1f, 1f, alpha)
        val r = radius.coerceIn(0f, minOf(width, height) / 2f)
        if (r == 0f) {
            shapes.rect(x, y, width, height)
            return
        }
        // Partition the silhouette so translucent panels are blended exactly once.
        shapes.rect(x + r, y, width - 2 * r, height)
        shapes.rect(x, y + r, r, height - 2 * r)
        shapes.rect(x + width - r, y + r, r, height - 2 * r)
        for (corner in 0 until 4) {
            val cx = if (corner == 0 || corner == 3) x + width - r else x + r
            val cy = if (corner < 2) y + height - r else y + r
            for (segment in 0 until 12) {
                val index = corner * 12 + segment
                shapes.triangle(
                    cx,
                    cy,
                    cx + cornerCos[index] * r,
                    cy + cornerSin[index] * r,
                    cx + cornerCos[index + 1] * r,
                    cy + cornerSin[index + 1] * r,
                )
            }
        }
    }

    fun circle(x: Float, y: Float, radius: Float, color: Color, alpha: Float = 1f) {
        geometry()
        shapes.color = tint.set(color).mul(1f, 1f, 1f, alpha)
        shapes.circle(x, y, radius, 48)
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, color: Color, alpha: Float = 1f) {
        geometry()
        shapes.color = tint.set(color).mul(1f, 1f, 1f, alpha)
        shapes.rectLine(x1, y1, x2, y2, thickness)
    }

    fun triangle(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, color: Color, alpha: Float = 1f) {
        geometry()
        shapes.color = tint.set(color).mul(1f, 1f, 1f, alpha)
        shapes.triangle(x1, y1, x2, y2, x3, y3)
    }

    fun ring(x: Float, y: Float, radius: Float, color: Color, alpha: Float = 1f, thickness: Float = 1f) {
        for (i in 0 until 64) {
            val a = i * Math.PI * 2 / 64
            val b = (i + 1) * Math.PI * 2 / 64
            line(
                x + cos(a).toFloat() * radius,
                y + sin(a).toFloat() * radius,
                x + cos(b).toFloat() * radius,
                y + sin(b).toFloat() * radius,
                thickness,
                color,
                alpha,
            )
        }
    }

    fun text(value: String, x: Float, top: Float, size: Float = 24f, color: Color = Palette.ink, bold: Boolean = false) {
        typography()
        val font = if (bold) display else body
        font.data.setScale(size / if (bold) 96f else 48f)
        font.color = color
        font.draw(batch, value, x, top)
    }

    fun centered(value: String, x: Float, top: Float, size: Float = 24f, color: Color = Palette.ink, bold: Boolean = false) {
        val font = if (bold) display else body
        font.data.setScale(size / if (bold) 96f else 48f)
        glyph.setText(font, value)
        text(value, x - glyph.width / 2f, top, size, color, bold)
    }

    fun wrap(value: String, x: Float, top: Float, width: Float, size: Float = 22f, color: Color = Palette.muted) {
        typography()
        body.data.setScale(size / 48f)
        body.color = color
        body.draw(batch, value, x, top, width, com.badlogic.gdx.utils.Align.left, true)
    }

    fun background(time: Float, reducedMotion: Boolean, lowEffects: Boolean) {
        rect(0f, 0f, 1280f, 720f, Palette.background)
        if (!lowEffects) {
            circle(1080f, 580f, 350f, Palette.cyan, .012f)
            circle(180f, 80f, 290f, Palette.pink, .014f)
            for (i in 0..10) line(i * 128f, 0f, i * 128f, 720f, 1f, Palette.line, .15f)
            for (i in 0..6) line(0f, i * 120f, 1280f, i * 120f, 1f, Palette.line, .15f)
        }
        for (i in 0 until if (lowEffects) 55 else 160) {
            val offset = if (reducedMotion) 0f else time * (2f + stars[i * 4 + 2])
            val x = (stars[i * 4] + offset) % 1280
            circle(x, stars[i * 4 + 1], stars[i * 4 + 2], Palette.ink, stars[i * 4 + 3] * .5f)
        }
    }

    fun dispose() {
        finish()
        shapes.dispose()
        batch.dispose()
        body.dispose()
        display.dispose()
    }
}
