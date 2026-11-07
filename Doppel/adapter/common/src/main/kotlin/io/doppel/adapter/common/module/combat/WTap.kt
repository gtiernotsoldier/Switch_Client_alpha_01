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
import kotlin.random.Random

/**
 * WTap Module — sprint-reset via brief W-key release during combat.
 *
 * Releases the forward key (W) for [actionTicks] milliseconds after landing
 * an attack, then re-presses it. This resets the player's sprint state,
 * allowing knockback to function at full strength on the next hit.
 *
 * State machine managed by the core (`TAP_TICK` / `TAP_BEGIN`).
 * Only activates while the player is holding W ([PlayerState.isMovingForward]).
 */
object WTap : Module("WTap", Category.COMBAT) {

    // ========== Version Selection ==========
    var combatVersion by choices("CombatVersion", arrayOf("1.8", "1.9+"))

    // ========== Shared Configuration ==========
    private val chance by int("Chance", 100, 0..100, "%")
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
        onlyMove = this@WTap.onlyMove
        onlyMoveForward = this@WTap.onlyMoveForward
        onlyWhenTargetGoesBack = this@WTap.onlyWhenTargetGoesBack
    }

    // ========== 1.8 Configuration ==========
    private val eventType by choices("EventType", arrayOf("POST", "PRE"))
    private val actionMin18 by int("ActionMsMin18", 25, 1..500, "ms")
    private val actionMax18 by int("ActionMsMax18", 55, 1..500, "ms")
    private val onceEveryMin by int("OnceEveryMin", 1, 1..10)
    private val onceEveryMax by int("OnceEveryMax", 1, 1..10)
    private val postDelayMin by int("PostDelayMin", 25, 0..500, "ms")
    private val postDelayMax by int("PostDelayMax", 55, 0..500, "ms")

    // ========== 1.9+ Configuration ==========
    private val cooldownThreshold by float("CooldownThreshold", 1.0f, 0.5f..1.0f, "%")
    private val actionMin19 by int("ActionMsMin19", 15, 1..500, "ms")
    private val actionMax19 by int("ActionMsMax19", 30, 1..500, "ms")

    // ========== Core State Machine ==========
    // Phase and both deadlines live in the core; this module keeps only what it must release.
    private var hitCounter: Int = 0
    private var hitThreshold: Int = 1

    /**
     * Claim W with OWNED semantics.
     *
     * A W-tap is *defined* by the physical key being ignored (see the override contract in
     * [EventBridge.isKeyForwardActive] and its test), so the tap must own the key rather than OR
     * with it. Going through the arbiter instead of writing `syntheticForward` /
     * `syntheticForwardOverride` directly is what stops the Velocity Sprint brace — which asks for
     * W = pressed in the same tick on a trade — from cancelling the tap, and vice versa.
     */
    private fun ownW(value: Boolean) = EventBridge.claimForward(
        name, value, EventBridge.KeyClaimMode.OWNED, EventBridge.KEY_PRIORITY_TAP
    )

    // ========== Tick Listener ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p, EventBridge.crosshairTarget)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        val now = System.nanoTime()

        // No core, no tap: this module's whole job is a timed key press it cannot compute.
        if (!Strategies.online) { ownW(EventBridge.physicalForwardDown); return }

        // ---- State machine events ----
        val t = Strategies.tapTick(player, target, now)
        when (t.event) {
            TapTick.END_TAP -> ownW(true)
            TapTick.SHOULD_START_TAP -> startTap(player, target, now)
            else -> {}
        }

        // Only evaluate trigger in IDLE phase
        if (t.phase != TapTick.IDLE) return

        // Guard: W must be physically held. Use the TRUE physical key state (LWJGL
        // Keyboard.isKeyDown), not player.isMovingForward (velocity-based, lags after the
        // tap releases W) and not isKeyForwardDown (which the override rewrites). This is
        // exactly Raven's Keyboard.isKeyDown(keyBindForward.getKeyCode()).
        if (!EventBridge.physicalForwardDown) {
            ownW(false)
            hitCounter = 0
            return
        }
        // Player is holding W and not tapping — ensure the key reads as pressed.
        ownW(true)

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

        // Route by version
        when (combatVersion) {
            "1.8" -> evaluate18(player, target, now)
            "1.9+" -> evaluate19(player, target, now)
        }
    }

    // ================================================================
    // 1.8 — CombatTrigger pipeline
    // ================================================================
    private fun evaluate18(player: PlayerState, target: TargetState, now: Long) {
        // Gate and trigger in one call: they share the core's random stream, and asking twice
        // would spend two draws where the original spent one.
        // hurtGate = false: fire on the attack itself, not the target's i-frame window (20 Hz
        // sampling of that window is unreliable).
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
            triggerChance = chance
        ) ?: return
        hitCounter = decision.counter
        hitThreshold = decision.threshold
        if (!decision.fire) return

        val pd = Random.nextInt(postDelayMin, postDelayMax + 1)
        if (pd > 0) {
            Strategies.tapBegin(player, target, now, pd, postDelay = true)
        } else {
            startTap(player, target, now)
        }
    }

    // ================================================================
    // 1.9+ — Cooldown-based triggering
    // ================================================================
    private fun evaluate19(player: PlayerState, target: TargetState, now: Long) {
        if (!EventBridge.isLeftMousePhysicallyDown) return

        val cooldown = player.attackCooldownProgress
        if (cooldown < cooldownThreshold) return

        if (!Strategies.gate(player, target, triggerOptions)) return

        if (chance < 100 && Random.nextInt(100) >= chance) return

        startTap(player, target, now)
    }

    // ================================================================
    // Execution
    // ================================================================
    private fun startTap(player: PlayerState, target: TargetState?, nowNs: Long) {
        ownW(false)                           // release W for the tap
        markWorked()                          // HUD work flash — the module just acted
        val ms = when (combatVersion) {
            "1.8" -> Random.nextInt(actionMin18, actionMax18 + 1)
            "1.9+" -> Random.nextInt(actionMin19, actionMax19 + 1)
            else -> 40
        }
        Strategies.tapBegin(player, target, nowNs, ms, postDelay = false)
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        hitCounter = 0
        hitThreshold = Random.nextInt(onceEveryMin, onceEveryMax + 1).coerceAtLeast(1)
        ownW(EventBridge.physicalForwardDown)
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.releaseForward(name)
        Strategies.resetTap()
        hitCounter = 0
    }
}
