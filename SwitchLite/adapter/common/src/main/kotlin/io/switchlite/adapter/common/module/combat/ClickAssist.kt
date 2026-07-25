package io.switchlite.adapter.common.module.combat

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.strategy.click.WeaponType
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.choices
import io.switchlite.adapter.common.option.int
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
    private val recentClicks = ArrayDeque<Long>(capacity = 10)

    /** Previous tick physical button states (for rising-edge detection). */
    private var prevLeftDown: Boolean = false
    private var prevRightDown: Boolean = false

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
        if (scheduledClicks > 0 && System.nanoTime() >= nextClickNs) {
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
        // Filter: only aiming at entity
        if (onlyAimingEntity && target == null && !player.isLookingAtTarget) return

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

    /** Fire one extra click (left or right). */
    private fun fireExtraClick() {
        if (nextIsRightClick) {
            EventBridge.pressUseItem()
            // Right-click release needs a brief delay for the game to register it.
            // The release will be handled by a follow-up scheduled click or cleanup.
            EventBridge.releaseUsingItem()
        } else {
            EventBridge.triggerAttack()
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

    // ========== Lifecycle ==========

    override fun onEnable() {
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        cleanup()
    }

    private fun cleanup() {
        scheduledClicks = 0
        recentClicks.clear()
        prevLeftDown = false
        prevRightDown = false
    }
}
