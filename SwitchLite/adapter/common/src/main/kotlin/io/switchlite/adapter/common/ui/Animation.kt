package io.switchlite.adapter.common.ui

/**
 * SimpleAnimation — exponential approach, frame-rate independent.
 *
 * The step size is proportional to the distance to the target, which
 * gives a natural ease-in-out feel (fast when far, slow when close).
 * Delta compensation scales the step by the actual frame time so the
 * animation speed is identical on 60 FPS and 240 FPS monitors.
 */
class Animation(private val initialValue: Float = 0f) {

    private var value: Float = initialValue
    private var target: Float = initialValue
    private var lastMs: Long = System.currentTimeMillis()

    /** Start moving toward [target] at the given [speed]. Safe to call every frame. */
    fun setTarget(target: Float, speed: Float) {
        this.target = target
        val now = System.currentTimeMillis()
        val delta = (now - lastMs).toFloat().coerceAtLeast(0f)
        lastMs = now
        if (value == target) return

        val s = speed.coerceAtMost(28f)
        // One-frame step, proportional to remaining distance.
        val step = kotlin.math.abs(target - value) * 0.35f / (10f / s)
        val movement = step * (delta / 16f)
        value = if (target > value) {
            (value + movement).coerceAtMost(target)
        } else {
            (value - movement).coerceAtLeast(target)
        }
    }

    /** Snap instantly to [v] (used when animations are disabled). */
    fun snap(v: Float) {
        value = v
        target = v
        lastMs = System.currentTimeMillis()
    }

    /** Current animated value. */
    val valueF: Float get() = value

    /** Current animated value as Int. */
    val valueI: Int get() = value.toInt()

    /** True when the animation reached its target. */
    val isDone: Boolean get() = kotlin.math.abs(value - target) < 0.01f

    /** Reset to the initial value. */
    fun reset() {
        value = initialValue
        target = initialValue
        lastMs = System.currentTimeMillis()
    }
}
