package io.doppel.adapter.common.module.combat

import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.contract.strategy.tap.Side
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.option.int
import io.doppel.ipc.Strategies
import io.doppel.ipc.Strategies.TapTick

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
 *  - Chase  : side chosen by target motion prediction (core `chase_side`) —
 *             strafes toward where the target is flying to hold the combo.
 *  - Hybrid : Seven while being hit (evade), Chase otherwise (align).
 *
 * Safety model ("assist, never hijack"):
 *  - Keyboard only. A/D synthetic channels never touch the mouse.
 *  - Always OR-ed with the physical key on the render thread.
 *  - Aborts instantly if the player is physically pressing A or D (never fights
 *    the player's own strafing fingers), if RequireForward is set and W is not
 *    physically held, if airborne (OnlyPlane), or when out of combat.
 *
 * This module keeps only judgements (physical keys, target guards, the pattern
 * mode), marshalling and the claimLeft / claimRight platform writes. The
 * fire-point chance roll and both uniform draws (tap duration, inter-tap gap)
 * run in the core on ADTap's own RNG stream (`TAP_ADTAP_SAMPLE`); the side
 * selection was already there (`TAP_SEVEN` / `TAP_RANDOM` / `TAP_CHASE` on
 * `rng_tap`). No `kotlin.random` remains here.
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
    // The phase and its two deadlines live in the core; this module only remembers which side it
    // is currently holding, because that is a key it must be able to release.
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
        val t = Strategies.tapTick(player, target, now)
        // No core: TapTick.OFFLINE is idle forever, and chooseSide answers null, so abort() is
        // what actually stops this module. Explicit here so it cannot fall through to a tap.
        if (!Strategies.online) { abort(); return }

        when (t.event) {
            TapTick.END_TAP -> {
                setSide(pendingSide, false)
                // The gap is a core draw. A missing answer means the pattern
                // stops here — the machine is already IDLE again, so the next
                // tick retries the fire point rather than arming a local guess.
                val s = sample(Strategies.ADTapSampleKind.GAP, player, target)
                if (s != null) {
                    Strategies.tapBegin(player, target, now, s.gapMs, postDelay = true)
                }
            }
            TapTick.SHOULD_START_TAP -> startTap(player, target, now)
            else -> {}
        }

        // First entry into combat (or resume after an abort) → begin the pattern.
        // `t.event == NONE` matters: the tick answer's phase is a snapshot from
        // BEFORE this branch ran, so on an END_TAP tick it still reads IDLE even
        // though the gap post-delay was just armed. Restarting here would arm
        // the gap and immediately overwrite it with a new tap — the pre-IPC
        // module read the phase AFTER arming, and this guard restores that.
        if (t.event == TapTick.NONE && t.phase == TapTick.IDLE) {
            startTap(player, target, now)
        }
    }

    // ================================================================
    // Pattern execution
    // ================================================================
    private fun startTap(player: PlayerState, target: TargetState, nowNs: Long) {
        // Fire point: the chance roll and both range draws run in the core on
        // ADTap's own stream. A missing answer means "do not tap" — never a
        // local guess — and the machine is still IDLE, so the next tick retries.
        val s = sample(Strategies.ADTapSampleKind.FIRE, player, target) ?: return
        if (!s.passed) {
            // The chance roll failed: cool off for a gap, then retry the fire point.
            Strategies.tapBegin(player, target, nowNs, s.gapMs, postDelay = true)
            return
        }
        val side = chooseSide(player, target)
        if (side == null) { abort(); return }
        pendingSide = side
        lastSide = side
        setSide(pendingSide, true)
        markWorked()                      // HUD work flash — the module just acted
        Strategies.tapBegin(player, target, nowNs, s.durationMs, postDelay = false)
    }

    /**
     * Side selection runs in the core: `seven` draws from the tap stream, `chase` needs the
     * target's predicted position, `random` must draw from the same stream the other patterns do
     * or the module's own draw order stops being reproducible. Null = no core / no target.
     */
    private fun chooseSide(player: PlayerState, target: TargetState): Side? = when (mode) {
        "Seven" -> sideOf(Strategies.tapSeven(player, target, sideCode(lastSide), repeatChance))
        "Random" -> sideOf(Strategies.tapRandom(player, target))
        "Chase" -> sideOf(Strategies.tapChase(player, target, player.rotation.yaw, leadTicks))
        "Hybrid" ->
            if (player.hurtTime > 0) sideOf(Strategies.tapSeven(player, target, sideCode(lastSide), repeatChance))
            else sideOf(Strategies.tapChase(player, target, player.rotation.yaw, leadTicks))
        else -> Side.LEFT
    }

    private fun sideCode(s: Side) = if (s == Side.LEFT) 0 else 1

    private fun sideOf(code: Int?): Side? = when (code) {
        null -> null
        0 -> Side.LEFT
        else -> Side.RIGHT
    }

    /** Ask ADTap's own core RNG stream for the chance roll and the range draws. */
    private fun sample(kind: Int, player: PlayerState, target: TargetState?) =
        Strategies.adtapSample(
            player, target, kind, chance,
            tapMinMs, tapMaxMs, gapMinMs, gapMaxMs
        )

    /**
     * A/D are ADDITIVE claims — ADTap deliberately never hijacks the player's own strafing.
     * Routing through the arbiter also stops [abort] from releasing a key another module is
     * holding, which is exactly what the Velocity Sprint brace's release used to do to ADTap.
     */
    private fun setSide(side: Side, down: Boolean) {
        if (side == Side.LEFT) {
            if (down) EventBridge.claimLeft(name, true) else EventBridge.releaseLeft(name)
        } else {
            if (down) EventBridge.claimRight(name, true) else EventBridge.releaseRight(name)
        }
    }

    /** Release both synthetic strafe keys and reset the pattern. */
    private fun abort() {
        EventBridge.releaseLeft(name)
        EventBridge.releaseRight(name)
        Strategies.resetTap()
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        // A fresh activation must not inherit the previous one's phase: a half-elapsed tap would
        // fire an instant key press on the first tick back.
        Strategies.resetTap()
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        abort()
    }
}
