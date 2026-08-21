package io.switchlite.adapter.common.module.combat

import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.strategy.combat.CombatTrigger
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
 * BlockHit Module — 1.8 exclusive sword block-hit combo.
 *
 * Simulates the block-hit technique: after (or before) landing an attack
 * on the target, briefly hold right-click to block, then release.
 * This cancels the attack recovery animation and provides knockback reduction
 * during the swing cooldown.
 *
 * **POST mode**: trigger when target was just hit (hurtTime at max).
 * **PRE mode**:  trigger before the attack lands (block first, attack second).
 *
 * State machine: IDLE → POST_DELAY (optional) → BLOCKING → IDLE
 *
 * Uses physical mouse button state to avoid cross-talk with AutoClicker.
 */
object BlockHit : Module("BlockHit", Category.COMBAT) {

    // ========== Mode ==========
    // 1 = POST (after hit), 2 = PRE (before hit)
    private val eventType by choices("Mode", arrayOf("POST", "PRE"))

    // ========== Trigger ==========
    /** Require physical right-click held alongside left-click to trigger. */
    private val onRightMBHold by boolean("OnRightMBHold", true)

    // ========== Timing (ranges, random per-trigger) ==========
    private val waitMsMin by int("WaitMsMin", 110, 1..500, "ms")
    private val waitMsMax by int("WaitMsMax", 150, 1..500, "ms")
    private val hitPerMin by int("HitPerMin", 1, 1..10)
    private val hitPerMax by int("HitPerMax", 1, 1..10)
    private val postDelayMin by int("PostDelayMin", 10, 0..500, "ms")
    private val postDelayMax by int("PostDelayMax", 40, 0..500, "ms")

    // ========== Probability & Range ==========
    private val chance by int("Chance", 100, 0..100, "%")
    private val rangeMin by float("RangeMin", 0.0f, 0.0f..6.0f, "blocks")
    private val rangeMax by float("RangeMax", 3.0f, 0.0f..6.0f, "blocks")

    // ========== Filters ==========
    private val onlySword by boolean("OnlySword", true)
    private val onlyPlayers by boolean("OnlyPlayers", true)

    // ========== Conditions (Unified Engine — shared with AimAssist/Velocity/etc.) ==========
    private val onlyPlane by boolean("OnlyPlane", true)
    private val onlyTargeting by boolean("OnlyTargeting", false)
    private val onlyMove by boolean("OnlyMove", false)
    private val onlyMoveForward by boolean("OnlyMoveForward", false)
    private val onlyWhenTargetGoesBack by boolean("OnlyWhenTargetGoesBack", false)

    // Unified trigger engine — map individual toggles into TriggerOptions
    private val triggerOptions by triggerOptions("Trigger") {
        onlyGround = onlyPlane
        onlyCurrentView = onlyTargeting
        onlyMove = this@BlockHit.onlyMove
        onlyMoveForward = this@BlockHit.onlyMoveForward
        onlyWhenTargetGoesBack = this@BlockHit.onlyWhenTargetGoesBack
    }

    // ========== Phase State Machine ==========
    private enum class Phase { IDLE, POST_DELAY, BLOCKING }

    private var phase: Phase = Phase.IDLE

    /** nanoTime when blocking started / will end (IDLE phase target). */
    private var blockEndNano: Long = 0L

    /** nanoTime when postDelay started; IDLE when delay expires. */
    private var postDelayEndNano: Long = 0L

    /** Current waitMs value (sampled once per trigger). */
    private var currentWaitMs: Int = 0

    /** Attacks counted since last trigger. */
    private var hitCounter: Int = 0

    /** Next hitPer threshold (sampled once per cycle). */
    private var hitPerThreshold: Int = 1

    // ========== Tick Listener ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onTick(p, t)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        val now = System.nanoTime()

        // ---- Phase: BLOCKING (holding right-click) ----
        if (phase == Phase.BLOCKING) {
            if (now >= blockEndNano) {
                releaseBlock()
                phase = Phase.IDLE
            }
            return // Blocking in progress, skip all other processing
        }

        // ---- Phase: POST_DELAY (waiting before block) ----
        if (phase == Phase.POST_DELAY) {
            if (now >= postDelayEndNano) {
                pressBlock(now)
                phase = Phase.BLOCKING
            }
            return
        }

        // ---- Phase: IDLE — evaluate trigger conditions ----
        if (target == null) return

        // Basic guards
        if (player.health <= 0f) { resetState(); return }
        if (onlySword) {
            val wt = player.weaponType
            if (wt != WeaponType.SWORD && wt != WeaponType.AXE) { resetState(); return }
        }

        // onRightMBHold: require physical right-click held
        if (onRightMBHold && !EventBridge.isRightMousePhysicallyDown) {
            resetState()
            return
        }
        // Always require physical left-click held (attack key)
        if (!EventBridge.isLeftMousePhysicallyDown) {
            resetState()
            return
        }

        // ---- Target guards ----
        if (!isValidTarget(target)) { resetState(); return }
        if (target.distance < rangeMin || target.distance > rangeMax) { resetState(); return }

        // ---- Additional conditions (Unified Engine) ----
        if (!ConditionChecker.check(triggerOptions, player, target)) return

        // ---- POST/PRE hurt-time + probability + hit counting (shared via Core) ----
        val eval = CombatTrigger.evaluate(
            mode = if (eventType == "PRE") CombatTrigger.Mode.PRE else CombatTrigger.Mode.POST,
            target = target,
            maxHurtTime = player.maxHurtResistantTime,
            hitCounter = hitCounter,
            hitThreshold = hitPerThreshold,
            hitPerMin = hitPerMin,
            hitPerMax = hitPerMax,
            chance = chance
        )
        hitCounter = eval.hitCounter
        hitPerThreshold = eval.hitThreshold
        if (!eval.fire) return

        // ---- Execute block-hit ----
        val postDelayMs = Random.nextInt(postDelayMin, postDelayMax + 1)
        currentWaitMs = Random.nextInt(waitMsMin, waitMsMax + 1)

        if (postDelayMs > 0) {
            postDelayEndNano = now + postDelayMs * 1_000_000L
            phase = Phase.POST_DELAY
        } else {
            pressBlock(now)
            phase = Phase.BLOCKING
        }
    }

    // ========== Helpers ==========

    private fun pressBlock(nowNs: Long) {
        EventBridge.syntheticUse = true
        blockEndNano = nowNs + currentWaitMs * 1_000_000L
    }

    private fun releaseBlock() {
        if (EventBridge.isRightMousePhysicallyDown) return // player is manually holding
        EventBridge.syntheticUse = false
    }

    /** Target validity: alive, onlyPlayers filter. */
    private fun isValidTarget(target: TargetState): Boolean {
        if (target.health <= 0f) return false
        // onlyPlayers: names are empty for non-player entities in most implementations
        if (onlyPlayers && target.name.isEmpty()) return false
        return true
    }

    private fun resetState() {
        hitCounter = 0
        // Don't reset phase — timers need to finish
    }

    // ========== Lifecycle ==========

    override fun onEnable() {
        hitCounter = 0
        hitPerThreshold = Random.nextInt(hitPerMin, hitPerMax + 1).coerceAtLeast(1)
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        if (phase == Phase.BLOCKING) {
            releaseBlock()
        }
        EventBridge.syntheticUse = false
        phase = Phase.IDLE
        hitCounter = 0
    }
}
