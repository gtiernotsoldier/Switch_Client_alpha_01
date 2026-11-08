package io.doppel.core.strategy.player

/**
 * ClickAssist's press evaluation — the Kotlin **oracle** for
 * `strategy::click_assist`.
 *
 * The ship path is the Rust port; `ClickAssistParityTest` replays the golden
 * fixture through this object. Nothing shipped refers to it.
 *
 * The 500 ms edge history is part of the machine, not an input: the "above 5
 * CPS" gate reads the module's own record of when the player clicked, so a
 * version that rolled the chance first would spend draws on clicks that never
 * had a chance — and then disagree about the rate on the next one.
 */
object ClickAssistLogic {

    /** One bit per predicate, matching `strategy::click_assist::flags`. */
    object Flags {
        const val RIGHT_BUTTON = 1 shl 0
        const val GUI_OPEN = 1 shl 1
        const val USING_ITEM = 1 shl 2
        const val BLOCKING = 1 shl 3
        const val IS_WEAPON = 1 shl 4
        const val HAS_TARGET = 1 shl 5
        const val LOOKING_AT_BLOCK = 1 shl 6
        const val LEFT_ENABLED = 1 shl 7
        const val RIGHT_ENABLED = 1 shl 8
        const val WEAPONS_ONLY = 1 shl 9
        const val ONLY_AIMING_ENTITY = 1 shl 10
        const val ONLY_BLOCKS = 1 shl 11
        const val ABOVE_5_CPS = 1 shl 12
    }

    enum class Mode {
        Normal, Double, Random;

        companion object {
            fun fromOrdinal(v: Int): Mode = values().getOrElse(v) { Normal }
        }
    }

    class State {
        /** Physical click edges inside the last 500 ms, oldest first. */
        val recent = ArrayDeque<Long>(16)
    }

    class Input(
        val nowNs: Long,
        val flags: Int,
        val probability: Int,
        val mode: Mode
    )

    private const val WINDOW_NS = 500_000_000L

    /**
     * One physical press. Returns the number of extra clicks (0, 1 or 2).
     *
     * @param rollChance called only when `probability < 100`.
     * @param rollExtra called only in `Random` mode.
     */
    fun evaluate(
        state: State,
        input: Input,
        rollChance: () -> Int,
        rollExtra: () -> Int
    ): Int {
        // The edge is recorded first, before any gate: the 5 CPS estimate is
        // about what the player is doing, not about whether this press got
        // boosted.
        state.recent.addLast(input.nowNs)
        while (state.recent.isNotEmpty() && state.recent.first() < input.nowNs - WINDOW_NS) {
            state.recent.removeFirst()
        }

        val f = input.flags

        if (f and Flags.GUI_OPEN != 0 || f and Flags.USING_ITEM != 0 || f and Flags.BLOCKING != 0) {
            return 0
        }

        if (f and Flags.RIGHT_BUTTON != 0) {
            if (f and Flags.RIGHT_ENABLED == 0) return 0
            if (f and Flags.ONLY_BLOCKS != 0 && f and Flags.LOOKING_AT_BLOCK == 0) return 0
        } else {
            if (f and Flags.LEFT_ENABLED == 0) return 0
            if (f and Flags.WEAPONS_ONLY != 0 && f and Flags.IS_WEAPON == 0) return 0
            if (f and Flags.ONLY_AIMING_ENTITY != 0 && f and Flags.HAS_TARGET == 0) return 0
        }

        if (f and Flags.ABOVE_5_CPS != 0 && estimateCps(state.recent, input.nowNs) < 5f) {
            return 0
        }

        if (input.probability < 100 && rollChance() >= input.probability) {
            return 0
        }

        return when (input.mode) {
            Mode.Double -> 2
            Mode.Random -> rollExtra()
            Mode.Normal -> 1
        }
    }

    private fun estimateCps(recent: ArrayDeque<Long>, nowNs: Long): Float {
        if (recent.size < 2) return 0f
        val windowNs = nowNs - recent.first()
        if (windowNs <= 0) return 0f
        return (recent.size - 1).toFloat() / (windowNs / 1_000_000_000f)
    }
}
