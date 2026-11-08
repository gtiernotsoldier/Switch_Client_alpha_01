package io.doppel.adapter.common.module.player

import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.ModuleRegistry
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.*
import io.doppel.ipc.EaglePlaceStep
import io.doppel.ipc.Strategies

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
 *     the edge query; see `strategy::eagle` in `core-rs` for the state machine that consumes it —
 *     the Kotlin oracle behind `EagleParityTest` is `io.doppel.core.strategy.player.EagleLogic`.)
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

    /**
     * How close to [placePitch] the view must actually be before a click is allowed, in degrees.
     *
     * The first version aimed and clicked in the same tick, so the click went wherever the view already
     * happened to point — the user saw placements continuing without any downward aim. This is the
     * tolerance the aim has to arrive inside first.
     */
    private val placeTolerance by int("PlaceTolerance", 6, 1..15, "°")

    // ========== Runtime state (owned by the module, mutated only from the tick listener) ==========
    /** Whether the sneak we are currently holding was pressed by this module (not by the player). */
    private var autoSneakActive: Boolean = false

    /**
     * Whether the right-click currently held is the one AutoPlace pressed.
     *
     * On the platform side `syntheticUse` is a **held** button — it is OR-ed into the right mouse
     * button every frame — so it has to be released again, and only by whoever pressed it.
     * This is a platform-ownership flag (who must release a real key), not decision
     * state: the aim-in-flight flag, the wait counter and the cooldown live in the
     * core. See `strategy::eagle_place` in `core-rs`.
     */
    private var placeUseOwned: Boolean = false

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onTick(p, t)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
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

        val decision = Strategies.eagleTick(
            player, target, onEdge, settingsMet, sneakKeyHeld,
            minOf(maxSneakTimeMin, maxSneakTimeMax),
            maxOf(maxSneakTimeMin, maxSneakTimeMax)
        )

        // No answer means the core is gone. Write nothing at all: pressing would
        // leave a sneak nobody owns, and releasing would cancel one the player is
        // holding by hand. A missed hold is recoverable; a stuck key is not.
        if (decision != null) {
            if (decision.press) {
                EventBridge.pressSneak()
                markWorked() // HUD work flash — the module just acted (a transition, not the steady hold)
            }
            if (decision.release) EventBridge.releaseSneak()
            autoSneakActive = decision.autoSneakActive
        }

        autoPlaceTick(player, onEdge)
    }

    /**
     * Bridging placement: while on an edge **and** sneaking, click the block in front of the feet.
     *
     * This side only makes judgements (the gate, the pitch tolerance), marshals
     * them, and turns the core's [EaglePlaceStep] into platform writes. The
     * aim-first timing — asking for the bridging angle, waiting for the view to
     * arrive inside [placeTolerance], giving up after [PLACE_MAX_AIM_TICKS],
     * cooldown between placements and its random draw — is `strategy::eagle_place`
     * in `core-rs`, matched tick-for-tick by `EaglePlaceParityTest`.
     *
     * AIM and CLICK are deliberately not the same tick: a click only goes out
     * once the view is already at the angle. BridgeAssist owning the view makes
     * the core stand down rather than fight for the camera, unless it already
     * holds the bridging angle (then the click goes ahead — they cooperate).
     */
    private fun autoPlaceTick(player: PlayerState, onEdge: Boolean) {
        // Release last tick's placement click FIRST. `EventBridge.syntheticUse` is not a one-shot: the
        // platform OR-s it into the right mouse button every frame, so leaving it set keeps the player
        // placing forever — which is exactly what "it still places with AutoPlace switched off" was.
        // One placement = one tick of use. Only ever clear our own press, never AutoBlock's.
        if (placeUseOwned) {
            EventBridge.syntheticUse = false
            placeUseOwned = false
        }

        // ---- Judgements (stay in the module) --------------------------------
        // The gate and the pitch tolerance are pure predicates over things only
        // the game side can see (the edge query, the real sneak key, the view).
        val sneaking = player.isSneaking || autoSneakActive
        val gatesOpen = shouldAutoPlace(enabled, autoPlace, onEdge, sneaking)
        val pitchInTolerance = pitchInTolerance(
            player.rotation.pitch, placePitch.toFloat(), placeTolerance.toFloat()
        )
        val bridgeAssistEnabled = ModuleRegistry.get("BridgeAssist")?.enabled == true

        // ---- Ask the core ---------------------------------------------------
        // The aim-in-flight flag, the wait counter, the cooldown AND its random
        // draw all live in the core on one RNG stream. This side sends the two
        // judgements and the tuning knobs, and gets back a single step. Every
        // tick sends a request even when the gates are closed: the core needs
        // `gatesOpen = false` to cancel a pending aim and forget its cooldown.
        val step = Strategies.eaglePlace(
            player, null,
            gatesOpen, pitchInTolerance, bridgeAssistEnabled,
            PLACE_MAX_AIM_TICKS,
            minOf(placeTicksMin, placeTicksMax), maxOf(placeTicksMin, placeTicksMax)
        ) ?: return // no answer: neither aim nor click — a stray block is unrecoverable

        // ---- Platform side effects (stay in the module) ---------------------
        when (step) {
            EaglePlaceStep.AIM ->
                // Ask for the bridging angle; the core counts the waiting ticks.
                EventBridge.setPlayerRotation(player.rotation.yaw, placePitch.toFloat())
            EaglePlaceStep.CLICK -> {
                EventBridge.syntheticUse = true
                placeUseOwned = true
                markWorked() // the HUD row flashes on the placement, like every other acting module
            }
            EaglePlaceStep.ABORT, EaglePlaceStep.NONE -> Unit // no write this tick
        }
    }

    /**
     * A geometry comparison the JVM keeps (Geometry is an explicit whitelist,
     * see `PROTECTION.md` 9.4): is the view already inside the tolerance of the
     * bridging angle? Pure judgement, no state — the timing decisions that used
     * to sit beside it (`waitedTicks`, cooldown, the random draw) are in the core.
     */
    internal fun pitchInTolerance(currentPitch: Float, targetPitch: Float, tolerance: Float): Boolean =
        kotlin.math.abs(currentPitch - targetPitch) <= tolerance

    /** How many ticks an aim may take before AutoPlace gives up on it (no click without the aim). */
    private const val PLACE_MAX_AIM_TICKS = 3

    /**
     * The AutoPlace gate, pure so the rule is testable: the module is on, the option is on, the player
     * is on an edge, and the player is sneaking (manually or because this module pressed it).
     */
    internal fun shouldAutoPlace(enabled: Boolean, autoPlace: Boolean, onEdge: Boolean, sneaking: Boolean): Boolean =
        enabled && autoPlace && onEdge && sneaking

    override fun onEnable() {
        autoSneakActive = false
        // A fresh enable must not inherit a hold the core still believes it owns,
        // nor a placement aim/cooldown left over from the previous activation —
        // resetPlayer() resets the core's eagle + eagle_place state.
        Strategies.resetPlayer()
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        // Release only the sneak THIS module pressed — a manual sneak (which we never took ownership
        // of) is left completely alone. (Raven only releases on disable while still on the edge; the
        // requirement here is that disabling the module never leaves the player stuck sneaking, so
        // this releases wherever the player happens to be.)
        if (autoSneakActive) EventBridge.releaseSneak()
        // Give the right button back if we are the ones holding it. The tick listener is unregistered
        // here, so nothing else would ever clear it — and a stuck synthetic use is a player who cannot
        // stop placing.
        if (placeUseOwned) {
            EventBridge.syntheticUse = false
            placeUseOwned = false
        }
        Strategies.resetPlayer()
        autoSneakActive = false
    }
}
