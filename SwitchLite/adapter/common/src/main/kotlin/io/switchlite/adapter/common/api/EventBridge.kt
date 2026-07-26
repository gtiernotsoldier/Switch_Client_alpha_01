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
    private val velocityNotifiers = mutableListOf<(VelocityContext) -> Unit>()

    fun registerVelocityListener(listener: (VelocityContext) -> PlatformCommand) {
        velocityListener = listener
    }

    fun unregisterVelocityListener() {
        velocityListener = null
    }

    /** Register a passive observer of velocity packets (does not return a command). */
    fun registerVelocityNotifier(notifier: (VelocityContext) -> Unit) {
        velocityNotifiers.add(notifier)
    }

    fun unregisterVelocityNotifier(notifier: (VelocityContext) -> Unit) {
        velocityNotifiers.remove(notifier)
    }

    fun notifyVelocityPacket(ctx: VelocityContext) {
        velocityNotifiers.forEach { it(ctx) }
    }

    // ========== PreTick (START phase, before game processes input) ==========
    private val startTickListeners = mutableListOf<(PlayerState, TargetState?) -> Unit>()

    fun registerStartTickListener(listener: (PlayerState, TargetState?) -> Unit) {
        startTickListeners.add(listener)
    }

    fun unregisterStartTickListener(listener: (PlayerState, TargetState?) -> Unit) {
        startTickListeners.remove(listener)
    }

    fun onStartTick(player: PlayerState, target: TargetState?) {
        startTickListeners.forEach { it(player, target) }
    }

    fun onVelocityPacket(ctx: VelocityContext): PlatformCommand {
        return velocityListener?.invoke(ctx) ?: PlatformCommand.Pass(ctx.originalMotion)
    }

    // ========== Tick ==========
    private val tickListeners = mutableListOf<(PlayerState, TargetState?) -> Unit>()
    private val simpleTickListeners = mutableListOf<(Int) -> Unit>()
    private var tickCounter = 0

    fun registerTickListener(listener: (PlayerState, TargetState?) -> Unit) {
        tickListeners.add(listener)
    }

    fun unregisterTickListener(listener: (PlayerState, TargetState?) -> Unit) {
        tickListeners.remove(listener)
    }

    fun registerTickListener(listener: (Int) -> Unit) {
        simpleTickListeners.add(listener)
    }

    fun unregisterTickListener(listener: (Int) -> Unit) {
        simpleTickListeners.remove(listener)
    }

    fun onTick(player: PlayerState, target: TargetState?) {
        tickCounter++
        tickListeners.forEach { it(player, target) }
        simpleTickListeners.forEach { it(tickCounter) }
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

    /**
     * Trigger a left-click attack from the module layer.
     *
     * ⚠️ Anti-cheat requirement for Forge/Fabric adapter implementations:
     *   The registered trigger MUST simulate the attack through the client input pipeline,
     *   NOT by calling MCP/Forge `attackEntity()` or sending C02 packets directly.
     *
     *   Correct:  `Minecraft.getMinecraft().gameSettings.keyBindAttack.pressed = true`
     *   (followed by `pressed = false` on next tick or in the same tick after processing).
     *
     *   Wrong:    `player.attackEntity(target)` — bypasses LWJGL input queue,
     *             invisible to client-side anti-cheat input pipeline monitors.
     *
     *   Rationale: Client anti-cheat hooks the LWJGL input queue. Using KeyBinding.setKeyBindState
     *   (or setting `.pressed` directly) ensures the event appears to originate from the
     *   input pipeline, satisfying both client-side input monitoring AND memory state checks.
     */
    fun triggerAttack() {
        attackTrigger?.invoke()
    }

    fun registerAttackTrigger(trigger: () -> Unit) {
        attackTrigger = trigger
    }

    // ========== Cancel Attack (HitSelect) ==========
    private var cancelAttackHandler: (() -> Unit)? = null

    fun cancelAttack() { cancelAttackHandler?.invoke() }

    fun registerCancelAttackHandler(handler: () -> Unit) { cancelAttackHandler = handler }

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

    // ========== Mouse Delta (for Self-adaptive AimAssist) ==========
    // Set by ForgeBootstrap / FabricBootstrap each tick before onTick().

    /** Raw mouse delta X this frame (pixels, screen space). */
    @Volatile var mouseDeltaX: Float = 0f
    /** Raw mouse delta Y this frame (pixels, screen space). */
    @Volatile var mouseDeltaY: Float = 0f
    /** Player's configured mouse sensitivity (from game options). */
    @Volatile var mouseSensitivity: Float = 1.0f

    /** Whether the physical right mouse button is currently held (not synthetic). */
    @Volatile var isRightMousePhysicallyDown: Boolean = false

    /** Whether the physical left mouse button is currently held (not synthetic). */
    @Volatile var isLeftMousePhysicallyDown: Boolean = false

    /** Whether the player's crosshair is currently pointing at a block. */
    @Volatile var isLookingAtBlock: Boolean = false

    /** Whether the player is in water, lava, or cobweb. */
    @Volatile var isInFluid: Boolean = false

    // ========== Item Use ==========
    private var releaseUsingItemHandler: (() -> Unit)? = null
    private var pressUseItemHandler: (() -> Unit)? = null

    /**
     * Release the player's active item use (e.g. stop blocking with shield,
     * release bow draw, stop eating). Used by 1.9+ OnItemUse.STOP mode.
     */
    fun releaseUsingItem() {
        releaseUsingItemHandler?.invoke()
    }

    fun registerReleaseUsingItemHandler(handler: () -> Unit) {
        releaseUsingItemHandler = handler
    }

    /**
     * Press the use-item key (right-click). Used by AutoBlock module.
     * Simulates right-click through the input pipeline.
     */
    fun pressUseItem() {
        pressUseItemHandler?.invoke()
    }

    fun registerPressUseItemHandler(handler: () -> Unit) {
        pressUseItemHandler = handler
    }

    // ========== Forward Key (WTap) ==========
    private var pressForwardHandler: (() -> Unit)? = null
    private var releaseForwardHandler: (() -> Unit)? = null

    fun pressForward() { pressForwardHandler?.invoke() }
    fun releaseForward() { releaseForwardHandler?.invoke() }

    fun registerPressForwardHandler(handler: () -> Unit) { pressForwardHandler = handler }
    fun registerReleaseForwardHandler(handler: () -> Unit) { releaseForwardHandler = handler }

    // ========== Back Key (STap) ==========
    private var pressBackHandler: (() -> Unit)? = null
    private var releaseBackHandler: (() -> Unit)? = null

    fun pressBack() { pressBackHandler?.invoke() }
    fun releaseBack() { releaseBackHandler?.invoke() }

    fun registerPressBackHandler(handler: () -> Unit) { pressBackHandler = handler }
    fun registerReleaseBackHandler(handler: () -> Unit) { releaseBackHandler = handler }

    fun registerCancelAttackHandler(handler: () -> Unit) { cancelAttackHandler = handler }

    // ========== Sprint Reset Packets (SprintReset — 1.8 exclusive) ==========
    private var sprintResetHandler: ((String) -> Unit)? = null

    fun sendSprintReset(mode: String) { sprintResetHandler?.invoke(mode) }
    fun registerSprintResetHandler(handler: (String) -> Unit) { sprintResetHandler = handler }

    // ========== Jump (JumpReset) ==========
    private var jumpHandler: (() -> Unit)? = null

    fun jump() { jumpHandler?.invoke() }
    fun registerJumpHandler(handler: () -> Unit) { jumpHandler = handler }

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
        velocityNotifiers.clear()
        tickListeners.clear()
        simpleTickListeners.clear()
        rotationSetter = null
        motionApplier = null
        sprintSetter = null
        releaseUsingItemHandler = null
        pressUseItemHandler = null
        pressForwardHandler = null
        releaseForwardHandler = null
        pressBackHandler = null
        releaseBackHandler = null
        jumpHandler = null
        sprintResetHandler = null
        attackTrigger = null
        cancelAttackHandler = null
        keyListeners.clear()
        tickCounter = 0
        mouseDeltaX = 0f
        mouseDeltaY = 0f
        mouseSensitivity = 1.0f
        isRightMousePhysicallyDown = false
        isLeftMousePhysicallyDown = false
        isLookingAtBlock = false
        isInFluid = false
    }
}
