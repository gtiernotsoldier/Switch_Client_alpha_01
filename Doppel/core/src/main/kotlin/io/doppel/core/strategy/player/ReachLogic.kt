package io.doppel.core.strategy.player

/**
 * Reach's press sampling — the Kotlin **oracle** for `strategy::reach_sample`.
 *
 * The ship path is the Rust port; `ReachParityTest` replays the golden fixture
 * through this object. Nothing shipped refers to it.
 *
 * The gate order is the algorithm here, not an implementation detail: the
 * throttle counter advances only for presses that survived the chance roll, so
 * moving that line silently changes how often the module fires.
 */
object ReachLogic {

    class State(
        /** Presses seen since the last extension, for `HitPer`. */
        var clickCount: Int = 0,
        /** -1 so a clock reading 0 is a valid extension instead of "never". */
        var lastExtendNs: Long = -1L
    )

    class Input(
        val nowNs: Long,
        val conditionsOk: Boolean,
        val guiOpen: Boolean,
        val chance: Int,
        val hitPer: Int,
        val delayMs: Int,
        val hitThroughBlocks: Boolean,
        val lookingAtBlock: Boolean,
        val reachMin: Float,
        val reachMax: Float
    )

    /**
     * One press. Returns the distance to use, or 0.0 to leave the press alone.
     *
     * @param rollChance called only when `chance < 100`, so the stream does not
     *        advance for a guaranteed pass.
     * @param rollDistance called only when the range is non-empty.
     */
    fun sample(
        state: State,
        input: Input,
        rollChance: () -> Int,
        rollDistance: () -> Float
    ): Double {
        if (!input.conditionsOk || input.guiOpen) {
            return 0.0
        }
        if (input.chance < 100 && rollChance() >= input.chance) {
            return 0.0
        }

        // HitPer throttle: extend only every N presses.
        state.clickCount++
        if (state.clickCount < input.hitPer) {
            return 0.0
        }
        state.clickCount = 0

        // Delay cooldown: skip if we extended too recently.
        if (input.delayMs > 0 &&
            state.lastExtendNs >= 0 &&
            input.nowNs - state.lastExtendNs < input.delayMs.toLong() * 1_000_000L
        ) {
            return 0.0
        }

        // Hit-through-blocks: skip when the crosshair currently hits a block.
        if (!input.hitThroughBlocks && input.lookingAtBlock) {
            return 0.0
        }

        state.lastExtendNs = input.nowNs

        return if (input.reachMax > input.reachMin) {
            (input.reachMin + rollDistance() * (input.reachMax - input.reachMin)).toDouble()
        } else {
            input.reachMin.toDouble()
        }
    }
}
