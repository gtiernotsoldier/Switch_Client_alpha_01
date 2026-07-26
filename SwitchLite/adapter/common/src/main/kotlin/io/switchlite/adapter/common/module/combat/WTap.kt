package io.switchlite.adapter.common.module.combat

import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.strategy.combat.CombatTrigger
import io.switchlite.core.strategy.tap.TapStateMachine
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.choices
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.option.int
import io.switchlite.adapter.common.option.triggerOptions
import kotlin.random.Random

/**
 * WTap Module — sprint-reset via brief W-key release during combat.
 *
 * Releases the forward key (W) for [actionTicks] milliseconds after landing
 * an attack, then re-presses it. This resets the player's sprint state,
 * allowing knockback to function at full strength on the next hit.
 *
 * State machine managed by Core [TapStateMachine].
 * Only activates while the player is holding W ([PlayerState.isMovingForward]).
 */
object WTap : Module("WTap", Category.COMBAT) {

    // ========== Version Selection ==========
    var combatVersion by choices("CombatVersion", arrayOf("1.8", "1.9+"))

    /** Provider for the player's attack cooldown (0.0–1.0), injected by 1.9+ adapter. */
    var attackCooldownProvider: (() -> Float) = { 1.0f }

    // ========== Shared Configuration ==========
    private val chance by int("Chance", 100, 0..100, "%")
    private val rangeMin by float("RangeMin", 0.0f, 0.0f..6.0f, "blocks")
    private val rangeMax by float("RangeMax", 3.0f, 0.0f..6.0f, "blocks")
    private val onlyPlayers by boolean("OnlyPlayers", true)

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
    private val machine = TapStateMachine()
    private var hitCounter: Int = 0
    private var hitThreshold: Int = 1

    // ========== Tick Listener ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onTick(p, t)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        val now = System.nanoTime()

        // ---- State machine events ----
        when (machine.tick(now)) {
            TapStateMachine.Event.END_TAP -> EventBridge.pressForward()
            TapStateMachine.Event.SHOULD_START_TAP -> startTap(now)
            TapStateMachine.Event.NONE -> {}
        }

        // Only evaluate trigger in IDLE phase
        if (machine.phase != TapStateMachine.Phase.IDLE) return

        // Guard: W must be held
        if (!player.isMovingForward) { hitCounter = 0; return }

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
        if (!ConditionChecker.check(triggerOptions, player, target)) return

        val eval = CombatTrigger.evaluate(
            mode = if (eventType == "PRE") CombatTrigger.Mode.PRE else CombatTrigger.Mode.POST,
            target = target,
            maxHurtTime = player.maxHurtResistantTime,
            hitCounter = hitCounter,
            hitThreshold = hitThreshold,
            hitPerMin = onceEveryMin,
            hitPerMax = onceEveryMax,
            chance = chance
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
    // 1.9+ — Cooldown-based triggering
    // ================================================================
    private fun evaluate19(player: PlayerState, target: TargetState, now: Long) {
        if (!EventBridge.isLeftMousePhysicallyDown) return

        val cooldown = attackCooldownProvider()
        if (cooldown < cooldownThreshold) return

        if (!ConditionChecker.check(triggerOptions, player, target)) return

        if (chance < 100 && Random.nextInt(100) >= chance) return

        startTap(now)
    }

    // ================================================================
    // Execution
    // ================================================================
    private fun startTap(nowNs: Long) {
        EventBridge.releaseForward()
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
        hitThreshold = Random.nextInt(onceEveryMin, onceEveryMax + 1).coerceAtLeast(1)
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        if (machine.phase == TapStateMachine.Phase.TAPPING) {
            EventBridge.pressForward()
        }
        machine.reset()
        hitCounter = 0
    }
}
