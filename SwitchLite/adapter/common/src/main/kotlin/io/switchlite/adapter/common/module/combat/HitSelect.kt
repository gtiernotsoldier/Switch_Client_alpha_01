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
 * HitSelect — smart click selector (decides whether THIS attack should fire).
 *
 * Two core rules (both on by default), evaluated every background tick:
 *
 * 1. CounterHit — when the player is within attack range, NOT currently clicking, and just got
 *    hit (hurtTime > 0), fire an automatic attack once. The core "eat a hit then hit back" trade:
 *    you were knocked back, so land a counter-attack instead of doing nothing.
 *
 * 2. Retiming — during a sword fight the player sometimes clicks while the target is still in its
 *    i-frame window (hurtResistantTime > 0 → the hit deals no damage, a wasted click). Cancel
 *    those wasted clicks and let the click through only when the target's i-frame window is about
 *    to end (hurtResistantTime <= RetimeAt) — so the re-timed click lands exactly as the target
 *    becomes hittable again.
 *
 * The original preference/chance/delay gates remain as the fallback path for when neither core
 * rule applies.
 */
object HitSelect : Module("HitSelect", Category.COMBAT) {

    // ========== Core (original) ==========
    private val mode by choices("Mode", arrayOf("Pause", "Active"))
    private val preference by choices("Preference", arrayOf("MoveSpeed", "KBReduction", "CriticalHits"))
    private val delay by int("Delay", 420, 300..500, "ms")
    private val chance by int("Chance", 80, 0..100, "%")
    private val tick by int("Tick", 1, 1..20, "ticks")

    // ========== Rule 1: CounterHit (eat a hit → auto hit back) ==========
    private val counterHit by boolean("CounterHit", true)
    private val counterRange by float("CounterRange", 3.0f, 0.0f..6.0f, "blocks")
    private val counterCdMs by int("CounterCD", 300, 0..1000, "ms")

    // ========== Rule 2: Retiming (cancel clicks inside the target's i-frame window) ==========
    private val retiming by boolean("Retiming", true)
    private val retimeRange by float("RetimeRange", 3.0f, 0.0f..6.0f, "blocks")
    /** Target is considered "about to become hittable" when hurtResistantTime <= this. */
    private val retimeAt by int("RetimeAt", 3, 0..10, "ticks")

    // ========== Conditions ==========
    private val onlyGround by boolean("OnlyGround", false)
    private val onlyTargeting by boolean("OnlyTargeting", true)
    private val onlyMove by boolean("OnlyMove", true)
    private val onlyMoveForward by boolean("OnlyMoveForward", false)
    private val onlyWhenTargetGoesBack by boolean("OnlyWhenTargetGoesBack", false)

    private val triggerOptions by triggerOptions("Trigger") {
        onlyGround = this@HitSelect.onlyGround
        onlyCurrentView = onlyTargeting
        onlyMove = this@HitSelect.onlyMove
        onlyMoveForward = this@HitSelect.onlyMoveForward
        onlyWhenTargetGoesBack = this@HitSelect.onlyWhenTargetGoesBack
    }

    // ========== State ==========
    private var lastAttackNano: Long = 0L
    private var lastEvalTick: Int = 0
    private var tickCount: Int = 0
    /** Last time Rule 1 fired a counter-attack (cooldown). */
    private var lastCounterNano: Long = 0L
    /** Synthetic counter-attack pulse: set true for one background tick, then cleared. */
    private var counterPulse: Boolean = false

    // ========== StartTick Listener ==========
    private val startListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onStartTick(p, t)
    }

    private fun onStartTick(player: PlayerState, target: TargetState?) {
        tickCount++

        // ---- Rule 1: CounterHit — in range, not clicking, just got hit → auto attack once ----
        if (counterHit && target != null) {
            val inRange = target.distance >= 0f && target.distance <= counterRange
            val notClicking = !EventBridge.isLeftMousePhysicallyDown
            val justHit = player.hurtTime > 0
            val cooled = System.nanoTime() - lastCounterNano >= counterCdMs * 1_000_000L
            if (inRange && notClicking && justHit && cooled) {
                lastCounterNano = System.nanoTime()
                EventBridge.syntheticAttack = true
                counterPulse = true
                return
            }
        }
        // Clear the counter pulse one tick later (unless we re-triggered above).
        if (counterPulse) {
            EventBridge.syntheticAttack = false
            counterPulse = false
        }

        // ---- Rule 2: Retiming — cancel wasted clicks while the target is in its i-frame ----
        if (retiming && target != null) {
            val inRange = target.distance >= 0f && target.distance <= retimeRange
            val stillInvulnerable = target.hurtResistantTime > retimeAt
            if (inRange && stillInvulnerable) {
                cancel()
                return
            }
        }

        // ========== Original fallback path ==========
        // Must be physically clicking
        if (!EventBridge.isLeftMousePhysicallyDown) return

        // Tick gate: haven't passed N ticks since last eval
        if (tickCount - lastEvalTick < tick) {
            cancel()
            return
        }
        lastEvalTick = tickCount

        // Condition gate
        if (!ConditionChecker.check(triggerOptions, player, target)) {
            cancel()
            return
        }

        // Probability gate
        if (Random.nextInt(100) < chance) {
            allow()
            return
        }

        // Preference gate
        if (checkPreference(player, target)) {
            allow()
            return
        }

        // Delay gate: force-allow if enough time passed since last attack
        if (System.nanoTime() - lastAttackNano >= delay * 1_000_000L) {
            allow()
            return
        }

        // No gate passed → cancel
        cancel()
    }

    // ========== Preference Checks ==========
    private fun checkPreference(player: PlayerState, target: TargetState?): Boolean {
        return when (preference) {
            "MoveSpeed" -> player.onGround && player.isMoving
            "KBReduction" -> player.hurtTime == 0
            // CriticalHits: mutually exclusive with onlyGround condition —
            // disable OnlyGround in the trigger panel when using this preference.
            "CriticalHits" -> player.motionY < 0.0 && !player.onGround &&
                player.attackCooldownProgress >= 1.0f
            else -> false
        }
    }

    // ========== Gate Actions ==========
    private fun allow() {
        lastAttackNano = System.nanoTime()
        // Click passes through — game processes attack normally
    }

    private fun cancel() {
        // Active mode: cancel click → game never sees it.
        // Pause mode: let click through but evaluation failed (throttle only).
        if (mode == "Active") EventBridge.cancelAttack()
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        lastAttackNano = System.nanoTime()  // start fresh, delay gate won't fire prematurely
        lastEvalTick = 0
        tickCount = 0
        lastCounterNano = 0L
        counterPulse = false
        EventBridge.syntheticAttack = false
        EventBridge.registerStartTickListener(startListener)
    }

    override fun onDisable() {
        EventBridge.unregisterStartTickListener(startListener)
        EventBridge.syntheticAttack = false
        counterPulse = false
    }
}
