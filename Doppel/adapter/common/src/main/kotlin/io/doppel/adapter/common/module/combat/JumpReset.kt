package io.doppel.adapter.common.module.combat

import io.doppel.ipc.Strategies
import io.doppel.ipc.JumpResetStep
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.contract.model.VelocityContext
import io.doppel.contract.util.Directions
import io.doppel.contract.util.Vec3
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.int
import io.doppel.adapter.common.option.triggerOptions
import kotlin.math.sqrt

/**
 * JumpReset Module — automatic jump on knockback to reduce knockback height.
 *
 * Detects incoming velocity packets (S12PacketEntityVelocity, S27PacketExplosion)
 * via [EventBridge.registerVelocityNotifier] and queues a jump via
 * [EventBridge.queueJump] — the main thread presses the jump key, so MC's own
 * tick performs the jump and the Keystrokes HUD reflects it.
 *
 * This side holds only **logic judgements + marshalling + platform I/O**:
 *  - the internal conditions (sprinting / on ground / not in fluid),
 *  - the front-angle geometry check (whitelisted frame-level geometry),
 *  - the unified gate,
 *  - the final "still sprinting at jump time" judgement,
 *  - and the single `queueJump` platform write.
 *
 * Everything stateful lives in the algorithm process: the chance roll, the
 * cooldown counter (Ticks / ReceivedHits), and the pending delayed jump with
 * its deadline. The two halves — `knock` on the velocity thread and `pump` on
 * the client tick — are two ops sharing one core state; the pump never draws.
 * No `kotlin.random`, no local time-window judgement (`System.nanoTime()` is
 * an input we marshal, like SprintReset/Backtrack/Reach), no cross-tick counters.
 *
 * Cooldown modes:
 * - Ticks: wait N client ticks between jumps (ticksUntilJump, default 4).
 * - ReceivedHits: jump after receiving N knockbacks (hitsUntilJump, default 2).
 */
object JumpReset : Module("JumpReset", Category.COMBAT) {

    // ========== Cooldown ==========
    private val cooldownMode by choices("CooldownMode", arrayOf("Ticks", "ReceivedHits"))
    private val ticksUntilJump by int("TicksUntilJump", 4, 0..20, "ticks")
    private val hitsUntilJump by int("HitsUntilJump", 2, 0..5)

    // ========== Timing & Probability (inputs to the core, not local state) ==========
    private val delay by int("Delay", 0, 0..500, "ms")
    private val chance by int("Chance", 100, 0..100, "%")

    // ========== Conditions (Unified Engine) ==========
    private val onlyPlane by boolean("OnlyPlane", true)
    private val onlyTargeting by boolean("OnlyTargeting", false)
    private val onlyMove by boolean("OnlyMove", false)
    private val onlyMoveForward by boolean("OnlyMoveForward", false)
    private val onlyWhenTargetGoesBack by boolean("OnlyWhenTargetGoesBack", false)

    private val triggerOptions by triggerOptions("Trigger") {
        onlyGround = onlyPlane
        onlyCurrentView = onlyTargeting
        onlyMove = this@JumpReset.onlyMove
        onlyMoveForward = this@JumpReset.onlyMoveForward
        onlyWhenTargetGoesBack = this@JumpReset.onlyWhenTargetGoesBack
    }

    // ========== Event listeners ==========
    private val velocityNotifier: (VelocityContext) -> Unit = { ctx ->
        if (enabled) onKnockback(ctx)
    }

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onTick(p, t)
    }

    // ========== Knockback half (velocity / Netty thread) ==========
    private fun onKnockback(ctx: VelocityContext) {
        val player = ctx.player
        val target = ctx.target

        // ---- Judgements (this side decides nothing beyond these predicates) ----
        // The internal conditions are not configurable: the S12 knockback
        // packet is the authoritative "I was hit + knocked back" signal, so
        // there is deliberately no hurtTime gate here (see the original
        // LiquidBounce port: on the Netty thread hurtTime reads 0 or a stale
        // value, and the exact ==9 frame was essentially never sampled).
        val internalOk = player.isSprinting && player.onGround &&
            !EventBridge.isInFluid && isKnockbackFromFront(player, ctx.originalMotion)
        // Short-circuit: a failed internal condition never asks the unified
        // gate, exactly as the original chain of early returns did.
        val gatesOpen = internalOk && Strategies.gate(player, target, triggerOptions)

        val step = Strategies.jumpKnock(
            player, target, gatesOpen, System.nanoTime(),
            chance, cooldownModeCode(), ticksUntilJump, hitsUntilJump, delay
        )

        // ---- Platform side effect: land the step the core decided ----
        if (step == JumpResetStep.JUMP && stillSprinting(player)) queueJump()
    }

    // ========== Pump half (client tick / main thread) ==========
    private fun onTick(player: PlayerState, target: TargetState?) {
        val step = Strategies.jumpPump(
            player, target, System.nanoTime(), cooldownModeCode(), ticksUntilJump
        )

        // The final judgement stays here and uses *this* frame's player, not a
        // snapshot cached at knockback time: a delayed jump must not fire once
        // the player has stopped sprinting or left the ground. The core owns
        // "whether to jump"; the module owns "whether we still may".
        if (step == JumpResetStep.JUMP && stillSprinting(player)) queueJump()
    }

    /** The one platform write this module owns. */
    private fun queueJump() {
        EventBridge.queueJump()
        markWorked() // the HUD row flashes on the jump actually asked for
    }

    /** Final "at jump time" judgement, kept on the JVM side. */
    private fun stillSprinting(player: PlayerState): Boolean =
        player.isSprinting && player.onGround

    /** Wire cooldown-mode code: 0 Ticks, 1 ReceivedHits. */
    private fun cooldownModeCode(): Int = if (cooldownMode == "ReceivedHits") 1 else 0

    // ========== Angle Check (frame-level geometry, explicit whitelist) ==========
    /**
     * Knockback must come from roughly the FRONT of where the player is FACING (the attacker is in
     * front of the crosshair). The knockback SOURCE direction is the reverse of the motion vector
     * (-motion): when the attacker is in front, the player is knocked BACKWARD, so motion points away
     * from facing and the source (-motion) points toward facing. Accept dot(facing, -motionUnit) >=
     * -0.5 (a 120° cone centered on the facing direction).
     */
    private fun isKnockbackFromFront(player: PlayerState, motion: Vec3): Boolean {
        val hSpeed = sqrt(motion.x * motion.x + motion.z * motion.z)
        if (hSpeed < 0.001) return false

        val facing = Directions.fromYaw(player.rotation.yaw)
        val srcX = -motion.x / hSpeed
        val srcZ = -motion.z / hSpeed
        return (facing.x * srcX + facing.z * srcZ) >= -0.5
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        // A fresh enable must not inherit a cooldown counter or a pending
        // delayed jump the core still believes it owns.
        Strategies.resetPlayer()
        EventBridge.registerVelocityNotifier(velocityNotifier)
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterVelocityNotifier(velocityNotifier)
        EventBridge.unregisterTickListener(tickListener)
        Strategies.resetPlayer()
    }
}
