package io.doppel.adapter.common.module.combat

import io.doppel.core.condition.ConditionChecker
import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.core.strategy.combat.CombatTrigger
import io.doppel.core.strategy.tap.TapStateMachine
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
 * STap Module — brief S-key press during forward movement for sudden stops.
 *
 * Presses the back key (S) for [actionTicks] milliseconds while holding W,
 * causing a near-instant speed drop that disrupts opponent aim prediction.
 *
 * State machine managed by Core [TapStateMachine].
 * Only activates while holding W ([PlayerState.isMovingForward]).
 */
object STap : Module("STap", Category.COMBAT) {

    // ========== Version Selection ==========
    var combatVersion by choices("CombatVersion", arrayOf("1.8", "1.9+"))

    // ========== Shared Configuration ==========
    private val chance18 by int("Chance18", 100, 0..100, "%")
    private val chance19 by int("Chance19", 50, 0..100, "%")
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

    // ========== 1.8 Configuration ==========
    private val eventType by choices("EventType", arrayOf("POST", "PRE"))
    private val actionMin18 by int("ActionMsMin18", 25, 1..500, "ms")
    private val actionMax18 by int("ActionMsMax18", 55, 1..500, "ms")
    private val onceEveryMin by int("OnceEveryMin", 1, 1..10)
    private val onceEveryMax by int("OnceEveryMax", 1, 1..10)
    private val postDelayMin by int("PostDelayMin", 10, 0..500, "ms")
    private val postDelayMax by int("PostDelayMax", 40, 0..500, "ms")

    // ========== 1.9+ Configuration ==========
    private val actionMin19 by int("ActionMsMin19", 10, 1..20, "ms")
    private val actionMax19 by int("ActionMsMax19", 15, 1..20, "ms")
    private val onceEvery19Min by int("OnceEvery19Min", 2, 1..10)
    private val onceEvery19Max by int("OnceEvery19Max", 3, 1..10)

    // ========== Core State Machine ==========
    private val machine = TapStateMachine()
    private var hitCounter: Int = 0
    private var hitThreshold: Int = 1

    // ========== Tick Listener ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p, EventBridge.crosshairTarget)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        val now = System.nanoTime()

        // ---- State machine events ----
        when (machine.tick(now)) {
            TapStateMachine.Event.END_TAP -> EventBridge.syntheticBack = false
            TapStateMachine.Event.SHOULD_START_TAP -> startTap(now)
            TapStateMachine.Event.NONE -> {}
        }

        if (machine.phase != TapStateMachine.Phase.IDLE) return

        // In IDLE, mirror the TRUE physical back key (LWJGL Keyboard.isKeyDown) so the
        // override doesn't block the player from voluntarily walking backward (S). STap only
        // presses S for its own tap. isKeyBackDown is rewritten by the override (self-loop);
        // physicalBackDown is the raw physical key, unaffected.
        EventBridge.syntheticBack = EventBridge.physicalBackDown

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

        // Unified conditions
        if (!ConditionChecker.check(triggerOptions, player, target)) return

        // Route by version
        when (combatVersion) {
            "1.8" -> evaluate18(player, target, now)
            "1.9+" -> evaluate19(now)
        }
    }

    // ================================================================
    // 1.8 — CombatTrigger pipeline
    // ================================================================
    private fun evaluate18(player: PlayerState, target: TargetState, now: Long) {
        val eval = CombatTrigger.evaluate(
            mode = if (eventType == "PRE") CombatTrigger.Mode.PRE else CombatTrigger.Mode.POST,
            target = target,
            maxHurtTime = player.maxHurtResistantTime,
            hitCounter = hitCounter,
            hitThreshold = hitThreshold,
            hitPerMin = onceEveryMin,
            hitPerMax = onceEveryMax,
            chance = chance18,
            // Fire on the attack itself, not the target's i-frame window (20Hz sampling of it is
            // unreliable) — restores the working "attack triggers a tap" behaviour.
            hurtGate = false
        )
        hitCounter = eval.hitCounter
        hitThreshold = eval.hitThreshold
        if (!eval.fire) return

        val pd = Random.nextInt(postDelayMin, postDelayMax + 1)
        if (pd > 0) {
            machine.beginPostDelay(now, pd)
        } else {
            startTap(now)
        }
    }

    // ================================================================
    // 1.9+ — Low-frequency micro-stutter
    // ================================================================
    private fun evaluate19(now: Long) {
        if (!EventBridge.isLeftMousePhysicallyDown) return

        if (Random.nextInt(100) >= chance19) return

        hitCounter++
        if (hitCounter < hitThreshold) return
        hitCounter = 0
        hitThreshold = Random.nextInt(onceEvery19Min, onceEvery19Max + 1).coerceAtLeast(1)

        startTap(now)
    }

    // ================================================================
    // Execution
    // ================================================================
    private fun startTap(nowNs: Long) {
        EventBridge.syntheticBack = true  // press S for the tap
        val ms = when (combatVersion) {
            "1.8" -> Random.nextInt(actionMin18, actionMax18 + 1)
            "1.9+" -> Random.nextInt(actionMin19, actionMax19 + 1)
            else -> 40
        }
        machine.beginTap(nowNs, ms)
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        hitCounter = 0
        hitThreshold = when (combatVersion) {
            "1.8" -> Random.nextInt(onceEveryMin, onceEveryMax + 1).coerceAtLeast(1)
            "1.9+" -> Random.nextInt(onceEvery19Min, onceEvery19Max + 1).coerceAtLeast(1)
            else -> 1
        }
        EventBridge.syntheticBackOverride = true
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.syntheticBack = false
        EventBridge.syntheticBackOverride = false
        machine.reset()
        hitCounter = 0
    }
}
