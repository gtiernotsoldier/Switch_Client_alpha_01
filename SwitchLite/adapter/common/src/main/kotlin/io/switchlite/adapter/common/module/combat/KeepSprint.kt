package io.switchlite.adapter.common.module.combat

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.*
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.strategy.keepsprint.KeepSprintConfig
import io.switchlite.core.strategy.keepsprint.KeepSprintInput
import io.switchlite.core.strategy.keepsprint.KeepSprintState
import io.switchlite.core.strategy.keepsprint.KeepSprintStrategy

/**
 * KeepSprint — maintains sprint speed when attacking.
 *
 * In vanilla Minecraft, attacking while sprinting reduces horizontal speed
 * to 60% and cancels the sprint state. This module detects post-attack
 * slowdown and restores sprint to a configurable percentage.
 *
 * Modes:
 * - Normal: always restore to the configured horizontal keep percentage.
 * - Legit: interpolate keep percentage based on distance to target —
 *   closer targets get less aggressive restoration (looks more natural).
 *
 * Constitution compliance:
 * - S1 Safety: only modifies sprint state, never sends packets or touches health.
 * - S2 Debuggability: logs every restore decision at DEBUG level.
 * - S3 Strategy: Normal/Legit selectable, all thresholds configurable.
 */
object KeepSprint : Module("KeepSprint", Category.COMBAT) {

    // ========== Mode ==========
    private val mode by choices("Mode", arrayOf("Normal", "Legit"))

    // ========== Speed ==========
    private val horizontalKeep by float("HorizontalKeep", 1.0f, 0.6f..1.0f)

    // ========== Legit Mode: Distance-based interpolation ==========
    private val minReach by float("MinReach", 1.0f, 0f..1.5f, "blocks")
    private val maxReach by float("MaxReach", 3.0f, 2.5f..3.0f, "blocks")
    private val minKeep by float("MinKeep", 0.65f, 0.6f..0.7f)
    private val maxKeep by float("MaxKeep", 0.85f, 0.7f..0.95f)

    // ========== Probability ==========
    private val chance by probability("Chance", 100, 0..100)

    // ========== Conditions ==========
    private val hurtTimeMax by int("HurtTime", 10, 1..10)

    // ========== Unified Condition Engine ==========
    // onlyGround default ON: sprint restore only triggers on ground.
    // Disable if using KeepSprint with crits (in-air hits).
    private val onlyGround by boolean("OnlyGround", true)
    private val onlyMove by boolean("OnlyMove", false)
    private val onlyMoveForward by boolean("OnlyMoveForward", false)
    private val onlyWhenTargetGoesBack by boolean("OnlyWhenTargetGoesBack", false)

    private val triggerOptions by triggerOptions("Trigger") {
        onlyGround = this@KeepSprint.onlyGround
        onlyMove = this@KeepSprint.onlyMove
        onlyMoveForward = this@KeepSprint.onlyMoveForward
        onlyWhenTargetGoesBack = this@KeepSprint.onlyWhenTargetGoesBack
    }

    // ========== Timing ==========
    private val delayTicks by int("Delay", 0, 1..20, "ticks")
    private val cooldownTicks by int("Cooldown", 1, 1..20, "ticks")

    // ========== Internal State ==========
    private val strategyState = KeepSprintState()
    private var prevSprinting = false
    private var sprintCancelledTick: Int? = null
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t -> onTick(p, t) }

    // ========== Config Snapshot ==========
    private fun buildConfig(): KeepSprintConfig {
        return KeepSprintConfig(
            mode = mode,
            horizontalKeep = horizontalKeep,
            minReach = minReach,
            maxReach = maxReach,
            minKeep = minKeep,
            maxKeep = maxKeep,
            chance = chance.current,
            hurtTimeMax = hurtTimeMax,
            delayTicks = delayTicks,
            cooldownTicks = cooldownTicks
        )
    }

    /**
     * Build strategy input from current player/target state.
     */
    private fun buildInput(player: PlayerState, target: TargetState?): KeepSprintInput {
        val now = EventBridge.getCurrentTick()
        if (prevSprinting && !player.isSprinting && player.isAttackKeyDown) {
            sprintCancelledTick = now
        }
        // Expire window if older than 10 ticks
        if (sprintCancelledTick != null && now - sprintCancelledTick!! > 10) {
            sprintCancelledTick = null
        }
        prevSprinting = player.isSprinting
        return KeepSprintInput(
            sprintCancelledTick = sprintCancelledTick,
            targetHurtTime = target?.hurtTime,
            targetDistance = target?.distance,
            currentTick = now,
            motionX = player.motionX,
            motionY = player.motionY,
            motionZ = player.motionZ
        )
    }

    /**
     * Apply restore: set sprint flag and apply motion computed by core strategy.
     */
    private fun applyRestore(result: io.switchlite.core.strategy.keepsprint.KeepSprintResult.Restore) {
        EventBridge.setSprinting(true)
        result.motion?.let { EventBridge.applyMotion(it) }
    }

    // ========== Tick Entry ==========
    fun onTick(player: PlayerState, target: TargetState?) {
        if (!enabled) return

        // Track sprint state FIRST (before condition gate) — must update
        // prevSprinting and sprintCancelledTick every tick regardless of
        // conditions, otherwise a jump/crit scenario leaves stale state.
        val config = cachedConfig { buildConfig() }
        val input = buildInput(player, target)

        // Unified condition check
        if (!ConditionChecker.check(triggerOptions, player, target)) return

        val result = KeepSprintStrategy.execute(config, strategyState, input)

        when (result) {
            is io.switchlite.core.strategy.keepsprint.KeepSprintResult.Restore -> {
                applyRestore(result)
                io.switchlite.core.logging.CoreLogger.debug(
                    "[KeepSprint] Restored sprint at ${"%.0f".format(result.keepPercentage * 100)}%"
                )
            }
            is io.switchlite.core.strategy.keepsprint.KeepSprintResult.DelayedRestore -> {
                io.switchlite.core.logging.CoreLogger.debug(
                    "[KeepSprint] Delayed restore queued: ${"%.0f".format(result.keepPercentage * 100)}% in ${result.releaseTick - input.currentTick} ticks"
                )
            }
            is io.switchlite.core.strategy.keepsprint.KeepSprintResult.Pass -> { /* no-op */ }
        }
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        strategyState.reset()
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        strategyState.reset()
        prevSprinting = false
        sprintCancelledTick = null
        EventBridge.unregisterTickListener(tickListener)
    }
}
