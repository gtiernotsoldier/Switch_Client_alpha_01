package io.switchlite.adapter.common.module.combat

import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.logging.CoreLogger
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
import io.switchlite.adapter.common.option.triggerOptions
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
 * **Switch**: AutoBlock maintains the block. On each fresh left-click attack (physical left
 * OR AutoClick) it briefly cancels the block (so the hit lands), waits [delayMs], then
 * re-blocks — the block-hit / anti-knockback rhythm. SwitchOnRightHold gates that cancel on
 * the player physically holding right-click.
 *
 * Conditions (unified engine):
 * - OnlyPlane / OnlyTargeting / OnlyMove / OnlyMoveForward / OnlyWhenTargetGoesBack.
 * - Plus AutoBlock's own distance range and probability.
 * - Player must hold a sword ([WeaponType.SWORD]).
 *
 * On disable: automatically releases right-click to prevent stuck key.
 */
object AutoBlock : Module("AutoBlock", Category.COMBAT) {

    /** Hysteresis margin (blocks) for the OnEnter range mode. */
    private const val RANGE_HYSTERESIS = 1.5f

    // ========== Mode ==========
    // Normal = click-style: while AutoClicker / physical left-click is working, block for
    //          [delayMs] then release (re-engages each hit).
    // Switch = AutoBlock maintains the block; each fresh left-click attack (physical or
    //          AutoClick) briefly cancels the block, then re-blocks after [delayMs].
    // Srg    = hold-style: block continuously while the player keeps attacking.
    private val mode by choices("Mode", arrayOf("Normal", "Switch", "Srg"))

    // ========== Distance Range ==========
    private val maxDistance by float("MaxDistance", 3.0f, 0.0f..6.0f, "blocks")
    private val minDistance by float("MinDistance", 0.0f, 0.0f..6.0f, "blocks")

    // How the distance gate triggers blocking:
    //  - "InRange" : maintain blocking while the target is inside [Min, Max] (continuous).
    //  - "OnEnter" : engage blocking when the target ENTERS the range, then hold through
    //                small distance fluctuations (hysteresis) instead of flickering at the edge.
    private val rangeMode by choices("RangeMode", arrayOf("InRange", "OnEnter"))

    // ========== Probability ==========
    private val probability by int("Chance", 100, 0..100, "%")

    // ========== Timing ==========
    private val delayMs by int("Delay", 50, 1..500, "ms")

    // ========== Conditions (Unified Engine — shared with BlockHit/WTap/AimAssist/etc.) ==========
    // Defaults are all OFF so the base block behavior is unchanged (block wherever you attack).
    // OnlyPlane (onlyGround) is off by default: 1.8 PvP keeps the player airborne most of the
    // time (jump-reset / knockback), so gating blocking on ground by default broke Srg/Switch.
    private val onlyPlane by boolean("OnlyPlane", false)
    private val onlyTargeting by boolean("OnlyTargeting", false)
    private val onlyMove by boolean("OnlyMove", false)
    private val onlyMoveForward by boolean("OnlyMoveForward", false)
    private val onlyWhenTargetGoesBack by boolean("OnlyWhenTargetGoesBack", false)

    // Unified trigger engine — map individual toggles into TriggerOptions.
    // minDistance/maxDistance/chance stay at their permissive defaults here; AutoBlock applies
    // its own MaxDistance/MinDistance/Chance options below so the two don't double-gate.
    private val triggerOptions by triggerOptions("Trigger") {
        onlyGround = onlyPlane
        onlyCurrentView = onlyTargeting
        onlyMove = this@AutoBlock.onlyMove
        onlyMoveForward = this@AutoBlock.onlyMoveForward
        onlyWhenTargetGoesBack = this@AutoBlock.onlyWhenTargetGoesBack
    }

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

    /** Last nanoTime an attack was detected (Srg debounce). */
    private var lastAttackNano: Long = 0L

    /** OnEnter range-mode latch: target has entered the attack range. */
    private var rangeEngaged: Boolean = false

    /** Throttle counter for diagnostic logging (every ~40 ticks ≈ 2s). */
    private var diagCount: Int = 0

    // ========== Tick Listener ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p, EventBridge.crosshairTarget)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        // Throttled heartbeat — logs even when the sword guard below early-returns, so we can
        // tell "module running but not holding a sword" apart from "tick not reaching here".
        if (++diagCount % 60 == 0) {
            CoreLogger.info(
                "[AutoBlock] heart mode=$mode enabled=$enabled sword=${player.weaponType} " +
                "onGround=${player.onGround} m0=${EventBridge.mouseButton0} physL=${player.isAttackKeyDown} " +
                "rightHeld=${EventBridge.isRightMousePhysicallyDown} target=${target?.distance ?: "null"}")
        }

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

        // Unified condition engine (OnlyPlane / OnlyTargeting / OnlyMove / OnlyMoveForward /
        // OnlyWhenTargetGoesBack). Applies to ALL modes.
        val conditionsMet = ConditionChecker.check(triggerOptions, player, target)

        // Distance gate — behavior depends on RangeMode (InRange = continuous; OnEnter = latch
        // on entry with hysteresis).
        val inRange = computeInRange(target)
        val probPass = probability >= 100 || Random.nextInt(100) < probability

        if (++diagCount % 40 == 0) {
            CoreLogger.info(
                "[AutoBlock] diag mode=$mode sword=${player.weaponType == WeaponType.SWORD} " +
                "m0=${EventBridge.mouseButton0} physL=${player.isAttackKeyDown} " +
                "cond=$conditionsMet (Plane=$onlyPlane Target=$onlyTargeting Move=$onlyMove " +
                "MoveF=$onlyMoveForward GoesBack=$onlyWhenTargetGoesBack) " +
                "inRange=$inRange rangeMode=$rangeMode prob=$probPass blockHeld=$blockHeld " +
                "reblock=$reblockPending rightHeld=${EventBridge.isRightMousePhysicallyDown} " +
                "target=${target?.distance ?: "null"}")
        }

        val nowNs = System.nanoTime()

        when (mode) {
            // ── Srg: hold-style — block continuously while the player is attacking.
            // Long-hold left-click (or AutoClicker working) keeps the block up. Debounced:
            // the block only releases once attacking has been absent for ~250ms. Without this,
            // a fast AutoClick cadence sampled on the 20Hz background thread flickers the
            // attack state true/false → the block drops mid-combat (the "防砍" the user saw).
            "Srg" -> {
                if (isAttacking && conditionsMet && inRange && probPass) {
                    lastAttackNano = nowNs
                    if (!blockHeld) {
                        EventBridge.syntheticUse = true
                        blockHeld = true
                    }
                    // Keep blocking while attacking.
                } else if (blockHeld && elapsedNs(lastAttackNano) >= 250_000_000L) {
                    releaseBlock()
                }
            }

            // ── Switch: block-hit style. AutoBlock maintains the block while a target is in
            // range. While the player is actively attacking (long-pressing left-click OR
            // AutoClicker working), the block is CANCELLED so attacks land; when they stop
            // attacking it re-blocks. SwitchOnRightHold: the cancel additionally requires the
            // player to be holding (long-pressing) right-click. Uses a debounced attack state
            // (not a rising edge) so both long-press left and AutoClick are detected reliably.
            "Switch" -> {
                val cancelAllowed = !switchOnRightHold || EventBridge.isRightMousePhysicallyDown
                val baseBlock = conditionsMet && inRange && probPass
                if (isAttacking) lastAttackNano = nowNs
                // "Actively attacking" within a ~200ms window (absorbs AutoClick pulse gaps).
                val attackingActive = elapsedNs(lastAttackNano) < 200_000_000L

                if (baseBlock && attackingActive && cancelAllowed) {
                    // Attacking → cancel the block so the hit lands.
                    if (blockHeld) releaseBlock()
                } else if (baseBlock) {
                    // Not attacking, or cancel not allowed → maintain the block.
                    if (!blockHeld) {
                        EventBridge.syntheticUse = true
                        blockHeld = true
                    }
                } else if (blockHeld) {
                    releaseBlock()
                }
            }

            // ── Normal: click-style — block while AutoClicker is working (mouseButton0 cadence)
            // or the player is physically left-clicking. Hold the block for [delayMs], then
            // release; re-engage on the next attack so it blocks each hit.
            else -> {
                val shouldBlock = isAttacking && conditionsMet && inRange && probPass
                if (blockHeld) {
                    if (elapsedNs(blockStartNano) >= delayMs * 1_000_000L) {
                        releaseBlock()
                    }
                } else if (shouldBlock) {
                    EventBridge.syntheticUse = true
                    blockHeld = true
                    blockStartNano = nowNs
                }
            }
        }
    }

    /**
     * Distance gate per [rangeMode].
     *  - "InRange": target within [minDistance, maxDistance].
     *  - "OnEnter": engage once the target enters [minDistance, maxDistance], then hold until it
     *    leaves beyond maxDistance + [RANGE_HYSTERESIS] (no flicker at the boundary).
     */
    private fun computeInRange(target: TargetState?): Boolean {
        if (target == null) {
            rangeEngaged = false
            return false
        }
        val d = target.distance
        val within = d >= minDistance && d <= maxDistance
        return when (rangeMode) {
            "OnEnter" -> {
                if (within) rangeEngaged = true
                else if (d > maxDistance + RANGE_HYSTERESIS) rangeEngaged = false
                rangeEngaged && d >= minDistance
            }
            else -> within
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
        lastAttackNano = 0L
        rangeEngaged = false
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        releaseBlock()
        EventBridge.syntheticUse = false
        reblockPending = false
        lastAttackNano = 0L
        rangeEngaged = false
    }
}
