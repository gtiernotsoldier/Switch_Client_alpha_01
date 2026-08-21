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
        velocityPacketReceivedThisTick = true
        velocityNotifiers.forEach { it(ctx) }
    }

    /** Set true by notifyVelocityPacket, cleared each tick by modules. */
    @Volatile var velocityPacketReceivedThisTick: Boolean = false

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

    /**
     * The entity currently under the player's crosshair (objectMouseOver.entityHit), filled
     * by the adapter each tick alongside [onTick]. No nearest-entity fallback. Modules that
     * act on the target the player is actually hitting (WTap/STap/AutoBlock/BlockHit/
     * SuperKnockback) read this instead of the generic `target`, matching Raven.
     */
    @Volatile var crosshairTarget: TargetState? = null

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

    // ========== Main-Thread Synthetic Input State ==========
    // Combat actions must be applied on the MC render thread, NOT the 20Hz
    // SwitchLite-Tick background thread. Modules (background thread) only write
    // these *desired* states; the platform adapter reads them on the render thread
    // (in ForgeBootstrap.render / FabricBootstrap render) and writes the real
    // KeyBinding fields there. This removes the race where a background-thread
    // key press is cleared before MC's render thread ever reads it.
    @Volatile var syntheticAttack: Boolean = false
    @Volatile var syntheticUse: Boolean = false

    /**
     * Synthetic forward/back key states, written by WTap/STap on the background tick
     * thread and applied to the real W/S KeyBinding on the render thread (in
     * ForgeEventBridge.applySyntheticInput). This puts WTap/STap's tap on the main
     * thread and lets the Keystrokes HUD flash the W/S keys. When the override flag is
     * true the adapter drives the key fully from this state.
     */
    @Volatile var syntheticForward: Boolean = false
    @Volatile var syntheticBack: Boolean = false
    @Volatile var syntheticForwardOverride: Boolean = false
    @Volatile var syntheticBackOverride: Boolean = false

    // ── Click CPS counters (Raven's mouseManager equivalent) ──
    // Recorded by the adapter on every press (synthetic or physical); read by the
    // Keystrokes HUD to display "N CPS". A 1-second sliding window.
    private val leftClickTimes = java.util.ArrayDeque<Long>()
    private val rightClickTimes = java.util.ArrayDeque<Long>()

    /** Record a left (0) or right (1) click for the CPS counter. */
    fun recordClick(button: Int) {
        val now = System.currentTimeMillis()
        val q = if (button == 0) leftClickTimes else rightClickTimes
        synchronized(q) {
            q.addLast(now)
            while (q.isNotEmpty() && q.first() < now - 1000L) q.removeFirst()
            if (q.size > 64) q.removeFirst()
        }
    }

    /** Clicks in the last second for the left button (0..n). */
    fun leftCps(): Int = synchronized(leftClickTimes) {
        val now = System.currentTimeMillis()
        while (leftClickTimes.isNotEmpty() && leftClickTimes.first() < now - 1000L) leftClickTimes.removeFirst()
        leftClickTimes.size
    }

    /** Clicks in the last second for the right button (0..n). */
    fun rightCps(): Int = synchronized(rightClickTimes) {
        val now = System.currentTimeMillis()
        while (rightClickTimes.isNotEmpty() && rightClickTimes.first() < now - 1000L) rightClickTimes.removeFirst()
        rightClickTimes.size
    }

    /**
     * CPS range the active full clicker wants. Written by AutoClicker on enable so
     * the platform adapter can generate a smooth press/release cadence (Raven-style
     * time-based) instead of one-shot pulses, eliminating click stutter at low tick
     * resolution. [minCps, maxCps]; ignored unless > 0.
     */
    @Volatile var clickMinCps: Int = 0
    @Volatile var clickMaxCps: Int = 0

    /**
     * Whether a fully-automatic clicker (AutoClicker/TriggerBot) is active.
     * When true, the attack key is fully driven by [syntheticAttack] (creating the
     * CPS press/release rhythm) and is NOT OR-ed with the physical mouse button —
     * otherwise the physical button being held would keep `pressed` stuck true and
     * swallow the cadence. Assist modules (ClickAssist/BlockHit/AutoBlock) leave
     * this false so they augment the player's own input via the OR path.
     */
    @Volatile var syntheticAttackOverride: Boolean = false

    /** Same as [syntheticAttackOverride] but for the use-item (right-click) key. */
    @Volatile var syntheticUseOverride: Boolean = false

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

    // ========== Click Delay Reset (DelayRemover — 1.8 exclusive) ==========
    private var resetClickDelayHandler: (() -> Unit)? = null

    fun resetClickDelay() { resetClickDelayHandler?.invoke() }
    fun registerResetClickDelayHandler(handler: () -> Unit) { resetClickDelayHandler = handler }

    // ========== Jump Delay Reset (NoJumpDelay — Movement) ==========
    private var resetJumpDelayHandler: (() -> Unit)? = null

    fun resetJumpDelay() { resetJumpDelayHandler?.invoke() }
    fun registerResetJumpDelayHandler(handler: () -> Unit) { resetJumpDelayHandler = handler }

    // ========== Render Offset (ParallaxStrike — Player) ==========
    @Volatile var renderOffsetX: Float = 0f
    @Volatile var renderOffsetY: Float = 0f
    @Volatile var renderOffsetZ: Float = 0f

    /** Clear all render offsets (modules call this, adapter reads on render). */
    fun clearRenderOffset() {
        renderOffsetX = 0f; renderOffsetY = 0f; renderOffsetZ = 0f
    }

    // ========== Hotbar Slot Switching (AutoTool — Player) ==========
    private var switchSlotHandler: ((Int) -> Unit)? = null  // silent switch to slot
    private var getBestSlotHandler: (() -> Int)? = null      // find best tool in hotbar

    fun switchToSlot(slot: Int) { switchSlotHandler?.invoke(slot) }
    fun getBestSlot(): Int = getBestSlotHandler?.invoke() ?: -1
    fun registerSwitchSlotHandler(handler: (Int) -> Unit) { switchSlotHandler = handler }
    fun registerGetBestSlotHandler(handler: () -> Int) { getBestSlotHandler = handler }

    // ========== Sneak Key (Eagle — Player) ==========
    private var pressSneakHandler: (() -> Unit)? = null
    private var releaseSneakHandler: (() -> Unit)? = null
    private var edgeDetector: (() -> Boolean)? = null

    fun pressSneak() { pressSneakHandler?.invoke() }
    fun releaseSneak() { releaseSneakHandler?.invoke() }
    fun isOnBlockEdge(): Boolean = edgeDetector?.invoke() ?: false
    fun registerPressSneakHandler(handler: () -> Unit) { pressSneakHandler = handler }
    fun registerReleaseSneakHandler(handler: () -> Unit) { releaseSneakHandler = handler }
    fun registerEdgeDetector(handler: () -> Boolean) { edgeDetector = handler }

    // ========== Module Cross-Check (BridgeAssist ↔ SafeWalk) ==========
    @Volatile var isSafeWalkEnabled: Boolean = false

    // ========== Rotation (BridgeAssist, AimAssist) ==========
    private var rotationApplier: ((Float, Float) -> Unit)? = null

    fun setPlayerRotation(yaw: Float, pitch: Float) { rotationApplier?.invoke(yaw, pitch) }
    fun registerRotationApplier(handler: (Float, Float) -> Unit) { rotationApplier = handler }

    // ========== Render Overrides (NoFOV, NoHurtCam — Render) ==========
    private var resetHurtCamHandler: (() -> Unit)? = null
    private var resetFovModifierHandler: (() -> Unit)? = null

    fun resetHurtCam() { resetHurtCamHandler?.invoke() }
    fun resetFovModifier() { resetFovModifierHandler?.invoke() }
    fun registerResetHurtCamHandler(handler: () -> Unit) { resetHurtCamHandler = handler }
    fun registerResetFovModifierHandler(handler: () -> Unit) { resetFovModifierHandler = handler }

    // ========== Gamma (Fullbright — Render) ==========
    private var gammaSetter: ((Float) -> Unit)? = null

    fun setGamma(value: Float) { gammaSetter?.invoke(value) }
    fun registerGammaSetter(handler: (Float) -> Unit) { gammaSetter = handler }

    // ========== Right-Click Delay (FastPlace — World) ==========
    private var rightClickDelayHandler: ((Int) -> Unit)? = null

    fun setRightClickDelay(ticks: Int) { rightClickDelayHandler?.invoke(ticks) }
    fun registerRightClickDelayHandler(handler: (Int) -> Unit) { rightClickDelayHandler = handler }

    // ========== Team Detection (Teams — Player) ==========
    private var scoreboardTeamChecker: ((String) -> String?)? = null
    private var displayNameProvider: ((String) -> String)? = null
    private var armorColorChecker: ((String) -> Int)? = null

    fun getScoreboardTeam(name: String): String? = scoreboardTeamChecker?.invoke(name)
    fun getDisplayName(name: String): String = displayNameProvider?.invoke(name) ?: name
    fun getArmorDyeColor(name: String): Int = armorColorChecker?.invoke(name) ?: -1
    fun registerScoreboardTeamChecker(handler: (String) -> String?) { scoreboardTeamChecker = handler }
    fun registerDisplayNameProvider(handler: (String) -> String) { displayNameProvider = handler }
    fun registerArmorColorChecker(handler: (String) -> Int) { armorColorChecker = handler }

    // ========== Entity Info (AntiBot — Player) ==========
    private var entityTicksProvider: ((String) -> Int)? = null
    private var entityOnGroundChecker: ((String) -> Boolean)? = null

    fun getEntityTicksExisted(name: String): Int = entityTicksProvider?.invoke(name) ?: 0
    fun isEntityOnGround(name: String): Boolean = entityOnGroundChecker?.invoke(name) ?: false
    fun registerEntityTicksProvider(handler: (String) -> Int) { entityTicksProvider = handler }
    fun registerEntityOnGroundChecker(handler: (String) -> Boolean) { entityOnGroundChecker = handler }

    // ========== HUD Text (HUD — Render) ==========
    @Volatile var hudTextLine: String = ""

    // ========== GUI Mouse State (populated by platform render hook for HUD drag) ==========
    // Scaled GUI coordinates (left-top origin, y down).
    @Volatile var guiMouseX: Int = 0
    @Volatile var guiMouseY: Int = 0
    /** Physical left mouse button state (for HUD click/drag detection). */
    @Volatile var guiLeftMouseDown: Boolean = false

    /**
     * Whether an MC GUI screen is currently open (pause menu, inventory, chat, etc.).
     * Populated by the platform adapter on the render thread. Used by draggable HUD
     * widgets (Keystrokes) so drag only engages while the game is paused/in a GUI —
     * never while fighting with the mouse held.
     */
    @Volatile var isGuiOpen: Boolean = false

    // ========== GUI Notifications (Render — right-corner toast) ==========

    data class Notification(
        val text: String,
        val type: NotificationType = NotificationType.INFO
    )

    enum class NotificationType {
        SUCCESS,  // green — module enabled / injection success
        ERROR,    // red — module disabled / injection failure
        INFO      // gold — general info
    }

    private val notificationQueue = mutableListOf<Notification>()

    fun pushNotification(text: String, type: NotificationType = NotificationType.INFO) {
        synchronized(notificationQueue) {
            // Cap at 5 to prevent overflow
            if (notificationQueue.size >= 5) {
                notificationQueue.removeAt(0)
            }
            notificationQueue.add(Notification(text, type))
        }
    }

    /** Drain all pending notifications (called by adapter render hook each frame). */
    fun drainNotifications(): List<Notification> {
        return synchronized(notificationQueue) {
            val copy = notificationQueue.toList()
            notificationQueue.clear()
            copy
        }
    }

    // ========== Reach (Reach — 1.8 exclusive) ==========
    private var reachSetter: ((Float) -> Unit)? = null

    fun setReach(distance: Float) { reachSetter?.invoke(distance) }
    fun registerReachSetter(handler: (Float) -> Unit) { reachSetter = handler }

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

    /**
     * Effective left mouse button state as MC sees it — physical OR synthetic. Updated on
     * the render thread. This reflects AutoClicker's click pulse (via the Mouse.buttons
     * buffer), so the Keystrokes HUD's LMB/RMB keys flash with the CPS rhythm like Raven.
     */
    @Volatile var mouseButton0: Boolean = false

    /** Effective right mouse button state (physical OR synthetic), render-thread updated. */
    @Volatile var mouseButton1: Boolean = false

    /** Whether the player's crosshair is currently pointing at a block. */
    @Volatile var isLookingAtBlock: Boolean = false

    /** Whether the player is in water, lava, or cobweb. */
    @Volatile var isInFluid: Boolean = false

    /** Player's food level (0-20). Vanilla sprint cancels at <= 6. */
    @Volatile var foodLevel: Int = 20

    // ========== WASD Key States (StrafeFix) ==========
    @Volatile var isKeyForwardDown: Boolean = false
    @Volatile var isKeyBackDown: Boolean = false
    @Volatile var isKeyLeftDown: Boolean = false
    @Volatile var isKeyRightDown: Boolean = false

    /**
     * TRUE physical forward/back key state read via LWJGL Keyboard.isKeyDown(keyCode),
     * NOT from KeyBinding.pressed (which the tap modules' override can rewrite). Used by
     * WTap/STap to decide whether the player is actually holding W, matching Raven's
     * `Keyboard.isKeyDown(keyBindForward.getKeyCode())`. Unaffected by synthetic taps.
     */
    @Volatile var physicalForwardDown: Boolean = false
    @Volatile var physicalBackDown: Boolean = false

    /** Jump (space) key state — used by the Keystrokes HUD. */
    @Volatile var isKeyJumpDown: Boolean = false

    // ========== Input Snapshots (NoMouseFix, NoKeyboardFix) ==========
    @Volatile var snapMouseDeltaX: Float = 0f
    @Volatile var snapMouseDeltaY: Float = 0f
    @Volatile var snapMouseSensitivity: Float = 1.0f
    @Volatile var snapKeyForward: Boolean = false
    @Volatile var snapKeyBack: Boolean = false
    @Volatile var snapKeyLeft: Boolean = false
    @Volatile var snapKeyRight: Boolean = false
    @Volatile var snapKeyAttack: Boolean = false

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
        startTickListeners.clear()
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
        resetClickDelayHandler = null
        resetJumpDelayHandler = null
        reachSetter = null
        attackTrigger = null
        cancelAttackHandler = null
        syntheticAttack = false
        syntheticUse = false
        syntheticAttackOverride = false
        syntheticUseOverride = false
        syntheticForward = false
        syntheticBack = false
        syntheticForwardOverride = false
        syntheticBackOverride = false
        switchSlotHandler = null
        getBestSlotHandler = null
        pressSneakHandler = null
        releaseSneakHandler = null
        edgeDetector = null
        isSafeWalkEnabled = false
        rotationApplier = null
        resetHurtCamHandler = null
        resetFovModifierHandler = null
        gammaSetter = null
        rightClickDelayHandler = null
        scoreboardTeamChecker = null
        displayNameProvider = null
        armorColorChecker = null
        entityTicksProvider = null
        entityOnGroundChecker = null
        hudTextLine = ""
        guiMouseX = 0
        guiMouseY = 0
        guiLeftMouseDown = false
        isGuiOpen = false
        synchronized(notificationQueue) { notificationQueue.clear() }
        keyListeners.clear()
        tickCounter = 0
        crosshairTarget = null
        mouseDeltaX = 0f
        mouseDeltaY = 0f
        mouseSensitivity = 1.0f
        isRightMousePhysicallyDown = false
        isLeftMousePhysicallyDown = false
        isLookingAtBlock = false
        isInFluid = false
        foodLevel = 20
        isKeyForwardDown = false
        isKeyBackDown = false
        isKeyLeftDown = false
        isKeyRightDown = false
        isKeyJumpDown = false
        velocityPacketReceivedThisTick = false
        renderOffsetX = 0f
        renderOffsetY = 0f
        renderOffsetZ = 0f
        // Snapshot fields
        snapMouseDeltaX = 0f
        snapMouseDeltaY = 0f
        snapMouseSensitivity = 1.0f
        snapKeyForward = false
        snapKeyBack = false
        snapKeyLeft = false
        snapKeyRight = false
        snapKeyAttack = false
    }
}
