package io.switchlite.adapter.common.api

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.model.VelocityContext
import io.switchlite.core.model.PlatformCommand
import io.switchlite.core.util.Vec2
import io.switchlite.core.util.Vec3

/**
 * Global EventBridge singleton.
 * Modules call this directly. Platform implementations (Forge/Fabric)
 * register themselves as the active bridge.
 */
object EventBridge {

    // ========== Velocity ==========
    private var velocityListener: ((VelocityContext) -> PlatformCommand)? = null

    fun registerVelocityListener(listener: (VelocityContext) -> PlatformCommand) {
        velocityListener = listener
    }

    fun unregisterVelocityListener() {
        velocityListener = null
    }

    fun onVelocityPacket(ctx: VelocityContext): PlatformCommand {
        return velocityListener?.invoke(ctx) ?: PlatformCommand.Pass(ctx.originalMotion)
    }

    // ========== Tick ==========
    private val tickListeners = mutableListOf<(PlayerState, TargetState?) -> Unit>()
    private var tickCounter = 0

    fun registerTickListener(listener: (PlayerState, TargetState?) -> Unit) {
        tickListeners.add(listener)
    }

    fun unregisterTickListener(listener: (PlayerState, TargetState?) -> Unit) {
        tickListeners.remove(listener)
    }

    fun onTick(player: PlayerState, target: TargetState?) {
        tickCounter++
        tickListeners.forEach { it(player, target) }
    }

    fun getCurrentTick(): Int = tickCounter

    // ========== Rotation ==========
    private var rotationSetter: ((Vec2) -> Unit)? = null

    fun setPlayerRotation(rotation: Vec2) {
        rotationSetter?.invoke(rotation)
    }

    fun registerRotationSetter(setter: (Vec2) -> Unit) {
        rotationSetter = setter
    }

    // ========== Motion ==========
    private var motionApplier: ((Vec3) -> Unit)? = null

    fun applyMotion(motion: Vec3) {
        motionApplier?.invoke(motion)
    }

    fun registerMotionApplier(applier: (Vec3) -> Unit) {
        motionApplier = applier
    }

    // ========== Key ==========
    private val keyListeners = mutableListOf<(keyCode: Int, pressed: Boolean) -> Unit>()

    fun registerKeyListener(listener: (keyCode: Int, pressed: Boolean) -> Unit) {
        keyListeners.add(listener)
    }

    fun unregisterKeyListener(listener: (keyCode: Int, pressed: Boolean) -> Unit) {
        keyListeners.remove(listener)
    }

    fun onKey(keyCode: Int, pressed: Boolean) {
        keyListeners.forEach { it(keyCode, pressed) }
    }

    // ========== Attack (Left Click) ==========
    private var attackTrigger: (() -> Unit)? = null

    fun triggerAttack() {
        attackTrigger?.invoke()
    }

    fun registerAttackTrigger(trigger: () -> Unit) {
        attackTrigger = trigger
    }

    // ========== Sprint ==========
    private var sprintSetter: ((Boolean) -> Unit)? = null

    /**
     * Set the player's sprinting state.
     * Used by 1.9+ crit logic (stop sprint before crit, restore after).
     */
    fun setSprinting(sprinting: Boolean) {
        sprintSetter?.invoke(sprinting)
    }

    fun registerSprintSetter(setter: (Boolean) -> Unit) {
        sprintSetter = setter
    }

    // ========== Item Use ==========
    private var releaseUsingItemHandler: (() -> Unit)? = null

    /**
     * Release the player's active item use (e.g. stop blocking with shield,
     * release bow draw, stop eating). Used by 1.9+ OnItemUse.STOP mode.
     */
    // TODO: 需要 EventBridge 添加 releaseUsingItem() — 当前为空实现，
    //       平台适配器 (Forge/Fabric) 需要注册实际的 releaseUsingItem handler
    fun releaseUsingItem() {
        releaseUsingItemHandler?.invoke()
    }

    fun registerReleaseUsingItemHandler(handler: () -> Unit) {
        releaseUsingItemHandler = handler
    }

    // ========== Platform Registration ==========
    // Called by ForgeBootstrap / FabricBootstrap to wire up platform-specific handlers
    fun registerPlatformHandlers(
        rotationSetter: (Vec2) -> Unit,
        motionApplier: (Vec3) -> Unit,
        sprintSetter: (Boolean) -> Unit = {}
    ) {
        this.rotationSetter = rotationSetter
        this.motionApplier = motionApplier
        this.sprintSetter = sprintSetter
    }

    fun reset() {
        velocityListener = null
        tickListeners.clear()
        rotationSetter = null
        motionApplier = null
        sprintSetter = null
        releaseUsingItemHandler = null
        keyListeners.clear()
        tickCounter = 0
    }
}
