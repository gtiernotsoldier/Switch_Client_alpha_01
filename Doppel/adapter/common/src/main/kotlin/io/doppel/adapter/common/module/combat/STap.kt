package io.doppel.adapter.common.module.combat

import io.doppel.ipc.Strategies
import io.doppel.ipc.Strategies.TapTick
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.option.int
import io.doppel.adapter.common.option.triggerOptions

/**
 * STap Module — brief S-key press during forward movement for sudden stops.
 *
 * Presses the back key (S) for [actionTicks] milliseconds while holding W,
 * causing a near-instant speed drop that disrupts opponent aim prediction.
 *
 * State machine managed by the core (`TAP_TICK` / `TAP_BEGIN`).
 * Only activates while holding W ([PlayerState.isMovingForward]).
 *
 * Combat triggering uses the 1.8 CombatTrigger pipeline only — the 1.9+
 * low-frequency stutter variant was removed as redundant.
 *
 * This module keeps only judgements (physical keys, target guards, the
 * CombatTrigger verdict), marshalling and the platform writes. The three
 * uniform range draws (post-delay, tap duration, initial hit threshold) run in
 * the core on STap's own RNG stream; no `kotlin.random` remains here.
 */
object STap : Module("STap", Category.COMBAT) {

    // ========== Shared Configuration ==========
    private val chance18 by int("Chance18", 100, 0..100, "%")
    private val rangeMin by float("RangeMin", 0.0f, 0.0f..6.0f, "blocks")
    private val rangeMax by float("RangeMax", 3.0f, 0.0f..6.0f, "blocks")
    private val onlyPlayers by boolean("OnlyPlayers", false)

    // ========== Shared Conditions (Unified Engine) ==========
    private val onlyPlane by boolean("OnlyPlane", true)
    private val onlyTargeting by boolean("OnlyTargeting", false)
    private val onlyMove by boolean("OnlyMove", false)
    private val onlyMoveForward by boolean("OnlyMoveForward", false)
    private val onlyWhenTargetGoesBack by boolean("OnlyWhenTargetGoesBack", false)

    private val triggerOptions by triggerOptions("Trigger") {
        onlyGround = onlyPlane
        onlyCurrentView = onlyTargeting
        onlyMove = this@STap.onlyMove
        onlyMoveForward = this@STap.onlyMoveForward
        onlyWhenTargetGoesBack = this@STap.onlyWhenTargetGoesBack
    }

    // ========== CombatTrigger configuration (1.8 mechanics) ==========
    // The "18" suffixes are historical: the 1.9+ variant these were paired
    // with was removed, but the keys are kept so existing configs still load.
    private val eventType by choices("EventType", arrayOf("POST", "PRE"))
    private val actionMin18 by int("ActionMsMin18", 25, 1..500, "ms")
    private val actionMax18 by int("ActionMsMax18", 55, 1..500, "ms")
    private val onceEveryMin by int("OnceEveryMin", 1, 1..10)
    private val onceEveryMax by int("OnceEveryMax", 1, 1..10)
    private val postDelayMin by int("PostDelayMin", 10, 0..500, "ms")
    private val postDelayMax by int("PostDelayMax", 40, 0..500, "ms")

    // ========== Core State Machine ==========
    // Phase, deadlines and all three range draws live in the core. The
    // counter/threshold pair is written back by the core's CombatTrigger
    // verdict and merely marshalled back on the next tick.
    private var hitCounter: Int = 0
    private var hitThreshold: Int = 1

    /** Ask STap's own core RNG stream for the configured range draws. */
    private fun sample(kind: Int, player: PlayerState, target: TargetState?) =
        Strategies.stapSample(
            player, target, kind,
            postDelayMin, postDelayMax, actionMin18, actionMax18,
            onceEveryMin, onceEveryMax
        )

    /**
     * Claim S with OWNED semantics — same reasoning as WTap: the tap works by the physical S being
     * ignored, and the arbiter is what keeps it from fighting the Velocity Sprint brace's additive
     * claim on the same tick.
     */
    private fun ownS(value: Boolean) = EventBridge.claimBack(
        name, value, EventBridge.KeyClaimMode.OWNED, EventBridge.KEY_PRIORITY_TAP
    )

    // ========== Tick Listener ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p, EventBridge.crosshairTarget)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        val now = System.nanoTime()

        // No core, no tap: the timing lives there, and a local guess would be a different module.
        if (!Strategies.online) { ownS(EventBridge.physicalBackDown); return }

        // ---- State machine events ----
        val t = Strategies.tapTick(player, target, now)
        when (t.event) {
            TapTick.END_TAP -> ownS(false)
            TapTick.SHOULD_START_TAP -> {
                // A post-delay just elapsed. The duration is a core draw; a
                // missing answer means "do not tap" rather than a local guess.
                val s = sample(Strategies.TapSampleKind.ACTION, player, target)
                if (s != null) startTap(player, target, now, s.actionMs)
            }
            else -> {}
        }

        if (t.phase != TapTick.IDLE) return

        // In IDLE, mirror the TRUE physical back key (LWJGL Keyboard.isKeyDown) so the
        // override doesn't block the player from voluntarily walking backward (S). STap only
        // presses S for its own tap. isKeyBackDown is rewritten by the override (self-loop);
        // physicalBackDown is the raw physical key, unaffected.
        ownS(EventBridge.physicalBackDown)

        // Guard: W must be physically held (Raven: Keyboard.isKeyDown(keyBindForward)).
        // Use the TRUE physical key state (LWJGL), not isMovingForward (velocity lag) and
        // not isKeyForwardDown (rewritten by the override).
        if (!EventBridge.physicalForwardDown) { hitCounter = 0; return }

        // Raven: only tap while attacking — require the effective left click (physical OR
        // synthetic) so it works both manually and with AutoClicker.
        if (!(EventBridge.syntheticAttack || player.isAttackKeyDown)) {
            hitCounter = 0
            return
        }

        // Target guards
        if (target == null || target.distance < rangeMin || target.distance > rangeMax) { hitCounter = 0; return }
        if (target.health <= 0f) { hitCounter = 0; return }
        if (onlyPlayers && target.name.isEmpty()) { hitCounter = 0; return }

        // The gate runs inside the trigger — one round trip, one random stream.
        evaluate18(player, target, now)
    }

    // ================================================================
    // CombatTrigger pipeline (1.8 mechanics)
    // ================================================================
    private fun evaluate18(player: PlayerState, target: TargetState, now: Long) {
        // hurtGate = false: fire on the attack itself, not the target's i-frame window (20 Hz
        // sampling of that window is unreliable) — restores "attack triggers a tap".
        val decision = Strategies.decide(
            player, target, triggerOptions,
            mode = if (eventType == "PRE") 1 else 0,
            hurtGate = false,
            maxHurtTime = player.maxHurtResistantTime,
            targetHurtResistantTime = target.hurtResistantTime,
            hitCounter = hitCounter,
            hitThreshold = hitThreshold,
            hitPerMin = onceEveryMin,
            hitPerMax = onceEveryMax,
            triggerChance = chance18
        ) ?: return
        hitCounter = decision.counter
        hitThreshold = decision.threshold
        if (!decision.fire) return

        // Fire point: draw the post-delay and the duration in the core. A
        // non-zero post-delay arms a delayed start; zero starts the tap now.
        val s = sample(Strategies.TapSampleKind.POST_DELAY_OR_START, player, target) ?: return
        if (s.postDelayMs > 0) {
            Strategies.tapBegin(player, target, now, s.postDelayMs, postDelay = true)
        } else {
            startTap(player, target, now, s.actionMs)
        }
    }

    // ================================================================
    // Execution (platform writes only)
    // ================================================================
    private fun startTap(player: PlayerState, target: TargetState?, nowNs: Long, actionMs: Int) {
        ownS(true)                         // press S for the tap
        markWorked()                      // HUD work flash — the module just acted
        Strategies.tapBegin(player, target, nowNs, actionMs, postDelay = false)
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        hitCounter = 0
        // Seed the throttle from the core's stream. Off-line, keep 1 — the
        // same value the module used before any tap could be computed.
        hitThreshold = sample(Strategies.TapSampleKind.INIT, PlayerState.EMPTY, null)?.threshold ?: 1
        ownS(EventBridge.physicalBackDown)
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.releaseBack(name)
        Strategies.resetTap()
        hitCounter = 0
    }
}
