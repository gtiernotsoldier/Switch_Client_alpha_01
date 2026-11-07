package io.doppel.contract.strategy.tap

/**
 * Strafe side for ADTap (keyboard A/D only — never the mouse).
 *
 * Part of the call contract rather than the pattern implementation: the adapter
 * has to name a side when it presses a key, and the Rust core has to name one
 * when it decides which key to press.
 */
enum class Side {
    LEFT, RIGHT;

    /** The opposite side. */
    fun flipped(): Side = if (this == LEFT) RIGHT else LEFT
}
