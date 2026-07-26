package io.switchlite.adapter.common.module.combat

import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.strategy.combat.CombatTrigger
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
 * STap Module — brief S-key press during forward movement for sudden stops.
 *
 * Presses the back key (S) for [actionTicks] milliseconds while holding W,
 * causing a near-instant speed drop. This disrupts the opponent's aim
 * prediction by creating a momentary "stutter" in the player's movement.
 *
 * Supports two version paths:
 * - **1.8**: POST/PRE mode with configurable hit counting and postDelay.
 * - **1.9+**: Fixed POST mode with lower probability (30-60%), 2-3 hit counting,
 *   and extremely short actionTicks (10-15ms) — a micro-stutter on the cooldown edge.
 *
 * Only activates while holding W ([PlayerState.isMovingForward]).
 */
object STap : Module("STap", Category.COMBAT) {

    // ========== Version Selection ==========
    var combatVersion by choices("CombatVersion", arrayOf("1.8", "1.9+"))

    // ========== Shared Configuration ==========
    private val chance18 by int("Chance18", 100, 0..100, "%")
    private val chance19 by int("Chance19", 50, 0..100, "%")
    private val rangeMin by float("RangeMin", 0.0f, 0.0f..6.0f, "blocks")
    private val rangeMax by float("RangeMax", 3.0f, 0.0f..6.0f, "blocks")
    private val onlyPlayers by boolean("OnlyPlayers", true)

    // ========== Shared Conditions (Unified Engine) ==========
    private val onlyPlane by boolean("OnlyPlane", true)
    private val onlyTargeting by boolean("OnlyTargeting", false)
    private val onlyMove by boolean("OnlyMove", false)
    private val onlyMoveForward by boolean("OnlyMoveForward", false)
    private val onlyWhenTargetGoesBack by boolean("OnlyWhenTargetGoesBack", false)

    private val triggerOptions by triggerOptions("Trigger") {
        onlyGround = onlyPlane
        onlyCurrentView = onlyTargeting
        onlyMove = this@STap.onlyMove
        onlyMoveForward = this@STap.onlyMoveForward
        onlyWhenTargetGoesBack = this@STap.onlyWhenTargetGoesBack
    }

    // ========== 1.8 Configuration ==========
    private val eventType by choices("EventType", arrayOf("POST", "PRE"))
    private val actionMin18 by int("ActionMsMin18", 25, 1..500, "ms")
    private val actionMax18 by int("ActionMsMax18", 55, 1..500, "ms")
    private val onceEveryMin by int("OnceEveryMin", 1, 1..10)
    private val onceEveryMax by int("OnceEveryMax", 1, 1..10)
    private val postDelayMin by int("PostDelayMin", 10, 0..500, "ms")
    private val postDelayMax by int("PostDelayMax", 40, 0..500, "ms")

    // ========== 1.9+ Configuration ==========
    private val actionMin19 by int("ActionMsMin19", 10, 1..20, "ms")
    private val actionMax19 by int("ActionMsMax19", 15, 1..20, "ms")
    private val onceEvery19Min by int("OnceEvery19Min", 2, 1..10)
    private val onceEvery19Max by int("OnceEvery19Max", 3, 1..10)

    // ========== State Machine ==========
    private enum class Phase { IDLE, POST_DELAY, TAPPING }

    private var phase: Phase = Phase.IDLE
    private var tapEndNano: Long = 0L
    private var postDelayEndNano: Long = 0L
    private var hitCounter: Int = 0
    private var hitThreshold: Int = 1

    // ========== Tick Listener ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onTick(p, t)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        val now = System.nanoTime()

        // ---- Phase: TAPPING (S pressed, waiting to release) ----
        if (phase == Phase.TAPPING) {
            if (now >= tapEndNano) {
                EventBridge.releaseBack()
                phase = Phase.IDLE
            }
            return
        }

        // ---- Phase: POST_DELAY (waiting before S press) ----
        if (phase == Phase.POST_DELAY) {
            if (now >= postDelayEndNano) {
                startTap(now)
            }
            return
        }

        // ---- Phase: IDLE — evaluate trigger ----

        // Guard: W must be held
        if (!player.isMovingForward) { hitCounter = 0; return }

        // Target guards
        if (target == null || target.distance < rangeMin || target.distance > rangeMax) { hitCounter = 0; return }
        if (target.health <= 0f) { hitCounter = 0; return }
        if (onlyPlayers && target.name.isEmpty()) { hitCounter = 0; return }

        // ---- Unified conditions ----
        if (!ConditionChecker.check(triggerOptions, player, target)) return

        // ---- Route by version ----
        when (combatVersion) {
            "1.8" -> evaluate18(player, target, now)
            "1.9+" -> evaluate19(now)
        }
    }

    // ================================================================
    // 1.8 — full trigger pipeline via CombatTrigger
    // ================================================================
    private fun evaluate18(player: PlayerState, target: TargetState, now: Long) {
        val eval = CombatTrigger.evaluate(
            mode = if (eventType == "PRE") CombatTrigger.Mode.PRE else CombatTrigger.Mode.POST,
            target = target,
            maxHurtTime = player.maxHurtResistantTime,
            hitCounter = hitCounter,
            hitThreshold = hitThreshold,
            hitPerMin = onceEveryMin,
            hitPerMax = onceEveryMax,
            chance = chance18
        )
        hitCounter = eval.hitCounter
        hitThreshold = eval.hitThreshold
        if (!eval.fire) return

        val pd = Random.nextInt(postDelayMin, postDelayMax + 1)
        if (pd > 0) {
            postDelayEndNano = now + pd * 1_000_000L
            phase = Phase.POST_DELAY
        } else {
            startTap(now)
        }
    }

    // ================================================================
    // 1.9+ — low-frequency micro-stutter
    // ================================================================
    private fun evaluate19(now: Long) {
        // 1.9+: POST only — must hit target (hurtTime at max)
        // Bail out early if no target was just hurt (handled by the caller having
        // a valid hurt-time check via the target's state. Since 1.9+ doesn't
        // expose target.hurtTime reliably on every tick, we rely on the physical
        // left-click as a proxy for "attack just happened".)
        if (!EventBridge.isLeftMousePhysicallyDown) return

        // Probability (lower for 1.9+: 30-60%, prevents opponent adaptation)
        if (Random.nextInt(100) >= chance19) return

        // Attack counting (2-3 per cycle)
        hitCounter++
        if (hitCounter < hitThreshold) return
        hitCounter = 0
        hitThreshold = Random.nextInt(onceEvery19Min, onceEvery19Max + 1).coerceAtLeast(1)

        // 1.9+: postDelay fixed at 0 (must sync with attack instant)
        startTap(now)
    }

    // ================================================================
    // Execution
    // ================================================================
    private fun startTap(nowNs: Long) {
        EventBridge.pressBack()
        phase = Phase.TAPPING

        val ms = when (combatVersion) {
            "1.8" -> Random.nextInt(actionMin18, actionMax18 + 1)
            "1.9+" -> Random.nextInt(actionMin19, actionMax19 + 1)
            else -> 40
        }
        tapEndNano = nowNs + ms * 1_000_000L
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        hitCounter = 0
        hitThreshold = when (combatVersion) {
            "1.8" -> Random.nextInt(onceEveryMin, onceEveryMax + 1).coerceAtLeast(1)
            "1.9+" -> Random.nextInt(onceEvery19Min, onceEvery19Max + 1).coerceAtLeast(1)
            else -> 1
        }
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        if (phase == Phase.TAPPING) {
            EventBridge.releaseBack()
        }
        phase = Phase.IDLE
        hitCounter = 0
    }
}
