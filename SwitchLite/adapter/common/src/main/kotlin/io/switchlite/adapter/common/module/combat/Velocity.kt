package io.switchlite.adapter.common.module.combat

import io.switchlite.core.algorithm.VectorOperations
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.*
import io.switchlite.core.option.RandomRange
import io.switchlite.core.util.Vec3
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.option.int
import io.switchlite.adapter.common.option.choices
import io.switchlite.adapter.common.option.triggerOptions
import io.switchlite.adapter.common.option.probability

object Velocity : Module("Velocity", Category.COMBAT) {

    // ========== 模式 ==========
    private val mode by choices("Mode", arrayOf("Legit", "Delay", "Click"))

    // ========== Legit 模式：水平（最大/最小分开）==========
    private val horizontalMin by float("HorizontalMin", 0.4f, 0.0f..1.0f)
    private val horizontalMax by float("HorizontalMax", 0.6f, 0.0f..1.0f)

    // ========== Legit 模式：垂直（最大/最小分开）==========
    private val verticalMin by float("VerticalMin", 0.4f, 0.0f..1.0f)
    private val verticalMax by float("VerticalMax", 0.6f, 0.0f..1.0f)

    // ========== 概率 ==========
    private val probability by probability("Chance", 100, 0..100)

    // ========== 延迟/Tick ==========
    private val delayMs by int("DelayMs", 0, 0..500, "ms")
    private val delayTicks by int("DelayTicks", 0, 0..20, "ticks")

    // ========== 条件判断（六个独立开关）==========
    private val onlyMove by boolean("OnlyMove", false)
    private val onlyMoveForward by boolean("OnlyMoveForward", false)
    private val onlyWhenTargetGoesBack by boolean("OnlyWhenTargetGoesBack", false)
    private val onlyGround by boolean("OnlyGround", true)
    private val onlyCurrentView by boolean("OnlyCurrentView", false)
    private val disabledInAir by boolean("DisabledInAir", true)

    // ========== 统一触发条件引擎 ==========
    private val triggerOptions by triggerOptions("Trigger") {
        onlyMove = this@Velocity.onlyMove
        onlyMoveForward = this@Velocity.onlyMoveForward
        onlyWhenTargetGoesBack = this@Velocity.onlyWhenTargetGoesBack
        onlyGround = this@Velocity.onlyGround
        onlyCurrentView = this@Velocity.onlyCurrentView
        disabledInAir = this@Velocity.disabledInAir
    }

    // ========== Click 模式配置 ==========
    private val clicksMin by int("ClicksMin", 2, 1..10)
    private val clicksMax by int("ClicksMax", 5, 1..10)
    private val hurtTimeToClick by int("HurtTimeToClick", 8, 0..10)
    private val whenFacingEnemyOnly by boolean("WhenFacingEnemyOnly", true)
    private val maxAngleDifference by float("MaxAngleDifference", 90f, 0f..180f, "degrees")
    private val clickRange by float("ClickRange", 3.0f, 0.0f..6.0f, "blocks")

    // ========== 内部状态 ==========
    private val delayQueue = DelayQueue()

    // ========== 入口方法 ==========
    fun onVelocityPacket(ctx: VelocityContext): PlatformCommand {
        return when (mode) {
            "Legit" -> handleLegit(ctx)
            "Delay" -> handleDelay(ctx)
            "Click" -> handleClick(ctx)
            else -> handleLegit(ctx)
        }
    }

    // ========== Legit 模式：范围随机 + 条件触发 ==========
    private fun handleLegit(ctx: VelocityContext): PlatformCommand {
        val player = ctx.player
        val target = ctx.target
        val original = ctx.originalMotion

        // 1. 统一条件检查（替换原来的六行手动 if）
        if (!ConditionChecker.check(triggerOptions, player, target)) {
            return PlatformCommand.Pass(original)
        }

        // 2. 概率检查（替换原来的 RandomRange.test(chance)）
        if (!probability.test()) return PlatformCommand.Pass(original)

        // 3. 延迟检查
        if (delayTicks > 0 || delayMs > 0) {
            delayQueue.enqueue(ctx, delayMs, delayTicks)
            return PlatformCommand.CancelPacket(ctx.packetHandle)
        }

        // 4. 采样因子 + 缩放
        val h = RandomRange.sample(horizontalMin, horizontalMax)
        val v = RandomRange.sample(verticalMin, verticalMax)
        
        val reduced = VectorOperations.scale(original, h, v, h)

        return PlatformCommand.ModifyMotion(reduced)
    }

    // ========== Delay 模式：包延迟 ==========
    private fun handleDelay(ctx: VelocityContext): PlatformCommand {
        val player = ctx.player
        val target = ctx.target
        val original = ctx.originalMotion
        
        if (!ConditionChecker.check(triggerOptions, player, target)) {
            return PlatformCommand.Pass(original)
        }
        
        delayQueue.enqueue(ctx, delayMs, delayTicks)
        return PlatformCommand.CancelPacket(ctx.packetHandle)
    }

    // ========== Click 模式：Liquid 算法逻辑 ==========
    private fun handleClick(ctx: VelocityContext): PlatformCommand {
        val player = ctx.player
        val target = ctx.target
        
        if (!ConditionChecker.check(triggerOptions, player, target)) {
            return PlatformCommand.Pass(ctx.originalMotion)
        }
        
        if (target == null) return PlatformCommand.Pass(ctx.originalMotion)

        // 1. 检测 hurtTime
        if (player.hurtTime != hurtTimeToClick) return PlatformCommand.Pass(ctx.originalMotion)

        // 2. 检测角度差
        if (whenFacingEnemyOnly && !player.isLookingAtTarget) return PlatformCommand.Pass(ctx.originalMotion)

        // 3. 检测距离
        val distance = player.position.distanceTo(target.position)
        if (distance > clickRange) return PlatformCommand.Pass(ctx.originalMotion)

        // 4. 采样点击次数
        val clicks = RandomRange.sampleInt(clicksMin, clicksMax)

        return PlatformCommand.ClickBurst(target.id, clicks)
    }

    // ========== 生命周期 ==========
    override fun onEnable() {
        EventBridge.unregisterVelocityListener()
        EventBridge.registerTickListener { currentTick ->
            if (enabled) onTick(currentTick)
        }
        EventBridge.registerVelocityListener { ctx ->
            if (enabled) onVelocityPacket(ctx) else PlatformCommand.Pass(ctx.originalMotion)
        }
    }

    override fun onDisable() {
        EventBridge.unregisterVelocityListener()
        EventBridge.unregisterTickListener(this::onTick)
        delayQueue.clear()
    }

    /**
     * Called on every tick to process delayed velocity packets
     */
    fun onTick(currentTick: Int) {
        val commands = delayQueue.pump(currentTick)
        for (command in commands) {
            when (command) {
                is PlatformCommand.ModifyMotion -> {
                    EventBridge.applyMotion(command.motion)
                }
                else -> {}
            }
        }
    }

    // ========== 内部类：延迟队列 ==========
    private inner class DelayQueue {
        private data class Entry(val ctx: VelocityContext, val releaseTick: Int)
        private val queue = mutableListOf<Entry>()
        private var tickCounter = 0

        fun enqueue(ctx: VelocityContext, delayMs: Int, delayTicks: Int) {
            val totalDelayTicks = delayTicks + (delayMs / 50)
            val releaseTick = tickCounter + totalDelayTicks
            queue.add(Entry(ctx, releaseTick))
        }

        fun pump(currentTick: Int): List<PlatformCommand> {
            tickCounter = currentTick
            val commands = mutableListOf<PlatformCommand>()
            val iterator = queue.iterator()
            
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (currentTick >= entry.releaseTick) {
                    // 延迟到期，执行缩放
                    val h = RandomRange.sample(horizontalMin, horizontalMax)
                    val v = RandomRange.sample(verticalMin, verticalMax)
                    
                    val reduced = VectorOperations.scale(entry.ctx.originalMotion, h, v, h)
                    commands.add(PlatformCommand.ModifyMotion(reduced))
                    iterator.remove()
                }
            }
            return commands
        }

        fun clear() {
            queue.clear()
        }
    }
}