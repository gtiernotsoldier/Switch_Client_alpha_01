package io.doppel.core.strategy.player

/**
 * Eagle's per-tick decision.
 *
 * This copy is the **oracle**: the live path is `strategy::eagle` in
 * `core-rs`, and `EagleParityTest` replays the golden fixture through this
 * object to prove the two agree. Nothing in the shipped agent refers to it —
 * `:core` is not a dependency of any runtime module.
 *
 * Mirrors Raven's `SafeWalk.onPreUpdate` + `setSneakState`:
 *  - settings not met            → release (unless the player holds the key), forget the delay;
 *  - on edge, nothing held       → press once, arm the release delay;
 *  - on edge, player already sneaking (their own key) → hands off, press nothing;
 *  - off edge, still holding     → count the delay down;
 *  - off edge, delay elapsed     → release, unless the player holds the key (Raven's isBindDown
 *                                  guard in setSneakState) — then keep holding and retry next tick.
 */
object EagleLogic {

    data class Input(
        /** `onGround && isEdgeOfBlock()` — true when the block under the player's feet is air. */
        val onEdge: Boolean,
        /** The module's pitch gate (`OnlyWhenLookingDown`). */
        val settingsMet: Boolean,
        /** The player is physically holding the sneak key (a manual sneak we must not fight). */
        val sneakKeyHeld: Boolean,
        /** Whether the sneak currently held was pressed by this module. */
        val autoSneakActive: Boolean,
        /** Ticks left to keep sneaking after leaving the edge. */
        val unsneakDelayTicks: Int
    )

    data class Decision(
        /** Write the sneak key down this tick. */
        val press: Boolean,
        /** Write the sneak key up this tick. */
        val release: Boolean,
        val autoSneakActive: Boolean,
        val unsneakDelayTicks: Int
    )

    /**
     * @param rollSneakDelay invoked only when a fresh edge press arms the delay, so the caller's
     *        randomness never advances while the module is idle.
     */
    fun tick(input: Input, rollSneakDelay: () -> Int): Decision {
        // Gate off → let go immediately (Raven setSneakState(false) on !settingsMet) ...
        if (!input.settingsMet) {
            return Decision(
                press = false,
                release = input.autoSneakActive && !input.sneakKeyHeld,
                autoSneakActive = false,
                unsneakDelayTicks = 0
            )
        }

        if (input.onEdge) {
            // Already holding our own sneak — nothing to write.
            if (input.autoSneakActive) {
                return Decision(false, false, true, input.unsneakDelayTicks)
            }
            // The player is sneaking on their own: hands off, do not take ownership (so the later
            // release can never write their key up).
            if (input.sneakKeyHeld) {
                return Decision(false, false, false, 0)
            }
            return Decision(
                press = true,
                release = false,
                autoSneakActive = true,
                unsneakDelayTicks = rollSneakDelay().coerceAtLeast(0)
            )
        }

        // Off the edge and not holding anything → nothing to do.
        if (!input.autoSneakActive) {
            return Decision(false, false, false, 0)
        }
        if (input.unsneakDelayTicks > 0) {
            return Decision(false, false, true, input.unsneakDelayTicks - 1)
        }
        // Raven: `if (!sneakState && Utils.isBindDown(keyBindSneak)) return;` — keep holding while
        // the player holds the key themselves, and retry (with the delay spent) next tick.
        if (input.sneakKeyHeld) {
            return Decision(false, false, true, 0)
        }
        return Decision(press = false, release = true, autoSneakActive = false, unsneakDelayTicks = 0)
    }
}
