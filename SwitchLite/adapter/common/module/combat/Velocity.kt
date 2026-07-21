package io.switchlite.adapter.common.module.combat

import io.switchlite.core.algorithm.VectorOperations
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.*
import io.switchlite.core.option.RandomRange
import io.switchlite.core.option.ProbabilityOption
import io.switchlite.core.util.Vec3
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category

/**
 * Velocity Module - Complete Implementation
 * Modes: LEGIT (Range Random + Conditions), DELAY (Packet Queue), CLICK (Liquid Burst)
 */
object Velocity : Module("Velocity", Category.COMBAT) {

    // ========== Mode Selection ==========
    private val mode by enum("Mode", VelocityMode.LEGIT)

    // ========== Legit Mode: Horizontal Range ==========
    private val horizontalMin by float("HorizontalMin", 0.4f, 0.0f..1.0f)
    private val horizontalMax by float("HorizontalMax", 0.6f, 0.0f..1.0f)

    // ========== Legit Mode: Vertical Range ==========
    private val verticalMin by float("VerticalMin", 0.4f, 0.0f..1.0f)
    private val verticalMax by float("VerticalMax", 0.6f, 0.0f..1.0f)

    // ========== Probability ==========
    private val chance by int("Chance", 100, 0..100, "%")

    // ========== Delay Settings ==========
    private val delayMs by int("DelayMs", 0, 0..500, "ms")
    private val delayTicks by int("DelayTicks", 0, 0..20, "ticks")

    // ========== Condition Flags (6 Independent Switches) ==========
    private val onlyMove by boolean("OnlyMove", false)
    private val onlyMoveForward by boolean("OnlyMoveForward", false)
    private val onlyWhenTargetGoesBack by boolean("OnlyWhenTargetGoesBack", false)
    private val onlyGround by boolean("OnlyGround", true)
    private val onlyCurrentView by boolean("OnlyCurrentView", false)
    private val disabledInAir by boolean("DisabledInAir", true)

    // ========== Click Mode: Liquid Burst Config ==========
    private val clicksMin by int("ClicksMin", 2, 1..10)
    private val clicksMax by int("ClicksMax", 5, 1..10)
    private val hurtTimeToClick by int("HurtTimeToClick", 8, 0..10)
    private val whenFacingEnemyOnly by boolean("WhenFacingEnemyOnly", true)
    private val maxAngleDifference by float("MaxAngleDiff", 90f, 0f..180f, "deg")
    private val clickRange by float("ClickRange", 3.0f, 0.0f..6.0f, "blocks")

    // ========== Internal State: Delay Queue ==========
    private val delayQueue = DelayQueue()

    // ========== Entry Point ==========

    fun onVelocityPacket(ctx: VelocityContext): PlatformCommand {
        return when (mode) {
            VelocityMode.LEGIT -> handleLegit(ctx)
            VelocityMode.DELAY -> handleDelay(ctx)
            VelocityMode.CLICK -> handleClick(ctx)
        }
    }

    // ========== Legit Mode Logic ==========

    private fun handleLegit(ctx: VelocityContext): PlatformCommand {
        val player = ctx.player
        val target = ctx.target
        val original = ctx.originalMotion

        // 1. Condition Checks (All 6)
        if (onlyMove && !player.isMoving) 
            return PlatformCommand.Pass(original)
        
        if (onlyMoveForward && !player.isMovingForward) 
            return PlatformCommand.Pass(original)
        
        if (onlyGround && !player.onGround) 
            return PlatformCommand.Pass(original)
        
        if (disabledInAir && !player.onGround) 
            return PlatformCommand.Pass(original)
        
        if (onlyCurrentView && target != null && !player.isLookingAtTarget) 
            return PlatformCommand.Pass(original)
        
        if (onlyWhenTargetGoesBack && target != null && !target.isMovingBackward) 
            return PlatformCommand.Pass(original)

        // 2. Probability Check
        if (!ProbabilityOption.test(chance)) 
            return PlatformCommand.Pass(original)

        // 3. Delay Check (Enqueue if delay > 0)
        if (delayTicks > 0 || delayMs > 0) {
            delayQueue.enqueue(ctx, delayMs, delayTicks)
            return PlatformCommand.CancelPacket(ctx.packetHandle)
        }

        // 4. Sample Factors & Scale Motion
        val hFactor = RandomRange.sample(horizontalMin, horizontalMax)
        val vFactor = RandomRange.sample(verticalMin, verticalMax)
        
        val reduced = VectorOperations.scale(original, hFactor, vFactor)

        return PlatformCommand.ModifyMotion(reduced)
    }

    // ========== Delay Mode Logic ==========

    private fun handleDelay(ctx: VelocityContext): PlatformCommand {
        // Always enqueue and cancel original packet
        delayQueue.enqueue(ctx, delayMs, delayTicks)
        return PlatformCommand.CancelPacket(ctx.packetHandle)
    }

    // ========== Click Mode Logic (Liquid Burst) ==========

    private fun handleClick(ctx: VelocityContext): PlatformCommand {
        val player = ctx.player
        val target = ctx.target ?: return PlatformCommand.Pass(ctx.originalMotion)

        // 1. Check HurtTime Window
        if (player.hurtTime != hurtTimeToClick) 
            return PlatformCommand.Pass(ctx.originalMotion)

        // 2. Check Facing Angle
        if (whenFacingEnemyOnly) {
            val angleDiff = calculateAngleDifference(player, target)
            if (angleDiff > maxAngleDifference) 
                return PlatformCommand.Pass(ctx.originalMotion)
        }

        // 3. Check Distance
        val distance = player.position.distanceTo(target.position)
        if (distance > clickRange) 
            return PlatformCommand.Pass(ctx.originalMotion)

        // 4. Sample Click Count
        val clicks = RandomRange.sampleInt(clicksMin, clicksMax)

        return PlatformCommand.ClickBurst(target.id, clicks)
    }

    // ========== Helper: Angle Calculation ==========
    
    private fun calculateAngleDifference(player: PlayerState, target: TargetState): Float {
        val dx = target.position.x - player.position.x
        val dz = target.position.z - player.position.z
        val targetYaw = kotlin.math.atan2(dx, dz).toFloat() * (180f / kotlin.math.PI.toFloat())
        var diff = kotlin.math.abs(player.rotationYaw - targetYaw)
        while (diff > 180f) diff -= 360f
        return kotlin.math.abs(diff)
    }

    // ========== Lifecycle ==========

    override fun onEnable() {
        EventBridge.registerVelocityListener { ctx ->
            if (enabled) onVelocityPacket(ctx) else PlatformCommand.Pass(ctx.originalMotion)
        }
        EventBridge.registerTickListener { player, target ->
            val ctx = VelocityContext(
                originalMotion = Vec3(player.motionX, player.motionY, player.motionZ),
                player = player,
                target = target,
                packetHandle = null
            )
            delayQueue.pump(EventBridge.getCurrentTick(), ctx)
        }
    }

    override fun onDisable() {
        EventBridge.unregisterVelocityListener()
        delayQueue.clear()
    }

    // ========== Internal Class: Delay Queue ==========

    private class DelayQueue {
        private data class QueuedPacket(
            val ctx: VelocityContext,
            val releaseTick: Int
        )
        
        private val queue = mutableListOf<QueuedPacket>()
        private var lastTick = 0

        fun enqueue(ctx: VelocityContext, delayMs: Int, delayTicks: Int) {
            val releaseTick = lastTick + delayTicks + (delayMs / 50)
            queue.add(QueuedPacket(ctx, releaseTick))
        }

        fun pump(currentTick: Int, baseCtx: VelocityContext) {
            lastTick = currentTick
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (currentTick >= item.releaseTick) {
                    val hFactor = RandomRange.sample(horizontalMin, horizontalMax)
                    val vFactor = RandomRange.sample(verticalMin, verticalMax)
                    val reduced = VectorOperations.scale(item.ctx.originalMotion, hFactor, vFactor)
                    EventBridge.applyMotion(reduced)
                    iterator.remove()
                }
            }
        }

        fun clear() {
            queue.clear()
        }
    }
}

enum class VelocityMode { LEGIT, DELAY, CLICK }
