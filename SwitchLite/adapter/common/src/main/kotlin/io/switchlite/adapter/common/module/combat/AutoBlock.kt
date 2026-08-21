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
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p, EventBridge.crosshairTarget)
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

        // ---------- Attack detection ----------
        // Block while actively attacking: AutoClicker running (mouseButton0 reflects its
        // cadence pulses on the render thread) OR the physical left button held. When not
        // attacking, stop blocking. This makes AutoBlock hold the block for as long as the
        // player/AutoClicker keeps attacking (the requested linkage).
        val isAttacking = EventBridge.mouseButton0 || player.isAttackKeyDown

        // Condition checks (only gate while attacking; otherwise just release).
        // AutoBlock is relaxed: it blocks whenever the player is attacking with a sword,
        // regardless of whether the crosshair is on an entity (works in tight spaces / pits).
        // target is only used as an OPTIONAL filter — distance range applies only when a
        // target is present, and onlyCurrentView (if enabled) requires the crosshair to be on it.
        var shouldBlock = isAttacking
        if (isAttacking) {
            if (target != null) {
                if (target.distance < minDistance || target.distance > maxDistance) shouldBlock = false
            }
            if (shouldBlock && onlyCurrentView && !player.isLookingAtTarget) shouldBlock = false
            if (shouldBlock && probability < 100 && Random.nextInt(100) >= probability) shouldBlock = false
        }

        if (shouldBlock && !blockHeld) {
            // Start blocking now.
            EventBridge.syntheticUse = true
            blockHeld = true
            if (mode == "Normal") {
                // In Normal mode we keep blocking while attacking; Switch re-blocks after a hit.
                blockStartNano = System.nanoTime()
            }
        } else if (!shouldBlock && blockHeld && mode != "Switch") {
            // No longer attacking → stop blocking (Normal mode).
            releaseBlock()
        } else if (!shouldBlock && blockHeld && mode == "Switch") {
            // Switch mode: release only if we started the block; but here we keep it simple
            // and release when no longer attacking.
            releaseBlock()
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
        // Always clear the synthetic use state. The assist path ORs it with the physical
        // right button, so clearing syntheticUse never cancels the player's own manual
        // block — it only stops our synthetic block. The old `if (isRightMousePhysicallyDown)
        // return` kept syntheticUse=true and stuck the player blocking after release.
        EventBridge.syntheticUse = false
        blockHeld = false
    }

    private fun elapsedNs(startNano: Long): Long =
        System.nanoTime() - startNano

    // ========== Lifecycle ==========

    override fun onEnable() {
        // No syntheticUseOverride: AutoBlock ADDS its block on top of the player's own
        // right-click (Raven style). Overriding the whole use key would swallow the
        // player's manual blocking.
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        releaseBlock()
        EventBridge.syntheticUse = false
        reblockPending = false
        wasAttacking = false
    }
}
