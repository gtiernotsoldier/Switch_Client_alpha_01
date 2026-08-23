package io.switchlite.core.strategy.combat

import io.switchlite.core.model.TargetState
import kotlin.random.Random

/**
 * Shared combat trigger logic for modules that fire after landing hits.
 *
 * Used by [BlockHit] and [WTap] (1.8 path). Evaluates three conditions
 * in sequence:
 *
 * 1. **POST/PRE hurt-time gate** — whether the target is in (or out of)
 *    the max-hurt window after being hit.
 * 2. **Probability roll** — configurable chance of firing.
 * 3. **Hit counting** — only fires every N hits, with random threshold
 *    sampling per cycle.
 *
 * Pure Core layer: no Minecraft, no EventBridge, no key simulation.
 *
 * @param mode      POST (target was just hit) or PRE (target not in hurt state).
 * @param target    current target snapshot.
 * @param maxHurtTime player's maxHurtResistantTime (typically 10).
 * @param hitCounter attacks counted since last trigger.
 * @param hitThreshold  current threshold (must reach this to fire).
 * @param hitPerMin minimum value for next random threshold.
 * @param hitPerMax maximum value for next random threshold.
 * @param chance    0-100 probability of firing when conditions are met.
 *
 * @return [EvalResult] with whether to fire, updated hitCounter, and new threshold.
 */
object CombatTrigger {

    enum class Mode { POST, PRE, EQUAL }

    data class EvalResult(
        val fire: Boolean,
        val hitCounter: Int,
        val hitThreshold: Int
    )

    fun evaluate(
        mode: Mode,
        target: TargetState,
        maxHurtTime: Int,
        hitCounter: Int,
        hitThreshold: Int,
        hitPerMin: Int,
        hitPerMax: Int,
        chance: Int
    ): EvalResult {
        // POST/PRE hurt window gate. Raven semantics (MC 1.8.9 Entity.hurtResistantTime,
        // i-frames counted down from 20): the target is "just hit" while in the window.
        //   POST — fire right after the target was hit (still inside the i-frame window).
        //   PRE  — fire any time the target is inside its i-frame window (was just hit); the tap
        //          lands while the target is still recovering, which is a reliable, forgiving
        //          window for both players and mobs. (Previously `<= 10` required the exact
        //          half-expired moment, which was essentially never sampled and never fired on mobs.)
        val hurtOk = when (mode) {
            Mode.POST  -> target.hurtResistantTime >= 10
            Mode.PRE   -> target.hurtResistantTime > 0 && target.hurtResistantTime <= maxHurtTime
            Mode.EQUAL -> target.hurtResistantTime == maxHurtTime
        }
        if (!hurtOk) return EvalResult(false, hitCounter, hitThreshold)

        // 2. Attack counting
        val c = hitCounter + 1
        if (c < hitThreshold) return EvalResult(false, c, hitThreshold)

        // 3. Probability roll (after counting — only roll when about to fire)
        if (chance < 100 && Random.nextInt(100) >= chance) {
            val nextT = Random.nextInt(hitPerMin, hitPerMax + 1).coerceAtLeast(1)
            return EvalResult(false, 0, nextT)
        }

        val nextThreshold = Random.nextInt(hitPerMin, hitPerMax + 1).coerceAtLeast(1)
        return EvalResult(true, 0, nextThreshold)
    }
}
