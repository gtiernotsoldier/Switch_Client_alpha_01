package io.doppel.core.strategy.player

/**
 * KnockbackDisplace's one-tick yaw flick — the Kotlin **oracle** for
 * `strategy::knockback_displace`.
 *
 * The ship path is the Rust port; `KnockbackDisplaceParityTest` replays the
 * golden fixture through this class. Nothing shipped refers to it.
 *
 * Note what is *not* here: how long the offset stays applied. The Rust side says
 * "this attack flicks 90°", the adapter decides when to write and restore the
 * rotation. That split is what keeps the module's state down to two fields.
 */
object KnockbackDisplaceLogic {

    enum class Direction {
        Left, Right, Random, Strafe, StrafeInverted;

        companion object {
            /** Unknown wire values fall back to Left rather than throwing. */
            fun fromOrdinal(v: Int): Direction = values().getOrElse(v) { Left }
        }
    }

    class State(
        var lastStrafeLeft: Boolean = false,
        /** -1 so a clock reading 0 is a valid attack instead of "never". */
        var lastFlickNs: Long = -1L
    )

    class Input(
        val nowNs: Long,
        val direction: Direction,
        val useLastStrafe: Boolean,
        val cooldownMs: Int,
        val angle: Float,
        val notTakingKnockback: Boolean,
        val velocityThisTick: Boolean,
        val keyLeft: Boolean,
        val keyRight: Boolean
    )

    /**
     * A landed hit. Returns the yaw offset in degrees, or 0 for "no flick".
     *
     * @param randomSign supplies the `Random` direction. Called only when that
     *        direction is selected, so the stream does not advance otherwise.
     */
    fun attack(state: State, input: Input, randomSign: () -> Float): Float {
        // Track the strafe direction even when the cooldown swallows this attack:
        // "use last strafe" describes what the player was doing, not whether a
        // flick happened.
        if (input.keyLeft != input.keyRight) {
            state.lastStrafeLeft = input.keyLeft
        }

        if (state.lastFlickNs >= 0 &&
            input.nowNs - state.lastFlickNs < input.cooldownMs.toLong() * 1_000_000L
        ) {
            return 0f
        }
        if (input.notTakingKnockback && input.velocityThisTick) {
            return 0f
        }

        val sign = when (input.direction) {
            Direction.Left -> 1f
            Direction.Right -> -1f
            Direction.Random -> randomSign()
            Direction.Strafe, Direction.StrafeInverted -> {
                val base = when {
                    input.keyLeft && !input.keyRight -> 1f
                    input.keyRight && !input.keyLeft -> -1f
                    input.useLastStrafe -> if (state.lastStrafeLeft) 1f else -1f
                    else -> 0f
                }
                if (input.direction == Direction.StrafeInverted) -base else base
            }
        }

        if (sign == 0f) {
            return 0f
        }

        state.lastFlickNs = input.nowNs
        return sign * input.angle
    }
}
