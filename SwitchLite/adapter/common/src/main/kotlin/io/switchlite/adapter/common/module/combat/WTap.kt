package io.switchlite.adapter.common.module.combat

import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
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
 * Supports two version paths:
 * - **1.8**: POST/PRE mode with configurable hit counting, postDelay.
 * - **1.9+**: Cooldown-based triggering with [attackCooldownProvider].
 *
 * Only activates while the player is holding W ([PlayerState.isMovingForward]).
 */
object WTap : Module("WTap", Category.COMBAT) {

    // ========== Version Selection ==========
    var combatVersion by choices("CombatVersion", arrayOf("1.8", "1.9+"))

    /** Provider for the player's attack cooldown (0.0–1.0), injected by 1.9+ adapter. */
    var attackCooldownProvider: (() -> Float) = { 1.0f }

    // ========== Shared Configuration ==========
    private val chance by int("Chance", 100, 0..100, "%")
    private val range by float("Range", 3.0f, 0.0f..6.0f, "blocks")
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
    private val actionTicksMin18 by int("ActionMsMin18", 25, 1..500, "ms")
    private val actionTicksMax18 by int("ActionMsMax18", 55, 1..500, "ms")
    private val onceEveryMin by int("OnceEveryMin", 1, 1..10)
    private val onceEveryMax by int("OnceEveryMax", 1, 1..10)
    private val postDelayMin by int("PostDelayMin", 25, 0..500, "ms")
    private val postDelayMax by int("PostDelayMax", 55, 0..500, "ms")

    // ========== 1.9+ Configuration ==========
    private val cooldownThreshold by float("CooldownThreshold", 1.0f, 0.5f..1.0f, "%")
    private val actionTicksMin19 by int("ActionMsMin19", 15, 1..500, "ms")
    private val actionTicksMax19 by int("ActionMsMax19", 30, 1..500, "ms")

    // ========== State Machine ==========
    private enum class Phase { IDLE, POST_DELAY, TAPPING }

    private var phase: Phase = Phase.IDLE
    private var tapEndNano: Long = 0L
    private var postDelayEndNano: Long = 0L
    private var hitCounter: Int = 0
    private var hitThreshold: Int = 1

    // ========== Tick Listener ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onTick(p, t)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        val now = System.nanoTime()

        // ---- Phase: TAPPING (W released, waiting to re-press) ----
        if (phase == Phase.TAPPING) {
            if (now >= tapEndNano) {
                EventBridge.pressForward()
                phase = Phase.IDLE
            }
            return
        }

        // ---- Phase: POST_DELAY (1.8 only, waiting before tap) ----
        if (phase == Phase.POST_DELAY) {
            if (now >= postDelayEndNano) {
                startTap(now)
            }
            return
        }

        // ---- Phase: IDLE — evaluate trigger ----

        // Guard: W must be held
        if (!player.isMovingForward) { hitCounter = 0; return }

        // Target guards
        if (target == null || target.distance > range) { hitCounter = 0; return }
        if (target.health <= 0f) { hitCounter = 0; return }
        if (onlyPlayers && target.name.isEmpty()) { hitCounter = 0; return }

        // ---- Route by version ----
        when (combatVersion) {
            "1.8" -> evaluate18(player, target, now)
            "1.9+" -> evaluate19(player, target, now)
        }
    }

    // ================================================================
    // 1.8 Logic
    // ================================================================
    private fun evaluate18(player: PlayerState, target: TargetState, now: Long) {
        val maxHurt = player.maxHurtResistantTime

        // Mode-specific hurt-time check
        val conditionMet = when (eventType) {
            "POST" -> target.hurtTime >= maxHurt
            "PRE"  -> target.hurtTime <  maxHurt
            else   -> target.hurtTime >= maxHurt
        }
        if (!conditionMet) return

        // Unified conditions
        if (!ConditionChecker.check(triggerOptions, player, target)) return

        // Probability
        if (chance < 100 && Random.nextInt(100) >= chance) return

        // Attack counting
        hitCounter++
        if (hitCounter < hitThreshold) return
        hitCounter = 0
        hitThreshold = Random.nextInt(onceEveryMin, onceEveryMax + 1).coerceAtLeast(1)

        // Execute
        val pd = Random.nextInt(postDelayMin, postDelayMax + 1)
        if (pd > 0) {
            postDelayEndNano = now + pd * 1_000_000L
            phase = Phase.POST_DELAY
        } else {
            startTap(now)
        }
    }

    // ================================================================
    // 1.9+ Logic
    // ================================================================
    private fun evaluate19(player: PlayerState, target: TargetState, now: Long) {
        // Must have a target in crosshair
        if (!EventBridge.isLeftMousePhysicallyDown) return

        // Cooldown check
        val cooldown = attackCooldownProvider()
        if (cooldown < cooldownThreshold) return

        // Unified conditions
        if (!ConditionChecker.check(triggerOptions, player, target)) return

        // Probability
        if (chance < 100 && Random.nextInt(100) >= chance) return

        // 1.9+: fire on every attack (postDelay = 0)
        startTap(now)
    }

    // ================================================================
    // Execution
    // ================================================================
    private fun startTap(nowNs: Long) {
        EventBridge.releaseForward()
        phase = Phase.TAPPING

        val ms = when (combatVersion) {
            "1.8" -> Random.nextInt(actionTicksMin18, actionTicksMax18 + 1)
            "1.9+" -> Random.nextInt(actionTicksMin19, actionTicksMax19 + 1)
            else -> 40
        }
        tapEndNano = nowNs + ms * 1_000_000L
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        hitCounter = 0
        hitThreshold = Random.nextInt(onceEveryMin, onceEveryMax + 1).coerceAtLeast(1)
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        // Re-press W if we had it released (prevents stuck key)
        if (phase == Phase.TAPPING) {
            EventBridge.pressForward()
        }
        phase = Phase.IDLE
        hitCounter = 0
    }
}
