package io.switchlite.adapter.common.module.combat

import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.choices
import io.switchlite.adapter.common.option.int
import io.switchlite.adapter.common.option.triggerOptions
import kotlin.random.Random

/**
 * HitSelect — smart attack timing controller.
 *
 * Defaults to blocking attacks, then opens through gates in priority order:
 *   probability → preference → delay force-allow.
 *
 * Preferences:
 *   MoveSpeed:    only attack when on ground & moving (preserves momentum).
 *   KBReduction:   skip attacks during knockback frames.
 *   CriticalHits:  only attack while falling (crit window).
 *
 * Recommended: Pause mode, MoveSpeed preference, 420ms delay, 80% chance.
 */
object HitSelect : Module("HitSelect", Category.COMBAT) {

    // ========== Core ==========
    private val mode by choices("Mode", arrayOf("Pause", "Active"))
    private val preference by choices("Preference", arrayOf("MoveSpeed", "KBReduction", "CriticalHits"))
    private val delay by int("Delay", 420, 300..500, "ms")
    private val chance by int("Chance", 80, 0..100, "%")
    private val tick by int("Tick", 1, 1..20, "ticks")

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

    // ========== StartTick Listener ==========
    private val startListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onStartTick(p, t)
    }

    private fun onStartTick(player: PlayerState, target: TargetState?) {
        tickCount++

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
            "KBReduction" -> player.hurtTime != player.maxHurtResistantTime
            // CriticalHits: mutually exclusive with onlyGround condition —
            // disable OnlyGround in the trigger panel when using this preference.
            "CriticalHits" -> player.motionY < 0.0 && !player.onGround
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
        EventBridge.registerStartTickListener(startListener)
    }

    override fun onDisable() {
        EventBridge.unregisterStartTickListener(startListener)
    }
}
