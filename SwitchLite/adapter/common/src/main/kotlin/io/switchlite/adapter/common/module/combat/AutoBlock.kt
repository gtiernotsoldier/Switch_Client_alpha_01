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
 * **Normal**: While AutoClicker is working (or the player physically left-clicks), press
 * right-click to block for [delayMs], then release — re-engages each hit.
 *
 * **Switch**: If already blocking when a left-click attack fires, release right-click first
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
    // Normal = click-style: while AutoClicker / physical left-click is working, block for
    //          [delayMs] then release (re-engages each hit).
    // Switch = if already blocking when a fresh attack fires, release briefly (so the
    //          attack animation plays out), wait [delayMs], then re-block.
    // Srg    = hold-style: block for as long as the player keeps attacking / holding.
    private val mode by choices("Mode", arrayOf("Normal", "Switch", "Srg"))

    // ========== Distance Range ==========
    private val maxDistance by float("MaxDistance", 3.0f, 0.0f..6.0f, "blocks")
    private val minDistance by float("MinDistance", 0.0f, 0.0f..6.0f, "blocks")

    // ========== Probability ==========
    private val probability by int("Chance", 100, 0..100, "%")

    // ========== Timing ==========
    private val delayMs by int("Delay", 50, 1..500, "ms")

    // ========== Conditions ==========
    private val onlyCurrentView by boolean("OnlyCurrentView", false)

    /** [Switch] mode only: only perform the block-hit switch while the player holds right-click. */
    private val switchOnRightHold by boolean("SwitchOnRightHold", true)

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

        // Switch-mode re-block timer: if we're waiting to re-block, finish it now.
        if (reblockPending) {
            if (elapsedNs(reblockStartNano) >= delayMs * 1_000_000L) {
                EventBridge.syntheticUse = true
                blockHeld = true
                reblockPending = false
            }
            // While waiting to re-block, don't process new attack logic.
            return
        }

        // ---------- Attack detection (AutoClick working + physical click) ----------
        // Effective attack: AutoClicker running (mouseButton0 reflects its cadence pulses on
        // the render thread) OR the physical left button held. mouseButton0 already folds the
        // synthetic clicks in, and isAttackKeyDown is the raw physical left button.
        val isAttacking = EventBridge.mouseButton0 || player.isAttackKeyDown
        val attackJustStarted = isAttacking && !wasAttacking
        wasAttacking = isAttacking

        // Condition checks — gate blocking only while attacking; otherwise release.
        var shouldBlock = isAttacking
        if (isAttacking) {
            if (target != null && (target.distance < minDistance || target.distance > maxDistance)) {
                shouldBlock = false
            }
            if (shouldBlock && onlyCurrentView && !player.isLookingAtTarget) shouldBlock = false
            if (shouldBlock && probability < 100 && Random.nextInt(100) >= probability) shouldBlock = false
        }

        when (mode) {
            // ── Srg: hold-style — block for as long as the player keeps attacking/holding.
            "Srg" -> {
                if (shouldBlock && !blockHeld) {
                    EventBridge.syntheticUse = true
                    blockHeld = true
                } else if (!shouldBlock && blockHeld) {
                    releaseBlock()
                }
            }

            // ── Switch: if already blocking when a fresh attack fires, release briefly so the
            // attack animation plays out, then re-block after delayMs. Only performs the switch
            // while the player holds right-click (SwitchOnRightHold), matching the block-hit
            // technique where the player keeps right-click held.
            "Switch" -> {
                val rightHeld = !switchOnRightHold || EventBridge.isRightMousePhysicallyDown
                if (!rightHeld) {
                    if (blockHeld) releaseBlock()
                } else if (blockHeld) {
                    if (attackJustStarted && shouldBlock) {
                        // Fresh attack while blocking → release now, re-block shortly.
                        releaseBlock()
                        reblockPending = true
                        reblockStartNano = System.nanoTime()
                    }
                    // Otherwise keep blocking.
                } else if (attackJustStarted && shouldBlock) {
                    // Not blocking → start blocking.
                    EventBridge.syntheticUse = true
                    blockHeld = true
                }
            }

            // ── Normal: click-style — block while AutoClicker is working (mouseButton0 cadence)
            // or the player is physically left-clicking. Hold the block for [delayMs], then
            // release; re-engage on the next attack so it blocks each hit.
            else -> {
                if (blockHeld) {
                    if (elapsedNs(blockStartNano) >= delayMs * 1_000_000L) {
                        releaseBlock()
                    }
                } else if (shouldBlock) {
                    EventBridge.syntheticUse = true
                    blockHeld = true
                    blockStartNano = System.nanoTime()
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
