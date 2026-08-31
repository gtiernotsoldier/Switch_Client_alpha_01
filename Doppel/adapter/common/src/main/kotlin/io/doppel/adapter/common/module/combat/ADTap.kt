package io.doppel.adapter.common.module.combat

import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.core.strategy.tap.Side
import io.doppel.core.strategy.tap.StrafePattern
import io.doppel.core.strategy.tap.TapStateMachine
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.option.int
import kotlin.random.Random

/**
 * ADTap Module — automated A/D strafe-pattern ("7-tap") for combat.
 *
 * IMPORTANT SEMANTICS: ADTap is NOT a sprint reset. In 1.8 mechanics, tapping A/D
 * while holding W never touches the sprint state — that is WTap's (W release) and
 * STap's (S press) job. ADTap instead produces a zig-zag strafe pattern that makes
 * the player's movement hard to track (defense) and, in Chase mode, bends the
 * sprint path toward the target's predicted position (combo alignment). WTap and
 * ADTap therefore COMPOSE cleanly: one owns sprint state, the other owns strafing.
 *
 * Patterns:
 *  - Seven  : classic 7-tap — alternating A/D with a small fumble chance.
 *  - Random : random side + uniform durations, maximum unpredictability.
 *  - Chase  : side chosen by target motion prediction (StrafePattern.chaseSide) —
 *             strafes toward where the target is flying to hold the combo.
 *  - Hybrid : Seven while being hit (evade), Chase otherwise (align).
 *
 * Safety model ("assist, never hijack"):
 *  - Keyboard only. A/D synthetic channels never touch the mouse.
 *  - Always OR-ed with the physical key on the render thread.
 *  - Aborts instantly if the player is physically pressing A or D (never fights
 *    the player's own strafing fingers), if RequireForward is set and W is not
 *    physically held, if airborne (OnlyPlane), or when out of combat.
 */
object ADTap : Module("ADTap", Category.COMBAT) {

    // ========== Pattern ==========
    private val mode by choices("Mode", arrayOf("Seven", "Random", "Chase", "Hybrid"))

    /** Tap duration envelope: each tap is a uniform draw in [TapMsMin, TapMsMax]. */
    private val tapMinMs by int("TapMsMin", 70, 10..400, "ms")
    private val tapMaxMs by int("TapMsMax", 140, 10..400, "ms")

    /** Gap between taps (both keys released). Zero gap = back-to-back burst. */
    private val gapMinMs by int("GapMsMin", 40, 0..500, "ms")
    private val gapMaxMs by int("GapMsMax", 110, 0..500, "ms")

    /** Probability (%) that a 7-tap repeats the same side instead of alternating. */
    private val repeatChance by int("RepeatChance", 10, 0..100, "%")

    /** Chase: linear motion-prediction horizon for the target (20 ticks = 1 s). */
    private val leadTicks by int("LeadTicks", 3, 0..20, "ticks")

    // ========== Combat Context ==========
    private val chance by int("Chance", 100, 0..100, "%")
    private val rangeMin by float("RangeMin", 0.0f, 0.0f..6.0f, "blocks")
    private val rangeMax by float("RangeMax", 4.0f, 0.0f..6.0f, "blocks")
    private val onlyPlayers by boolean("OnlyPlayers", false)

    /** Only strafe while on the ground — never risk walking off an edge mid-air. */
    private val onlyPlane by boolean("OnlyPlane", true)

    /** Only assist while the player physically holds W (strafe alongside forward movement). */
    private val requireForward by boolean("RequireForward", true)

    // ========== Core State ==========
    private val machine = TapStateMachine()
    private var pendingSide: Side = Side.LEFT
    private var lastSide: Side = Side.LEFT

    // ========== Tick Listener ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p, EventBridge.crosshairTarget)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        val now = System.nanoTime()

        // ---- Combat context gate: no valid target in range → full stop ----
        if (target == null || target.health <= 0f ||
            target.distance < rangeMin || target.distance > rangeMax ||
            (onlyPlayers && target.name.isEmpty())
        ) {
            abort()
            return
        }

        // ---- Safety guards ("assist, never hijack") ----
        if (onlyPlane && !player.onGround) { abort(); return }
        if (requireForward && !EventBridge.physicalForwardDown) { abort(); return }
        // The player's own strafing always wins — never fight their fingers.
        if (EventBridge.physicalLeftDown || EventBridge.physicalRightDown) { abort(); return }

        // ---- State machine events ----
        when (machine.tick(now)) {
            TapStateMachine.Event.END_TAP -> {
                setSide(pendingSide, false)
                machine.beginPostDelay(now, nextGapMs())
            }
            TapStateMachine.Event.SHOULD_START_TAP -> startTap(player, target, now)
            TapStateMachine.Event.NONE -> {}
        }

        // First entry into combat (or resume after an abort) → begin the pattern.
        if (machine.phase == TapStateMachine.Phase.IDLE) {
            startTap(player, target, now)
        }
    }

    // ================================================================
    // Pattern execution
    // ================================================================
    private fun startTap(player: PlayerState, target: TargetState, nowNs: Long) {
        if (chance < 100 && Random.nextInt(100) >= chance) {
            machine.beginPostDelay(nowNs, nextGapMs())
            return
        }
        pendingSide = chooseSide(player, target)
        lastSide = pendingSide
        val duration = Random.nextInt(tapMinMs, tapMaxMs + 1)
        setSide(pendingSide, true)
        machine.beginTap(nowNs, duration)
    }

    private fun chooseSide(player: PlayerState, target: TargetState): Side = when (mode) {
        "Seven" -> StrafePattern.sevenTapSide(lastSide, repeatChance, Random)
        "Random" -> StrafePattern.randomSide(Random)
        "Chase" -> StrafePattern.chaseSide(player.rotation.yaw, player.position, target, leadTicks)
        "Hybrid" ->
            if (player.hurtTime > 0) StrafePattern.sevenTapSide(lastSide, repeatChance, Random)
            else StrafePattern.chaseSide(player.rotation.yaw, player.position, target, leadTicks)
        else -> Side.LEFT
    }

    private fun nextGapMs(): Int = Random.nextInt(gapMinMs, gapMaxMs + 1)

    private fun setSide(side: Side, down: Boolean) {
        if (side == Side.LEFT) EventBridge.syntheticLeft = down
        else EventBridge.syntheticRight = down
    }

    /** Release both synthetic strafe keys and reset the pattern. */
    private fun abort() {
        EventBridge.syntheticLeft = false
        EventBridge.syntheticRight = false
        machine.reset()
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        abort()
    }
}
