package io.doppel.adapter.common.module.player

import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.*
import kotlin.random.Random

/**
 * Eagle — auto-sneak on a block edge. Port of Raven's SafeWalk sneak branch.
 *
 * Raven (`module/impl/player/SafeWalk.java`, `onPreUpdate` + `setSneakState`) is the authority:
 *
 *  1. `edge = mc.thePlayer.onGround && Utils.isEdgeOfBlock()`, where `isEdgeOfBlock()` asks whether
 *     the block the player is standing ON is air:
 *         posY - ((posY % 1.0 == 0.0) ? 1 : 0)
 *     — feet exactly on a block top → the block below; otherwise (slab/stair/snow layer) the block
 *     the feet are inside, i.e. the one actually carrying the player. (Doppel's platform hook does
 *     the edge query; see [EagleLogic] for the state machine that consumes it.)
 *  2. While the edge holds and its settings are met, the sneak key is pressed ONCE and the release
 *     delay is armed. Off the edge the delay is counted down and only then released.
 *  3. `setSneakState(false)` refuses to release while the player is physically holding the sneak key
 *     (`Utils.isBindDown(mc.gameSettings.keyBindSneak)`): the module must never fight a manual sneak.
 *
 * What the previous Doppel version did instead — and why the user reported "does not work":
 *  - it pressed AND released from the same `onEdge` boolean, so the sneak was dropped the instant
 *    the edge test flickered: no hold at all, and the "hold for MaxSneakTime after the edge" that
 *    its own KDoc advertised never happened (the delay was rolled when *entering* the edge, then
 *    counted while still on the edge);
 *  - after the delay had elapsed it called releaseSneak() on every subsequent tick, i.e. it kept
 *    writing the sneak KeyBinding to false — actively cancelling a manual sneak;
 *  - the `player.isSneaking && sneakTicks == 0` "don't override manual sneak" guard could never
 *    work: the auto-sneak itself sets `isSneaking`, and the guard only covered a subset of ticks.
 */
object Eagle : Module("Eagle", Category.PLAYER) {

    // ========== Config ==========
    // Raven: SliderSetting("Sneak delay", " tick", 0, 0, 20, 1) — how many ticks to keep sneaking
    // after the player has left the edge. Doppel keeps its randomized Min/Max pair (the range is
    // rendered as a range on the HUD), which is the same behaviour with anti-cheat-visible jitter.
    private val maxSneakTimeMin by int("MaxSneakTimeMin", 1, 1..5, "ticks")
    private val maxSneakTimeMax by int("MaxSneakTimeMax", 5, 1..5, "ticks")

    // Raven: ButtonSetting("Pitch check", false) with a hard-coded 70° threshold; Doppel makes the
    // threshold tunable and keeps the same sign convention (positive pitch = looking down).
    private val onlyWhenLookingDown by boolean("OnlyWhenLookingDown", false)
    private val lookDownThreshold by int("LookDownThreshold", 45, 0..90, "°")

    // ========== AutoPlace — bridging placement ==========
    /**
     * Simulate a right-click to place a block, the way bridging clients do.
     *
     * Two gates, and both must hold: the player is standing on an edge **and** is sneaking (a manual
     * sneak counts, as does one this module pressed). Without them this is just an auto-clicker aimed
     * at the ground, which is also what makes it detectable — the reference clients gate the
     * placement exactly the same way, on the "should bridge" state.
     */
    private val autoPlace by boolean("AutoPlace", false)

    /** The downward angle the placement flick uses. Bridging aims at the block by the feet. */
    private val placePitch by int("PlacePitch", 78, 40..90, "°")

    /** Ticks between placements. A Min/Max pair: the jitter is the point, and the HUD renders a range. */
    private val placeTicksMin by int("PlaceTicksMin", 1, 1..3, "ticks")
    private val placeTicksMax by int("PlaceTicksMax", 3, 1..3, "ticks")

    // ========== Runtime state (owned by the module, mutated only from the tick listener) ==========
    /** Whether the sneak we are currently holding was pressed by this module (not by the player). */
    private var autoSneakActive: Boolean = false

    /** Ticks left to keep sneaking after the player left the edge. */
    private var unsneakDelayTicks: Int = 0

    /** Ticks left before the next AutoPlace right-click. */
    private var placeCooldownTicks: Int = 0

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p)
    }

    private fun onTick(player: PlayerState) {
        // Raven: settingsMet() — the pitch gate. Evaluated before the edge so the sneak is released
        // the moment the gate stops being met, exactly like SafeWalk.
        val settingsMet = !onlyWhenLookingDown || player.rotation.pitch >= lookDownThreshold

        // "The player is holding sneak themselves." Raven reads the real key
        // (Utils.isBindDown(keyBindSneak)); Doppel has no physical sneak-key hook yet, so derive it
        // from the entity state: while autoSneakActive is false, an already-sneaking player can only
        // be sneaking on their own input. The AND also keeps our own press (whose entity state lags
        // one tick) from looking like a manual sneak.
        // ⚠ Needs `EventBridge.isKeySneakDown` for the exact Raven guard — see the report.
        val sneakKeyHeld = player.isSneaking && !autoSneakActive

        // Read once: the sneak state machine and AutoPlace must agree on whether the player is on an
        // edge this tick.
        val onEdge = EventBridge.isOnBlockEdge()

        val decision = EagleLogic.tick(
            EagleLogic.Input(
                onEdge = onEdge,
                settingsMet = settingsMet,
                sneakKeyHeld = sneakKeyHeld,
                autoSneakActive = autoSneakActive,
                unsneakDelayTicks = unsneakDelayTicks
            ),
            rollSneakDelay = { Random.nextInt(maxSneakTimeMin, maxSneakTimeMax + 1) }
        )

        if (decision.press) {
            EventBridge.pressSneak()
            markWorked() // HUD work flash — the module just acted (a transition, not the steady hold)
        }
        if (decision.release) EventBridge.releaseSneak()

        autoSneakActive = decision.autoSneakActive
        unsneakDelayTicks = decision.unsneakDelayTicks

        autoPlaceTick(player, onEdge)
    }

    /**
     * Bridging placement: while on an edge **and** sneaking, click the block in front of the feet.
     *
     * The look is flicked down to [placePitch] only for the tick the click happens — that is what makes
     * the ray hit the block below the crosshair rather than the horizon — and the player's own mouse
     * input takes the view back on the next tick, so this cannot pin the camera.
     */
    private fun autoPlaceTick(player: PlayerState, onEdge: Boolean) {
        val sneaking = player.isSneaking || autoSneakActive
        if (!shouldAutoPlace(enabled, autoPlace, onEdge, sneaking)) {
            placeCooldownTicks = 0
            return
        }
        if (placeCooldownTicks > 0) {
            placeCooldownTicks--
            return
        }
        EventBridge.setPlayerRotation(player.rotation.yaw, placePitch.toFloat())
        EventBridge.syntheticUse = true
        markWorked() // the HUD row flashes on the placement, like every other acting module
        placeCooldownTicks = Random.nextInt(placeTicksMin, placeTicksMax + 1)
    }

    /**
     * The AutoPlace gate, pure so the rule is testable: the module is on, the option is on, the player
     * is on an edge, and the player is sneaking (manually or because this module pressed it).
     */
    internal fun shouldAutoPlace(enabled: Boolean, autoPlace: Boolean, onEdge: Boolean, sneaking: Boolean): Boolean =
        enabled && autoPlace && onEdge && sneaking

    override fun onEnable() {
        autoSneakActive = false
        unsneakDelayTicks = 0
        placeCooldownTicks = 0
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        // Release only the sneak THIS module pressed — a manual sneak (which we never took ownership
        // of) is left completely alone. (Raven only releases on disable while still on the edge; the
        // requirement here is that disabling the module never leaves the player stuck sneaking, so
        // this releases wherever the player happens to be.)
        if (autoSneakActive) EventBridge.releaseSneak()
        autoSneakActive = false
        unsneakDelayTicks = 0
        placeCooldownTicks = 0
    }
}

/**
 * Eagle's per-tick decision, split out of the module so it is pure and unit-testable.
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
