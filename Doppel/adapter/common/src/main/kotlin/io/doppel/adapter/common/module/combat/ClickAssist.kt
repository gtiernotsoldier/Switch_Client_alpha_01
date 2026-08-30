package io.doppel.adapter.common.module.combat

import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.core.strategy.click.WeaponType
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.int
import kotlin.math.atan2
import kotlin.random.Random

/**
 * ClickAssist Module — simulates extra clicks on player input.
 *
 * Three modes:
 *   Normal — 1 extra click per real click (double-click effect)
 *   Double — 2 extra clicks per real click (triple-click)
 *   Random — 0–2 extra clicks randomly
 *
 * Configurable CPS interval between extra clicks, probability per trigger,
 * and filters (weapons only, aiming at entity, looking at block, manual CPS floor).
 *
 * Only responds to PHYSICAL mouse button edges (via EventBridge physical button
 * state), avoiding cross-talk with AutoClicker's synthetic key bind presses.
 */
object ClickAssist : Module("ClickAssist", Category.COMBAT) {

    // ========== Core ==========
    private val mode by choices("Mode", arrayOf("Normal", "Double", "Random"))
    private val cps by int("CPS", 10, 1..20, "cps")
    private val probability by int("Chance", 80, 0..100, "%")

    // ========== Filters ==========
    private val leftClickEnabled by boolean("LeftClick", true)
    private val rightClickEnabled by boolean("RightClick", false)
    private val weaponsOnly by boolean("WeaponsOnly", true)
    private val onlyAimingEntity by boolean("OnlyAimingEntity", false)
    private val onlyBlocks by boolean("OnlyBlocks", true)
    private val above5Cps by boolean("Above5CPS", false)

    // ========== Internal State ==========

    /** Nano-time interval between extra clicks: 1_000_000_000 / CPS */
    private val intervalNs: Long get() = 1_000_000_000L / cps.coerceAtLeast(1)

    /** Pending extra click count. */
    private var scheduledClicks: Int = 0

    /** When the next scheduled extra click fires (nanoTime). */
    private var nextClickNs: Long = 0L

    /** Whether the next scheduled click is a right-click (vs left-click). */
    private var nextIsRightClick: Boolean = false

    /** Sliding window of recent click timestamps for manual CPS estimation. */
    private val recentClicks = ArrayDeque<Long>(10)

    /** Previous tick physical button states (for rising-edge detection). */
    private var prevLeftDown: Boolean = false
    private var prevRightDown: Boolean = false

    /**
     * Two-tick right-click state machine:
     * Tick N: pressUseItem → set pending
     * Tick N+1: auto-release via releaseUsingItem
     * MC's block placement is processed in onLivingUpdate() between ticks,
     * so a same-tick press+release would never register.
     */
    private var rightExtraPressed: Boolean = false

    // ========== Tick Listener ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onTick(p, t)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        // ---- Trigger condition: player in game, no GUI, not eating/blocking ----
        // In-game check: health > 0 implies the player entity exists
        if (player.health <= 0f) {
            cleanup()
            return
        }
        if (player.isUsingItem || player.isBlocking) {
            cleanup()
            return
        }
        // GUI check: handled by ForgeBootstrap/FabricBootstrap via EventBridge
        // (not blocking extra clicks during GUI because tick events still fire)

        // ---- Process scheduled extra clicks ----
        // Phase 1: release pending right-click from previous tick
        if (rightExtraPressed) {
            EventBridge.syntheticUse = false
            rightExtraPressed = false
            // If there are more scheduled clicks, the next one fires immediately
            if (scheduledClicks > 0) {
                val isRight = nextIsRightClick
                fireExtraClick()
                scheduledClicks--
                if (scheduledClicks > 0) {
                    nextClickNs = System.nanoTime() + intervalNs
                }
            }
            // Fall through to edge detection below
        }

        // Phase 2: fire new extra click if timer is due
        if (scheduledClicks > 0 && !rightExtraPressed && System.nanoTime() >= nextClickNs) {
            fireExtraClick()
            scheduledClicks--
            if (scheduledClicks > 0) {
                nextClickNs += intervalNs
            }
        }

        // ---- Physical click edge detection ----
        val leftEdge = EventBridge.isLeftMousePhysicallyDown && !prevLeftDown
        val rightEdge = EventBridge.isRightMousePhysicallyDown && !prevRightDown
        prevLeftDown = EventBridge.isLeftMousePhysicallyDown
        prevRightDown = EventBridge.isRightMousePhysicallyDown

        val now = System.nanoTime()

        // ---- Left-click handling ----
        if (leftEdge && leftClickEnabled) {
            recentClicks.addLast(now)
            pruneOldClicks(now)

            // Filter: weapons only
            if (weaponsOnly) {
                val wt = player.weaponType
                if (wt != WeaponType.SWORD && wt != WeaponType.AXE) {
                    // fall through — skip scheduling but allow right-click check
                } else checkedSchedule(now, player, target, isRightClick = false)
            } else {
                checkedSchedule(now, player, target, isRightClick = false)
            }
        }

        // ---- Right-click handling ----
        if (rightEdge && rightClickEnabled) {
            recentClicks.addLast(now)
            pruneOldClicks(now)

            // Filter: only blocks
            if (onlyBlocks && !EventBridge.isLookingAtBlock) {
                // skip
            } else checkedSchedule(now, player, target, isRightClick = true)
        }
    }

    private fun checkedSchedule(nowNs: Long, player: PlayerState, target: TargetState?, isRightClick: Boolean) {
        // Filter: only aiming at entity — angle-based check (player.isLookingAtTarget is
        // hardcoded false in StateExtractor; crosshair raycast is adapter-level only).
        if (onlyAimingEntity && target == null && !isLookingAtTarget(player, target)) return

        // Filter: above 5 CPS (applies to both left and right)
        if (above5Cps && estimateCps(nowNs) < 5f) return

        // Probability roll
        if (probability < 100 && Random.nextInt(100) >= probability) return

        val extraCount = when (mode) {
            "Normal" -> 1
            "Double" -> 2
            "Random" -> Random.nextInt(3) // 0, 1, or 2
            else -> 1
        }

        if (extraCount == 0) return

        // Interleave: if already have pending clicks, queue after them
        if (scheduledClicks > 0) {
            scheduledClicks += extraCount
        } else {
            scheduledClicks = extraCount
            nextClickNs = nowNs
            nextIsRightClick = isRightClick
        }
    }

    /** Fire one extra click (left or right). Right-click spans two ticks. */
    private fun fireExtraClick() {
        if (nextIsRightClick) {
            EventBridge.syntheticUse = true
            rightExtraPressed = true  // release happens next tick
        } else {
            EventBridge.syntheticAttack = true
        }
    }

    // ========== CPS Estimation ==========

    /** Prune click timestamps older than 500ms from the sliding window. */
    private fun pruneOldClicks(nowNs: Long) {
        val cutoff = nowNs - 500_000_000L
        while (recentClicks.isNotEmpty() && recentClicks.first() < cutoff) {
            recentClicks.removeFirst()
        }
    }

    /** Estimate manual CPS from the sliding window. */
    private fun estimateCps(nowNs: Long): Float {
        if (recentClicks.size < 2) return 0f
        val windowNs = nowNs - recentClicks.first()
        if (windowNs <= 0) return 0f
        return (recentClicks.size - 1).toFloat() / (windowNs / 1_000_000_000f)
    }

    // ========== Helpers ==========

    /**
     * Angle-based "looking at target" check.
     * Computes horizontal angular difference between player's yaw and the
     * direction to target. A 30° threshold is used (same as ConditionChecker default).
     */
    private fun isLookingAtTarget(player: PlayerState, target: TargetState?): Boolean {
        if (target == null) return false
        val dx = target.position.x - player.position.x
        val dz = target.position.z - player.position.z
        if (dx == 0.0 && dz == 0.0) return true
        val yawToTarget = Math.toDegrees(atan2(-dx, dz)).toFloat()
        var diff = player.rotation.yaw - yawToTarget
        diff = ((diff + 180f) % 360f + 360f) % 360f - 180f
        return kotlin.math.abs(diff) <= 30f
    }

    // ========== Lifecycle ==========

    override fun onEnable() {
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        cleanup()
    }

    private fun cleanup() {
        if (rightExtraPressed) {
            EventBridge.syntheticUse = false
            rightExtraPressed = false
        }
        scheduledClicks = 0
        recentClicks.clear()
        prevLeftDown = false
        prevRightDown = false
    }
}
