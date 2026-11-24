package io.github.lubacareer.spacepong.bricks

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Sweeps a circle against rectangle faces and their four rounded Minkowski corners. */
internal class CircleSweep(private val radius: Double, private val collectTouching: Boolean = false) {
    var time = Double.POSITIVE_INFINITY
        private set
    var normalX = 0.0
        private set
    var normalY = 0.0
        private set
    private var x = 0.0
    private var y = 0.0
    private var vx = 0.0
    private var vy = 0.0
    private var endX = 0.0
    private var endY = 0.0
    private var limit = 0.0

    fun path(x: Double, y: Double, vx: Double, vy: Double, seconds: Double) {
        this.x = x
        this.y = y
        this.vx = vx
        this.vy = vy
        endX = x + vx * seconds
        endY = y + vy * seconds
        limit = seconds
    }

    fun hits(left: Double, bottom: Double, width: Double, height: Double): Boolean {
        time = Double.POSITIVE_INFINITY
        val right = left + width
        val top = bottom + height
        if (max(x, endX) + radius < left || min(x, endX) - radius > right ||
            max(y, endY) + radius < bottom || min(y, endY) - radius > top
        ) {
            return false
        }
        if (collectTouching) {
            val dx = x - x.coerceIn(left, right)
            val dy = y - y.coerceIn(bottom, top)
            if (dx * dx + dy * dy <= radius * radius) {
                time = 0.0
                return true
            }
        }
        if (vx != 0.0) {
            val nx = if (vx > 0) -1.0 else 1.0
            val face = if (vx > 0) left - radius else right + radius
            val t = (face - x) / vx
            if (y + vy * t in bottom..top) consider(t, nx, 0.0)
        }
        if (vy != 0.0) {
            val ny = if (vy > 0) -1.0 else 1.0
            val face = if (vy > 0) bottom - radius else top + radius
            val t = (face - y) / vy
            if (x + vx * t in left..right) consider(t, 0.0, ny)
        }
        corner(left, bottom, -1, -1)
        corner(left, top, -1, 1)
        corner(right, bottom, 1, -1)
        corner(right, top, 1, 1)
        return time.isFinite()
    }

    private fun corner(cx: Double, cy: Double, xSign: Int, ySign: Int) {
        val dx = x - cx
        val dy = y - cy
        val approach = dx * vx + dy * vy
        if (approach >= 0) return
        val distanceTerm = dx * dx + dy * dy - radius * radius
        val discriminant = approach * approach - (vx * vx + vy * vy) * distanceTerm
        if (discriminant < 0 || discriminant == 0.0 && !collectTouching) return
        // The conjugate form retains precision for a short time to first contact.
        val t = distanceTerm / (-approach + sqrt(discriminant))
        val nx = (dx + vx * t) / radius
        val ny = (dy + vy * t) / radius
        if (nx * xSign >= 0 && ny * ySign >= 0) consider(t, nx, ny)
    }

    private fun consider(t: Double, nx: Double, ny: Double) {
        if (t < -TIME_EPSILON || t > limit + TIME_EPSILON || t >= time) return
        time = t.coerceIn(0.0, limit)
        normalX = nx
        normalY = ny
    }

    companion object {
        const val TIME_EPSILON = 1e-10
    }
}
