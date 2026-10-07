package io.doppel.adapter.common.module.combat

import io.doppel.core.condition.ConditionChecker
import io.doppel.core.logging.CoreLogger
import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.core.strategy.autoblock.RangeGate
import io.doppel.core.strategy.click.WeaponType
import io.doppel.core.strategy.combat.CombatTrigger
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.option.int
import io.doppel.adapter.common.option.triggerOptions
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

    // ========== Mode ==========
    // Normal = click-style: while AutoClicker / physical left-click is working, block for
    //          [delayMs] then release (re-engages each hit).
    // Switch = AutoBlock maintains the block; each fresh left-click attack (physical or
    //          AutoClick) briefly cancels the block, then re-blocks after [delayMs].
    // Srg    = hold-style: block continuously while the player keeps attacking.
    private val mode by choices("Mode", arrayOf("Normal", "Switch", "Sag", "Predict", "Lag"))

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
    private val delayMs by int("Delay", 50, 1..500, "ms", modes = arrayOf("Normal", "Switch"))

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

    /**
     * [Sag] and [Predict]: release the block after holding it this long, then re-engage on the next
     * trigger. A hold-style block that never lets go is what the server sees as "always blocking"; a
     * hold-and-release rhythm is what the block-hit timing is supposed to look like.
     */
    private val maxHoldMs by int("MaxHold", 700, 400..2000, "ms", modes = arrayOf("Sag", "Predict"))

    /**
     * [Predict]: the i-frame window the core trigger fires in — the same PRE mode WTap uses.
     *
     * `CombatTrigger` PRE fires while the target is inside its hurt-resistance window, i.e. while it is
     * still recovering. Blocking there times the block against the hit that is coming rather than
     * reacting to the one that already landed. Reusing the core algorithm (instead of re-deriving the
     * window here) is what keeps Predict, WTap and BlockHit consistent with each other.
     */
    private val predictHurtTime by int("PredictHurtTime", 10, 1..20, "ticks", modes = arrayOf("Predict"))

    // ========== Lag (packet lag instead of a block) ==========
    /** Chance that a lag window starts at all (Raven's Lag Chance). */
    private val lagChance by int("LagChance", 100, 0..100, "%", modes = arrayOf("Lag"))
    /** How long one lag window may last (Raven's Lag Max Duration). */
    private val lagMaxMs by int("LagMax", 200, 50..500, "ms", modes = arrayOf("Lag"))
    /** Block immediately after an attack was let through (Raven's Block again immediately). */
    private val lagBlockAgain by boolean("LagBlockAgain", true, modes = arrayOf("Lag"))

    /** [Switch] mode only: only perform the block-hit switch while the player holds right-click. */
    private val switchOnRightHold by boolean("SwitchOnRightHold", true, modes = arrayOf("Switch"))

    // ========== Internal State ==========

    /** Whether AutoBlock pressed the right-click (not the player). */
    private var blockHeld: Boolean = false

    /** nanoTime when the current Lag window started (0 = not lagging). */
    private var lagStartNano: Long = 0L

    /** nanoTime when blocking started (Normal mode countdown). */
    private var blockStartNano: Long = 0L

    /** Whether we are in the Switch-mode "wait before re-blocking" phase. */
    private var reblockPending: Boolean = false

    /** nanoTime when Switch release happened; re-block after [delayMs]. */
    private var reblockStartNano: Long = 0L

    /** Last nanoTime an attack was detected (Srg debounce). */
    private var lastAttackNano: Long = 0L

    /** OnEnter range-mode latch (algorithm state, owned by core RangeGate). */
    private val rangeGateState = RangeGate.State()

    /** [Predict]: hit counter/threshold handed back and forth with the core trigger. */
    private var predictHits: Int = 0
    private var predictThreshold: Int = 1

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
        // Any open screen — inventory, chest, crafting, chat, pause — means the player is not
        // fighting. Never press or hold the synthetic block there, and drop anything we were
        // holding so the block cannot stick while the player browses a container.
        if (EventBridge.isGuiOpen) {
            releaseBlock()
            reblockPending = false
            lastAttackNano = 0L
            return
        }

        // 1.8 exclusive: only SWORD matters (1.9+ has shields, no sword block)
        if (player.weaponType != WeaponType.SWORD) return

        // Switch-mode re-block timer: if we're waiting to re-block, finish it now.
        if (reblockPending) {
            if (elapsedNs(reblockStartNano) >= delayMs * 1_000_000L) {
                EventBridge.syntheticUse = true
                // Every call below is a *transition* (a block starting, or a re-block), never the
                // steady hold, so the HUD row flashes when the module does something instead of
                // glowing red for as long as a target stands nearby.
                markWorked()
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
        // on entry with hysteresis). Algorithm lives in core RangeGate.
        val inRange = RangeGate.compute(
            rangeGateState,
            RangeGate.Config(
                mode = if (rangeMode == "OnEnter") RangeGate.Mode.ON_ENTER else RangeGate.Mode.IN_RANGE,
                minDistance = minDistance,
                maxDistance = maxDistance,
                hysteresis = RangeGate.DEFAULT_HYSTERESIS
            ),
            target?.distance
        )
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
            // ── Sag: hold-style — block continuously while the player is attacking.
            // Long-hold left-click (or AutoClicker working) keeps the block up. Debounced:
            // the block only releases once attacking has been absent for ~250ms. Without this,
            // a fast AutoClick cadence sampled on the 20Hz background thread flickers the
            // attack state true/false → the block drops mid-combat (the "blockhit" the user saw).
            "Sag" -> {
                if (isAttacking && conditionsMet && inRange && probPass) {
                    lastAttackNano = nowNs
                    if (!blockHeld) {
                        EventBridge.syntheticUse = true
                        markWorked() // block starting — a transition, not the steady hold
                        blockHeld = true
                        blockStartNano = nowNs
                    } else if (elapsedNs(blockStartNano) >= maxHoldMs * 1_000_000L) {
                        // Held long enough: let go. The next attack re-engages on its own, which is the
                        // block/release rhythm instead of a block that stays up for the whole fight.
                        releaseBlock()
                    }
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
                // SwitchOnRightHold: with it on, the mode runs ONLY while the player holds right-click —
                // and holding right-click is the player blocking manually, so we step aside entirely
                // rather than maintaining a block nobody asked for. It used to gate only the cancel,
                // so the block was maintained anyway and the option looked broken because it was.
                if (switchOnRightHold && !EventBridge.isRightMousePhysicallyDown) {
                    if (blockHeld) releaseBlock()
                    lastAttackNano = 0L
                    return
                }
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
                        markWorked() // block starting — a transition, not the steady hold
                        blockHeld = true
                    }
                } else if (blockHeld) {
                    releaseBlock()
                }
            }

            // ── Predict: block inside the target's i-frame window, via the same core trigger WTap's PRE
            // uses (see [predictHurtTime]). The window is the point: the target is still recovering, so
            // the block is timed against the hit that is coming rather than the one that already landed.
            "Predict" -> {
                var trigger = false
                if (target != null) {
                    val eval = CombatTrigger.evaluate(
                        mode = CombatTrigger.Mode.PRE,
                        target = target,
                        maxHurtTime = predictHurtTime,
                        hitCounter = predictHits,
                        hitThreshold = predictThreshold,
                        hitPerMin = 1,
                        hitPerMax = 1,
                        chance = 100
                    )
                    predictHits = eval.hitCounter
                    predictThreshold = eval.hitThreshold
                    trigger = eval.fire
                }
                trigger = trigger && conditionsMet && inRange && probPass
                if (trigger) {
                    if (!blockHeld) {
                        EventBridge.syntheticUse = true
                        markWorked() // block starting — a transition, not the steady hold
                        blockHeld = true
                        blockStartNano = nowNs
                    }
                } else if (blockHeld && elapsedNs(blockStartNano) >= maxHoldMs * 1_000_000L) {
                    releaseBlock()
                }
            }

            // ── Lag: do not block at all — hold the outgoing packets instead. Raven's Lag mode: the
            // server keeps seeing the last state it had (the block) while nothing new arrives, and the
            // held traffic goes out the moment the module stops asking or an attack must land.
            "Lag" -> {
                val want = conditionsMet && inRange && probPass && isAttacking
                val lagging = lagStartNano != 0L
                if (!lagging && want && (lagChance >= 100 || Random.nextInt(100) < lagChance)) {
                    lagStartNano = nowNs
                    EventBridge.packetLagActive = true
                    markWorked() // a lag window starting is a transition, like a block starting
                } else if (lagging) {
                    // Keep the request alive (the platform releases it if it is not refreshed), and end it
                    // when the window is up or the player stopped attacking.
                    EventBridge.packetLagActive = true
                    if (elapsedNs(lagStartNano) >= lagMaxMs * 1_000_000L || !want) {
                        lagStartNano = 0L
                        EventBridge.packetLagActive = false
                    }
                }
                // An attack was let through: block at once, so the server sees the block right after it.
                if (EventBridge.packetLagAttackReleased) {
                    EventBridge.packetLagAttackReleased = false
                    if (lagBlockAgain && conditionsMet && inRange) {
                        EventBridge.syntheticUse = true
                        blockHeld = true
                        blockStartNano = nowNs
                        markWorked()
                    }
                }
                // Let any immediately-re-blocked use go after [delayMs], the same as Normal mode.
                if (blockHeld && elapsedNs(blockStartNano) >= delayMs * 1_000_000L) releaseBlock()
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
                    markWorked()
                    blockHeld = true
                    blockStartNano = nowNs
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
        lastAttackNano = 0L
        rangeGateState.reset()
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        releaseBlock()
        EventBridge.syntheticUse = false
        reblockPending = false
        lastAttackNano = 0L
        rangeGateState.reset()
    }
}
