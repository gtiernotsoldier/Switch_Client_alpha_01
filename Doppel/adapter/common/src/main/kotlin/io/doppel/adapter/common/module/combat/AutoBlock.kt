package io.doppel.adapter.common.module.combat

import io.doppel.contract.logging.CoreLogger
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.contract.strategy.click.WeaponType
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.option.int
import io.doppel.adapter.common.option.triggerOptions
import kotlin.random.Random
import io.doppel.contract.option.TriggerOptions
import io.doppel.ipc.Strategies

// Option *defaults*, not algorithm parameters: the live values travel to the core with every call.
// File-level rather than object members because an object's properties are initialized in
// declaration order and the option delegates below read these while they are being built.
private const val DEFAULT_MAX_HURT = 20
private const val DEFAULT_RTT_TICKS = 2
private const val DEFAULT_LEAD_TICKS = 2
private const val DEFAULT_PREDICT_WINDOW = 3
private const val DEFAULT_PREDICT_CHANCE = 50
private const val RANGE_HYSTERESIS = 1.5f

/**
 * AutoBlock Module — 1.8 exclusive (sword blocking).
 *
 * Automates the sword block (right-click) timing during combat.
 * Two modes:
 *
 * **Normal**: While AutoClicker is working (or the player physically left-clicks), press
 * right-click to block for [delayMs], then release — re-engages each hit.
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
    // Normal  = click-style: while AutoClicker / physical left-click is working, block for
    //           [delayMs] then release (re-engages each hit).
    // Sag     = hold-style: block continuously while the player keeps attacking.
    // Predict = lock-style: hold the guard while the target's damage lock is up, and swing the
    //           moment it lifts; also holds ahead of the opponent's own estimated attack.
    //           The algorithm lives in the core (`CBT_PREDICT`).
    // Lag      = no block at all: hold the packet stream back instead.
    // Adaptive = the three above combined: four signals, weighted by how much each one actually
    //            knows, plus a duty-cycle budget (core `CBT_ARBITER`).
    private val mode by choices("Mode", arrayOf("Normal", "Sag", "Predict", "Adaptive", "Lag"))

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
    private val delayMs by int("Delay", 50, 1..500, "ms", modes = arrayOf("Normal"))

    // ========== Conditions (Unified Engine — shared with BlockHit/WTap/AimAssist/etc.) ==========
    // Defaults are all OFF so the base block behavior is unchanged (block wherever you attack).
    // OnlyPlane (onlyGround) is off by default: 1.8 PvP keeps the player airborne most of the
    // time (jump-reset / knockback), so gating blocking on ground by default broke Sag.
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
     * [Sag]: release the block after holding it this long, then re-engage on the next trigger. A
     * hold-style block that never lets go is what the server sees as "always blocking"; a
     * hold-and-release rhythm is what the block-hit timing is supposed to look like.
     *
     * Predict does not use this any more. Its hold length is derived from the target's damage lock,
     * which is already bounded (~half the i-frame countdown), so a wall-clock cap would only ever
     * cut a correct hold short.
     */
    private val maxHoldMs by int("MaxHold", 700, 400..2000, "ms", modes = arrayOf("Sag"))

    // ========== Predict (core CBT_PREDICT) ==========
    // Layer A is arithmetic — the target's i-frame countdown, high half = our hit is discarded.
    // Layer B is an estimate — the opponent's attack rhythm, learned from hits we take.
    //
    // The old Predict reused `CombatTrigger` PRE, which fires on hurtResistantTime in 1..10.
    // That is the *open* half of the window, so it blocked exactly when a hit would have landed and
    // released exactly when it would not. `PredictHurtTime` went with it: it capped that window at
    // 10 by hand, which is the half-way value, not the window.

    /**
     * [Predict]: release the guard this many ticks before the damage lock lifts, so the swing is
     * armed the moment it does. 0 = release exactly when a hit starts landing.
     */
    private val predictLead by int(
        "PredictLead", DEFAULT_LEAD_TICKS, 0..10, "ticks", modes = arrayOf("Predict"))

    /**
     * [Predict]: ticks to subtract from the target's i-frame counter before comparing, because the
     * client sees a delayed copy of it. Raise it for a laggier connection; too high and the guard
     * comes off before a hit can actually land.
     */
    private val predictRtt by int(
        "PredictRtt", DEFAULT_RTT_TICKS, 0..10, "ticks", modes = arrayOf("Predict"))

    /** [Predict]: also hold the guard when the opponent's own next attack is due (layer B). */
    private val predictAttacks by boolean("PredictAttacks", true, modes = arrayOf("Predict"))

    /** [Predict]: how close the predicted attack must be, in ticks. */
    private val predictWindow by int(
        "PredictWindow", DEFAULT_PREDICT_WINDOW, 0..20, "ticks", modes = arrayOf("Predict"))

    /** [Predict]: probability that a due prediction holds the guard (0-100). */
    private val predictChance by int(
        "PredictChance", DEFAULT_PREDICT_CHANCE, 0..100, "%", modes = arrayOf("Predict"))

    // ========== Adaptive (core CBT_ARBITER) ==========
    // One weight per signal. With the arbiter's defaults, lock/threat/sustain each hold on their own
    // — so Adaptive is never weaker than the single mode it replaces — and only reflex needs
    // company. Setting a weight to 0 tunes that mode's contribution out, which is how "Sag but not
    // Normal" is expressed here.
    /** [Adaptive]: weight of the damage-lock signal — arithmetic, so it is trusted most. */
    private val sigLock by float("SigLock", 1.0f, 0f..1f, modes = arrayOf("Adaptive"))

    /** [Adaptive]: weight of the predicted-attack signal (an estimate). */
    private val sigThreat by float("SigThreat", 0.6f, 0f..1f, modes = arrayOf("Adaptive"))

    /** [Adaptive]: weight of "I have been attacking" — Sag's signal. */
    private val sigSustain by float("SigSustain", 0.5f, 0f..1f, modes = arrayOf("Adaptive"))

    /** [Adaptive]: weight of "I just swung" — Normal's signal. */
    private val sigReflex by float("SigReflex", 0.4f, 0f..1f, modes = arrayOf("Adaptive"))

    /** [Adaptive]: how much combined weight is needed to hold the guard. */
    private val sigThreshold by float("SigThreshold", 0.5f, 0f..1f, modes = arrayOf("Adaptive"))

    /**
     * [Adaptive]: ceiling on how much of the time the guard may be up, in %. Above it only the
     * lock signal may still hold — "my hit cannot land" is not behaviour that needs rationing,
     * "I am probably attacking" is. 100 disables the gate.
     */
    private val sigMaxDuty by int("SigMaxDuty", 100, 0..100, "%", modes = arrayOf("Adaptive"))

    // ========== Lag (packet lag instead of a block) ==========
    /** Chance that a lag window starts at all (Raven's Lag Chance). */
    private val lagChance by int("LagChance", 100, 0..100, "%", modes = arrayOf("Lag"))
    /** How long one lag window may last (Raven's Lag Max Duration). */
    private val lagMaxMs by int("LagMax", 200, 50..500, "ms", modes = arrayOf("Lag"))
    /** Block immediately after an attack was let through (Raven's Block again immediately). */
    private val lagBlockAgain by boolean("LagBlockAgain", true, modes = arrayOf("Lag"))

    // ========== Internal State ==========

    /** Whether AutoBlock pressed the right-click (not the player). */
    private var blockHeld: Boolean = false

    /** nanoTime when the current Lag window started (0 = not lagging). */
    private var lagStartNano: Long = 0L

    /** nanoTime when blocking started (Normal mode countdown). */
    private var blockStartNano: Long = 0L

    /** Last nanoTime an attack was detected (Srg debounce). */
    private var lastAttackNano: Long = 0L

    /** [Predict]: previous tick's own i-frame counter, for the "we were just hit" rising edge. */
    private var lastSelfHurt: Int = 0

    /** [Predict]: last non-zero maxHurtResistantTime read from the player (1.8: 20). */
    private var lastKnownMaxHurt: Int = 0

    /** Monotonic tick index; the predictor only ever looks at differences. */
    private var tickCounter: Int = 0

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

        // ---------- Per-tick clocks ----------
        // Kept before every early return. The tick index is wall-clock for the predictor, and the
        // rising edge of our OWN i-frame counter is the "we were just hit" signal layer B learns
        // from. Skipping either just because the module is idle this tick would corrupt the
        // estimate rather than merely slow it down.
        tickCounter++
        val selfJustHit = player.hurtResistantTime > lastSelfHurt
        lastSelfHurt = player.hurtResistantTime
        if (player.maxHurtResistantTime > 0) lastKnownMaxHurt = player.maxHurtResistantTime

        // ---------- Platform-level guards ----------
        // Any open screen — inventory, chest, crafting, chat, pause — means the player is not
        // fighting. Never press or hold the synthetic block there, and drop anything we were
        // holding so the block cannot stick while the player browses a container.
        if (EventBridge.isGuiOpen) {
            releaseBlock()
            lastAttackNano = 0L
            return
        }

        // 1.8 exclusive: only SWORD matters (1.9+ has shields, no sword block)
        if (player.weaponType != WeaponType.SWORD) return

        // ---------- Attack detection (AutoClick working + physical click) ----------
        // Effective attack: AutoClicker running (mouseButton0 reflects its cadence pulses on
        // the render thread) OR the physical left button held. mouseButton0 already folds the
        // synthetic clicks in, and isAttackKeyDown is the raw physical left button.
        val isAttacking = EventBridge.mouseButton0 || player.isAttackKeyDown

        // Unified condition engine (OnlyPlane / OnlyTargeting / OnlyMove / OnlyMoveForward /
        // OnlyWhenTargetGoesBack). Applies to ALL modes.
        val conditionsMet = Strategies.gate(player, target, triggerOptions)

        // Distance gate — behavior depends on RangeMode (InRange = continuous; OnEnter = latch
        // on entry with hysteresis). Algorithm lives in the core (`CBT_RANGE`).
        val inRange = Strategies.range(
            player, target,
            onEnter = rangeMode == "OnEnter",
            minDistance = minDistance,
            maxDistance = maxDistance,
            hysteresis = RANGE_HYSTERESIS,
            distance = target?.distance
        )
        val probPass = probability >= 100 || Random.nextInt(100) < probability

        if (++diagCount % 40 == 0) {
            CoreLogger.info(
                "[AutoBlock] diag mode=$mode sword=${player.weaponType == WeaponType.SWORD} " +
                "m0=${EventBridge.mouseButton0} physL=${player.isAttackKeyDown} " +
                "cond=$conditionsMet (Plane=$onlyPlane Target=$onlyTargeting Move=$onlyMove " +
                "MoveF=$onlyMoveForward GoesBack=$onlyWhenTargetGoesBack) " +
                "inRange=$inRange rangeMode=$rangeMode prob=$probPass blockHeld=$blockHeld " +
                "rightHeld=${EventBridge.isRightMousePhysicallyDown} " +
                "target=${target?.distance ?: "null"}")
        }

        val nowNs = System.nanoTime()

        // Attack recency — "the last time an attack actually counted". Recorded once here rather
        // than inside a mode branch so Sag's release debounce and the Adaptive mode's sustain/reflex
        // signals read the same value. Note the probability roll is deliberately NOT part of this
        // condition: the roll decides whether to *act*, not whether the attack happened. It used to
        // be inside Sag's branch, which meant a failed roll also skipped the timestamp and the 250 ms
        // debounce could expire while the player was still attacking.
        if (isAttacking && conditionsMet && inRange) lastAttackNano = nowNs

        when (mode) {
            // ── Sag: hold-style — block continuously while the player is attacking.
            // Long-hold left-click (or AutoClicker working) keeps the block up. Debounced:
            // the block only releases once attacking has been absent for ~250ms. Without this,
            // a fast AutoClick cadence sampled on the 20Hz background thread flickers the
            // attack state true/false → the block drops mid-combat (the "blockhit" the user saw).
            "Sag" -> {
                if (isAttacking && conditionsMet && inRange && probPass) {
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

            // ── Predict: time the guard against the target's damage lock.
            //
            // After we land a hit the target cannot be damaged for the high half of its i-frame
            // countdown (11..20 of 20) and it can still hit us back. Swinging into that window is a
            // wasted swing with the guard down; the useful move is to hold, and to swing the moment
            // the lock lifts. Layer B adds the guessed half: hold even outside the lock when the
            // opponent's own next attack is due. The algorithm is entirely in the core (`CBT_PREDICT`).
            "Predict" -> {
                val decision = Strategies.predict(
                    player, target,
                    maxHurt = predictMaxHurt(),
                    rttTicks = predictRtt,
                    leadTicks = predictLead,
                    predictAttacks = predictAttacks,
                    windowTicks = predictWindow,
                    chance = predictChance,
                    currentTick = tickCounter,
                    opponentJustHit = selfJustHit
                )
                if (decision != null && decision.block && conditionsMet && inRange && probPass) {
                    if (!blockHeld) {
                        EventBridge.syntheticUse = true
                        markWorked() // block starting — a transition, not the steady hold
                        blockHeld = true
                    }
                } else {
                    // Release whenever the decision says swing: no wall-clock hold here, the lock
                    // length already bounds it.
                    releaseBlock()
                }
                if (diagCount % 40 == 0) {
                    CoreLogger.info(
                        "[AutoBlock] predict block=${decision?.block} " +
                        "effHurt=${decision?.effectiveHurt} held=$blockHeld"
                    )
                }
            }

            // ── Lag: do not block at all — hold the outgoing packets instead. Raven's Lag mode: the
            // server keeps seeing the last state it had (the block) while nothing new arrives, and the
            // held traffic goes out the moment the module stops asking or an attack must land.
            // ── Adaptive: Normal + Sag + Predict, combined.
            //
            // The three modes differ in what they KNOW, not in what they want, so they are four
            // signals rather than alternatives: Predict supplies `locked` (arithmetic) and `threat`
            // (an estimate), Sag supplies `sustain` (attacking lately), Normal supplies `reflex`
            // (just swung). The core's block arbiter weighs them by how much each one knows and applies a
            // duty-cycle budget on top.
            "Adaptive" -> {
                val decision = Strategies.predict(
                    player, target,
                    maxHurt = predictMaxHurt(),
                    rttTicks = predictRtt,
                    leadTicks = predictLead,
                    predictAttacks = predictAttacks,
                    windowTicks = predictWindow,
                    chance = predictChance,
                    currentTick = tickCounter,
                    opponentJustHit = selfJustHit
                )
                // Sag's signal, including Sag's own release debounce: a fast AutoClick cadence
                // sampled at 20 Hz flickers the raw attack state, so the block would drop mid-fight.
                val sustain = isAttacking || elapsedNs(lastAttackNano) < 250_000_000L
                // Normal's signal: inside the short window Normal would hold after a swing.
                val reflex = elapsedNs(lastAttackNano) < delayMs * 1_000_000L

                // locked/threat are the two separate predictor signals, not a decode of `block`:
                // the arbiter weighs them differently, so collapsing them loses the distinction
                // that makes Adaptive stronger than either mode alone. A missing answer is "no
                // signal", which weighs the same as false.
                val verdict = Strategies.arbiter(
                    player, target,
                    locked = decision?.locked ?: false,
                    threat = decision?.threat ?: false,
                    sustain = sustain,
                    reflex = reflex,
                    wLock = sigLock.toDouble(),
                    wThreat = sigThreat.toDouble(),
                    wSustain = sigSustain.toDouble(),
                    wReflex = sigReflex.toDouble(),
                    threshold = sigThreshold.toDouble(),
                    maxDuty = sigMaxDuty / 100.0
                )

                if (verdict != null && verdict.block && conditionsMet && inRange && probPass) {
                    if (!blockHeld) {
                        EventBridge.syntheticUse = true
                        markWorked() // block starting — a transition, not the steady hold
                        blockHeld = true
                    }
                } else {
                    releaseBlock()
                }
                // No record() here: the core records inside the call above, so the duty budget
                // counts ticks rather than module decisions.
                if (diagCount % 40 == 0) {
                    CoreLogger.info(
                        "[AutoBlock] adaptive score=${"%.2f".format(verdict?.score ?: 0.0)} " +
                        "held=$blockHeld"
                    )
                }
            }

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

    /**
     * [Predict]: assemble the core predictor's config.
     *
     * `maxHurtResistantTime` comes from the player (1.8: 20), not from the module. The removed
     * `PredictHurtTime` option hardcoded 10 as the window's *upper bound* — 10 is the half-way
     * value, so the window it selected was the wrong half of the countdown.
     */
    private fun predictMaxHurt(): Int =
        if (lastKnownMaxHurt > 0) lastKnownMaxHurt else DEFAULT_MAX_HURT

    // ========== Lifecycle ==========

    override fun onEnable() {
        // No syntheticUseOverride: AutoBlock ADDS its block on top of the player's own
        // right-click (Raven style). Overriding the whole use key would swallow the
        // player's manual blocking.
        lastAttackNano = 0L
        Strategies.resetCombat()
        lastSelfHurt = 0
        tickCounter = 0
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        releaseBlock()
        EventBridge.syntheticUse = false
        lastAttackNano = 0L
        Strategies.resetCombat()
        lastSelfHurt = 0
    }
}
