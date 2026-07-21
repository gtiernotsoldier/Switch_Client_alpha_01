package io.switchlite.core.util

/**
 * 2D vector for rotation (yaw, pitch)
 * Pure math representation
 */
data class Vec2(val yaw: Float, val pitch: Float) {
    companion object {
        val ZERO = Vec2(0f, 0f)
    }
}
