package io.github.lubacareer.spacepong.input

data class TouchRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    init {
        require(x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite())
        require(width > 0f && height > 0f)
    }

    fun contains(px: Float, py: Float): Boolean = px >= x && px <= x + width && py >= y && py <= y + height
}

/**
 * Routes logical arena coordinates, independent of Android/libGDX pointer numbering.
 * The player and action are captured once at touch-down and never change mid-touch.
 */
class TouchRouter(
    val localVersus: Boolean,
    val width: Float = 1280f,
    val height: Float = 720f,
) {
    private enum class Action { LEFT_DRAG, RIGHT_DRAG, LEFT_PULSE, RIGHT_PULSE }
    private data class Capture(val action: Action, val offsetY: Float = 0f, var pulsePending: Boolean = false)

    private val captures = mutableMapOf<Int, Capture>()
    private var leftPulseArea: TouchRect? = null
    private var rightPulseArea: TouchRect? = null

    // Released taps survive cancellation of a later capture, including reused pointer IDs.
    private var leftReleasedPulsePending = false
    private var rightReleasedPulsePending = false
    private var leftPulseEnabled = true
    private var rightPulseEnabled = true

    var leftTargetY: Float? = null
        private set
    var rightTargetY: Float? = null
        private set

    init {
        require(width.isFinite() && height.isFinite() && width > 0f && height > 0f)
    }

    fun pulseAreas(left: TouchRect, right: TouchRect? = null) {
        leftPulseArea = left
        rightPulseArea = right
    }

    fun down(pointer: Int, x: Float, y: Float, leftPaddleY: Float, rightPaddleY: Float): Boolean {
        if (pointer < 0 || pointer in captures || !x.isFinite() || !y.isFinite()) return false
        if (!leftPaddleY.isFinite() || !rightPaddleY.isFinite()) return false
        if (x !in 0f..width || y !in 0f..height) return false
        val leftHalf = x < width / 2f
        val action = when {
            leftPulseArea?.contains(x, y) == true && (!localVersus || leftHalf) -> Action.LEFT_PULSE
            localVersus && !leftHalf && rightPulseArea?.contains(x, y) == true -> Action.RIGHT_PULSE
            leftHalf -> Action.LEFT_DRAG
            localVersus -> Action.RIGHT_DRAG
            else -> return false
        }
        if (action == Action.LEFT_PULSE && !leftPulseEnabled || action == Action.RIGHT_PULSE && !rightPulseEnabled) return false
        if (captures.values.any { it.action == action }) return false
        val offset = when (action) {
            Action.LEFT_DRAG -> y - leftPaddleY
            Action.RIGHT_DRAG -> y - rightPaddleY
            else -> 0f
        }
        captures[pointer] = Capture(action, offset, pulsePending = action == Action.LEFT_PULSE || action == Action.RIGHT_PULSE)
        when (action) {
            Action.LEFT_DRAG -> leftTargetY = leftPaddleY
            Action.RIGHT_DRAG -> rightTargetY = rightPaddleY
            Action.LEFT_PULSE, Action.RIGHT_PULSE -> Unit
        }
        return true
    }

    fun move(pointer: Int, x: Float, y: Float): Boolean {
        val capture = captures[pointer] ?: return false
        if (!x.isFinite() || !y.isFinite()) return false
        when (capture.action) {
            Action.LEFT_DRAG -> leftTargetY = (y - capture.offsetY).coerceIn(0f, height)
            Action.RIGHT_DRAG -> rightTargetY = (y - capture.offsetY).coerceIn(0f, height)
            Action.LEFT_PULSE, Action.RIGHT_PULSE -> Unit
        }
        return true
    }

    fun up(pointer: Int) {
        val capture = captures.remove(pointer) ?: return
        when (capture.action) {
            Action.LEFT_DRAG -> leftTargetY = null
            Action.RIGHT_DRAG -> rightTargetY = null
            Action.LEFT_PULSE -> leftReleasedPulsePending = leftReleasedPulsePending || capture.pulsePending
            Action.RIGHT_PULSE -> rightReleasedPulsePending = rightReleasedPulsePending || capture.pulsePending
        }
    }

    /** OS cancellation invalidates the captured action; ordinary up preserves quick taps. */
    fun cancel(pointer: Int) {
        when (captures.remove(pointer)?.action) {
            Action.LEFT_DRAG -> leftTargetY = null
            Action.RIGHT_DRAG -> rightTargetY = null
            Action.LEFT_PULSE, Action.RIGHT_PULSE, null -> Unit
        }
    }

    fun setPulseEnabled(left: Boolean, right: Boolean = false) {
        leftPulseEnabled = left
        rightPulseEnabled = right
        if (!left) consumeLeftPulse()
        if (!right) consumeRightPulse()
    }

    fun cancelAll() {
        captures.clear()
        leftTargetY = null
        rightTargetY = null
        leftReleasedPulsePending = false
        rightReleasedPulsePending = false
    }

    fun consumeLeftPulse(): Boolean {
        val held = consumeHeldPulse(Action.LEFT_PULSE)
        return (leftReleasedPulsePending || held).also { leftReleasedPulsePending = false }
    }

    fun consumeRightPulse(): Boolean {
        val held = consumeHeldPulse(Action.RIGHT_PULSE)
        return (rightReleasedPulsePending || held).also { rightReleasedPulsePending = false }
    }

    private fun consumeHeldPulse(action: Action): Boolean {
        val capture = captures.values.firstOrNull { it.action == action } ?: return false
        return capture.pulsePending.also { capture.pulsePending = false }
    }
}
