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

    // ========== Platform Registration ==========
    // Called by ForgeBootstrap / FabricBootstrap to wire up platform-specific handlers
    fun registerPlatformHandlers(
        rotationSetter: (Vec2) -> Unit,
        motionApplier: (Vec3) -> Unit
    ) {
        this.rotationSetter = rotationSetter
        this.motionApplier = motionApplier
    }

    fun reset() {
        velocityListener = null
        tickListeners.clear()
        rotationSetter = null
        motionApplier = null
        tickCounter = 0
    }
}
