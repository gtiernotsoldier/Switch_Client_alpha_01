package io.switchlite.adapter.common.module.combat

import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.model.VelocityContext
import io.switchlite.core.algorithm.RotationCalculator
import io.switchlite.core.util.Vec3
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.choices
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.option.int
import io.switchlite.adapter.common.option.triggerOptions
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * JumpReset Module — automatic jump on knockback to reduce knockback height.
 *
 * Detects incoming velocity packets (S12PacketEntityVelocity, S27PacketExplosion)
 * via [EventBridge.registerVelocityNotifier]. When knockback is received:
 * 1. Checks internal conditions (angle ≤ 120°, sprinting, hurtTime == 9, not in fluid).
 * 2. Checks configurable conditions via [ConditionChecker].
 * 3. Probability roll + cooldown check.
 * 4. Optional delay timer (resets on new knockback).
 * 5. Queues a jump via [EventBridge.queueJump] — the main thread presses the jump key, so MC's own
 *    tick performs the jump and the Keystrokes HUD reflects it.
 *
 * Cooldown modes:
 * - Ticks: wait N server ticks between jumps (ticksUntilJump, default 4).
 * - ReceivedHits: jump after receiving N hits (hitsUntilJump, default 2).
 *
 * Only activates while player is sprinting and on ground.
 */
object JumpReset : Module("JumpReset", Category.COMBAT) {

    // ========== Cooldown ==========
    private val cooldownMode by choices("CooldownMode", arrayOf("Ticks", "ReceivedHits"))
    private val ticksUntilJump by int("TicksUntilJump", 4, 0..20, "ticks")
    private val hitsUntilJump by int("HitsUntilJump", 2, 0..5)

    // ========== Timing & Probability ==========
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

    // ========== Internal State ==========
    private var knockbackMotion: Vec3? = null
    private var knockbackPlayer: PlayerState? = null
    private var knockbackTarget: TargetState? = null
    private var delayEndNano: Long = 0L
    private var cooldownCounter: Int = 0
    private var jumpPending: Boolean = false

    // ========== Velocity Notifier ==========
    private val velocityNotifier: (VelocityContext) -> Unit = { ctx ->
        if (enabled) onKnockback(ctx)
    }

    private val tickListener: (PlayerState, TargetState?) -> Unit = { _, _ ->
        // Only used for Ticks cooldown counting (increment each tick)
        if (enabled && cooldownMode == "Ticks" && cooldownCounter < ticksUntilJump) {
            cooldownCounter++
        }
        // Process delayed jump
        if (enabled && jumpPending) processPendingJump()
    }

    // ========== Knockback Handler ==========
    private fun onKnockback(ctx: VelocityContext) {
        val player = ctx.player
        val target = ctx.target
        val motion = ctx.originalMotion

        // ---- Internal conditions (not configurable) ----
        // Must be sprinting
        if (!player.isSprinting) return

        // Must be on ground
        if (!player.onGround) return

        // Must be at hurtTime == 9 (specific frame after damage)
        if (player.hurtTime != 9) return

        // Must not be in water/lava/web
        if (EventBridge.isInFluid) return

        // Angle: knockback direction vs player facing ≤ 120°
        if (!isKnockbackFromFront(player, motion)) return

        // ---- Configurable conditions ----
        if (!ConditionChecker.check(triggerOptions, player, target)) return

        // ---- Probability ----
        if (chance < 100 && Random.nextInt(100) >= chance) return

        // ---- Cooldown check ----
        when (cooldownMode) {
            "Ticks" -> {
                if (cooldownCounter < ticksUntilJump) return
            }
            "ReceivedHits" -> {
                cooldownCounter++
                if (cooldownCounter < hitsUntilJump) return
            }
        }

        // ---- Schedule jump ----
        // Reset past cooldown state
        cooldownCounter = 0

        knockbackMotion = motion
        knockbackPlayer = player
        knockbackTarget = target
        jumpPending = true

        if (delay > 0) {
            delayEndNano = System.nanoTime() + delay * 1_000_000L
        } else {
            executeJump()
        }
    }

    // ========== Delayed Jump Processing ==========
    private fun processPendingJump() {
        if (System.nanoTime() >= delayEndNano) {
            executeJump()
        }
    }

    /**
     * Execute the jump: trigger jump action and reset all state.
     * This is called either immediately (delay=0) or after the delay timer.
     */
    private fun executeJump() {
        if (!jumpPending) return

        // Verify player is still sprinting + on ground at jump time
        val player = knockbackPlayer
        if (player != null && (!player.isSprinting || !player.onGround)) {
            resetState()
            return
        }

        EventBridge.queueJump()

        // Reset after execution — cooldownCounter must be reset here because
        // ticks continue incrementing it during the delay period.
        cooldownCounter = 0
        resetState()
    }

    // ========== Angle Check ==========
    private fun isKnockbackFromFront(player: PlayerState, motion: Vec3): Boolean {
        val hSpeed = sqrt(motion.x * motion.x + motion.z * motion.z)
        if (hSpeed < 0.001) return false

        // Player facing direction from yaw (via Core RotationCalculator)
        val facing = RotationCalculator.yawToDirection(player.rotation.yaw)

        // Knockback direction unit vector
        val kbX = motion.x / hSpeed
        val kbZ = motion.z / hSpeed

        // Dot product ≥ cos(120°) = -0.5 → angle ≤ 120°
        return (facing.x * kbX + facing.z * kbZ) >= -0.5
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        cooldownCounter = 0
        jumpPending = false
        EventBridge.registerVelocityNotifier(velocityNotifier)
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterVelocityNotifier(velocityNotifier)
        EventBridge.unregisterTickListener(tickListener)
        resetState()
    }

    private fun resetState() {
        knockbackMotion = null
        knockbackPlayer = null
        knockbackTarget = null
        jumpPending = false
    }
}
