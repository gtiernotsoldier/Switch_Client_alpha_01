package io.switchlite.adapter.common.module.combat

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.strategy.click.WeaponType
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.choices
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.option.int
import kotlin.random.Random

/**
 * AutoBlock Module — 1.8 exclusive (sword blocking).
 *
 * Automates the sword block (right-click) timing during combat.
 * Two modes:
 *
 * **Normal**: On attack, press right-click to block for [delayMs], then release.
 *
 * **Switch**: If already blocking when attack fires, release right-click first
 * (allowing full attack animation), wait [delayMs], then re-press to resume blocking.
 *
 * Conditions:
 * - Player must hold a sword ([WeaponType.SWORD]).
 * - Target within configured distance range.
 * - Optional "only current view" check.
 * - Probability-based activation.
 *
 * On disable: automatically releases right-click to prevent stuck key.
 */
object AutoBlock : Module("AutoBlock", Category.COMBAT) {

    // ========== Mode ==========
    private val mode by choices("Mode", arrayOf("Normal", "Switch"))

    // ========== Distance Range ==========
    private val maxDistance by float("MaxDistance", 3.0f, 0.0f..6.0f, "blocks")
    private val minDistance by float("MinDistance", 0.0f, 0.0f..6.0f, "blocks")

    // ========== Probability ==========
    private val probability by int("Chance", 100, 0..100, "%")

    // ========== Timing ==========
    private val delayMs by int("Delay", 50, 1..500, "ms")

    // ========== Conditions ==========
    private val onlyCurrentView by boolean("OnlyCurrentView", false)

    // ========== Internal State ==========

    /** Whether AutoBlock pressed the right-click (not the player). */
    private var blockHeld: Boolean = false

    /** nanoTime when blocking started (Normal mode countdown). */
    private var blockStartNano: Long = 0L

    /** Whether we are in the Switch-mode "wait before re-blocking" phase. */
    private var reblockPending: Boolean = false

    /** nanoTime when Switch release happened; re-block after [delayMs]. */
    private var reblockStartNano: Long = 0L

    /** Previous tick's attack key state (for rising-edge detection). */
    private var wasAttacking: Boolean = false

    // ========== Tick Listener ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onTick(p, t)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        // ---------- Platform-level guards ----------
        // 1.8 exclusive: only SWORD matters (1.9+ has shields, no sword block)
        if (player.weaponType != WeaponType.SWORD) return

        // ---------- Switch re-block timer ----------
        if (reblockPending) {
            if (elapsedNs(reblockStartNano) >= delayMs * 1_000_000L) {
                EventBridge.syntheticUse = true
                blockHeld = true
                reblockPending = false
            }
            // While waiting for re-block, don't process new attacks
            return
        }

        // ---------- Normal mode block-release timer ----------
        if (blockHeld && mode == "Normal") {
            if (elapsedNs(blockStartNano) >= delayMs * 1_000_000L) {
                releaseBlock()
            }
            return
        }

        // ---------- Attack detection (rising edge) ----------
        val isAttacking = player.isAttackKeyDown
        val attackJustStarted = isAttacking && !wasAttacking
        wasAttacking = isAttacking

        if (!attackJustStarted) return

        // ---------- Condition checks ----------
        if (target == null) return

        // Distance range
        if (target.distance < minDistance || target.distance > maxDistance) return

        // Only current view
        if (onlyCurrentView && !player.isLookingAtTarget) return

        // Probability roll
        if (probability < 100 && Random.nextInt(100) >= probability) return

        // ---------- Mode-specific behaviour ----------
        when (mode) {
            "Normal" -> {
                EventBridge.syntheticUse = true
                blockHeld = true
                blockStartNano = System.nanoTime()
            }
            "Switch" -> {
                if (blockHeld) {
                    // Currently blocking → release for attack, then re-block
                    releaseBlock()
                    reblockPending = true
                    reblockStartNano = System.nanoTime()
                } else {
                    // Not blocking → start blocking now
                    EventBridge.syntheticUse = true
                    blockHeld = true
                }
            }
        }
    }

    // ========== Helpers ==========

    /**
     * Release the right-click.
     * Only releases if we were the ones who pressed it AND the player is not
     * physically holding the right mouse button (prevents stealing the player's manual block).
     */
    private fun releaseBlock() {
        if (!blockHeld) return
        if (EventBridge.isRightMousePhysicallyDown) return  // player is manually holding
        EventBridge.syntheticUse = false
        blockHeld = false
    }

    private fun elapsedNs(startNano: Long): Long =
        System.nanoTime() - startNano

    // ========== Lifecycle ==========

    override fun onEnable() {
        EventBridge.syntheticUseOverride = true
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        releaseBlock()
        EventBridge.syntheticUse = false
        EventBridge.syntheticUseOverride = false
        reblockPending = false
        wasAttacking = false
    }
}
