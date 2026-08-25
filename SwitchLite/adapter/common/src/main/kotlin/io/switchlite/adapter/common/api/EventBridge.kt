package io.switchlite.adapter.common.api

import io.switchlite.core.logging.CoreLogger
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

    // ========== 1. Lifecycle & Tick ==========

    // ========== PreTick (START phase, before game processes input) ==========
    private val startTickListeners = mutableListOf<(PlayerState, TargetState?) -> Unit>()

    fun registerStartTickListener(listener: (PlayerState, TargetState?) -> Unit) {
        startTickListeners.add(listener)
    }

    fun unregisterStartTickListener(listener: (PlayerState, TargetState?) -> Unit) {
        startTickListeners.remove(listener)
    }

    fun onStartTick(player: PlayerState, target: TargetState?) {
        // Iterate a copy: modules enable/disable (and thus register/unregister listeners) from
        // other threads; iterating the live list here could throw ConcurrentModificationException.
        startTickListeners.toList().forEach { it(player, target) }
    }

    // ========== Tick ==========
    private val tickListeners = mutableListOf<(PlayerState, TargetState?) -> Unit>()
    private val simpleTickListeners = mutableListOf<(Int) -> Unit>()
    private var tickCounter = 0
    private var tickDiagCount = 0

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
        // Throttled dispatch heartbeat — tells us whether module tick dispatch actually runs
        // on the user's machine and whether the player failed to extract (EMPTY).
        if (++tickDiagCount % 60 == 0) {
            CoreLogger.info(
                "[EventBridge] dispatch #$tickCounter listeners=${tickListeners.size} " +
                "playerEmpty=${player === PlayerState.EMPTY} sword=${player.weaponType}")
        }
        tickListeners.toList().forEach { it(player, target) }
        simpleTickListeners.toList().forEach { it(tickCounter) }
    }

    fun getCurrentTick(): Int = tickCounter

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
        attackListeners.clear()
        sprintResetActive = false
        superKnockbackActive = false
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
        releaseJumpHandler = null
        pendingJump = false
        jumpPressedSince = 0L
        sprintResetHandler = null
        sendEntityActionHandler = null
        serverSprintState = false
        resetClickDelayHandler = null
        resetJumpDelayHandler = null
        reachSetter = null
        reachRaycast = null
        attackTrigger = null
        cancelAttackHandler = null
        syntheticAttack = false
        syntheticUse = false
        attackGateProvider = null
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
        desiredRotationYaw = null
        desiredRotationPitch = null
        desiredRotationFractionY = 0.2f
        desiredRotationFractionP = 0.1f
        aimOffsetYaw = 0f
        aimOffsetPitch = 0f
        aimAnimActive = false
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
        velocityModified = false
        lastKnockbackNano = 0L
        lastKbOriginalSpeed = 0.0
        lastKbModifiedSpeed = 0.0
        lastKbMotionX = 0.0
        lastKbMotionY = 0.0
        lastKbMotionZ = 0.0
        entityVelocityNotifiers.clear()
        entityPositionProvider = null
        forwardRayTargetProvider = null
        fovNearestTargetProvider = null
        targetFilterPlayers = true
        targetFilterMobs = true
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

    // ========== 2. Combat: Attack & Knockback ==========

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
        velocityNotifiers.toList().forEach { it(ctx) }
    }

    /** Set true by notifyVelocityPacket, cleared each tick by modules. */
    @Volatile var velocityPacketReceivedThisTick: Boolean = false

    // ========== Entity Velocity (S12 for OTHER entities — KnockbackDisplay dealt-KB) ==========
    // The packet interceptor feeds every non-player S12 here (Netty thread, read-only). Modules
    // register to observe knockbacks applied to OTHER entities — KnockbackDisplay correlates them
    // with the player's own attacks to show "how much KB did I deal".
    private val entityVelocityNotifiers = mutableListOf<(entityId: Int, motion: Vec3) -> Unit>()

    fun registerEntityVelocityNotifier(notifier: (Int, Vec3) -> Unit) {
        entityVelocityNotifiers.add(notifier)
    }

    fun unregisterEntityVelocityNotifier(notifier: (Int, Vec3) -> Unit) {
        entityVelocityNotifiers.remove(notifier)
    }

    /** Called by the adapter (Netty thread) when an S12 velocity packet targets a non-player entity. */
    fun notifyEntityVelocity(entityId: Int, motion: Vec3) {
        entityVelocityNotifiers.toList().forEach { it(entityId, motion) }
    }

    /**
     * Whether the last velocity packet was modified or cancelled by the Velocity module. Set by the
     * module on each packet; read by the VelocityDisplay HUD to color the readout (modified → accent,
     * untouched → vanilla). False when Velocity is disabled or the packet passed through unchanged.
     */
    @Volatile var velocityModified: Boolean = false

    /**
     * Timestamp (System.nanoTime) of the last knockback packet that affected this player. Set by the
     * Velocity module / interceptor; read by the JumpTiming HUD to measure jump timing vs knockback.
     */
    @Volatile var lastKnockbackNano: Long = 0L

    /**
     * Knockback coefficient data from the last velocity packet — original horizontal speed and the
     * modified horizontal speed (after Velocity reduction). Read by VelocityDisplay / KnockbackDisplay
     * to show the retained/cut percentages. 0/0 when none yet.
     */
    @Volatile var lastKbOriginalSpeed: Double = 0.0
    @Volatile var lastKbModifiedSpeed: Double = 0.0

    /**
     * The original (pre-reduction) knockback MOTION vector from the last velocity packet, in
     * blocks/tick. Read by the KnockbackDisplay HUD to show the raw "KB x y z" the player was
     * hit with (the Velocity cut % is shown next to it). 0/0/0 when none yet.
     */
    @Volatile var lastKbMotionX: Double = 0.0
    @Volatile var lastKbMotionY: Double = 0.0
    @Volatile var lastKbMotionZ: Double = 0.0

    /**
     * Set by the Velocity module when it processes a knockback packet: records the original
     * motion vector, the (after-reduction) horizontal speed, plus a fresh timestamp.
     */
    fun recordKnockback(originalMotion: Vec3, modifiedSpeed: Double) {
        lastKbMotionX = originalMotion.x
        lastKbMotionY = originalMotion.y
        lastKbMotionZ = originalMotion.z
        lastKbOriginalSpeed = kotlin.math.sqrt(originalMotion.x * originalMotion.x + originalMotion.z * originalMotion.z)
        lastKbModifiedSpeed = modifiedSpeed
        lastKnockbackNano = System.nanoTime()
    }

    fun onVelocityPacket(ctx: VelocityContext): PlatformCommand {
        return velocityListener?.invoke(ctx) ?: PlatformCommand.Pass(ctx.originalMotion)
    }

    // ========== Attack Event (SuperKnockback — ported LB semantics) ==========
    // We don't hook PlayerControllerMP.attackEntity; instead the adapter feeds us a "fresh hit"
    // notification via the target's hurtTime rising edge (same reliable signal SprintReset uses).
    // Modules register here to act at attack time. The adapter calls notifyAttack(target) when it
    // detects a fresh hit.
    private val attackListeners = mutableListOf<(TargetState?) -> Unit>()

    fun registerAttackListener(listener: (TargetState?) -> Unit) {
        attackListeners.add(listener)
    }

    fun unregisterAttackListener(listener: (TargetState?) -> Unit) {
        attackListeners.remove(listener)
    }

    /**
     * Called by the adapter each tick when the crosshair target just got hit (hurtTime rising edge).
     * Dispatches to registered attack listeners (SuperKnockback uses this).
     */
    fun notifyAttack(target: TargetState?) {
        attackListeners.toList().forEach { it(target) }
    }

    // ========== Sprint Coordination (SuperKnockback + SprintReset) ==========
    // Both modules send sprint packets on a hit. To keep max knockback advantage, SprintReset
    // runs first and SuperKnockback offsets its action by one tick so the two never fire a C0B
    // burst in the same tick. Modules set these flags on enable/disable.
    @Volatile private var sprintResetActive: Boolean = false
    @Volatile private var superKnockbackActive: Boolean = false

    /** SprintReset calls this from onEnable/onDisable. */
    fun setSprintResetActive(active: Boolean) { sprintResetActive = active }
    /** SuperKnockback calls this from onEnable/onDisable. */
    fun setSuperKnockbackActive(active: Boolean) { superKnockbackActive = active }

    /** Whether both sprint modules are enabled (i.e. coordination is required). */
    fun isSprintCoordinationActive(): Boolean = sprintResetActive && superKnockbackActive

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

    /**
     * Server-side sprint state mirror (LiquidBounce's `serverSprintState`). Tracks what the
     * server believes about the player's sprint state, kept in sync via the C0B entity-action
     * packets (STOP_SPRINTING / START_SPRINTING). Modules like SuperKnockback (SprintTap) rely
     * on this to decide whether a stop/start actually toggles anything.
     */
    @Volatile var serverSprintState: Boolean = false

    // ========== Sprint Reset Packets (SprintReset — 1.8 exclusive) ==========
    private var sprintResetHandler: ((String) -> Unit)? = null

    /**
     * Queued sprint-reset mode to send. The module (background 20Hz thread) sets this; the
     * platform's render thread drains it (see [drainPendingSprintReset]) so the packet is sent
     * on the MC main thread — NetworkManager.addToSendQueue is not strictly thread-safe, and
     * sending from the main thread is safer / more compliant.
     */
    @Volatile private var pendingSprintResetMode: String? = null

    /** Called by the module on the background thread: queue a sprint reset to send. */
    fun sendSprintReset(mode: String) {
        pendingSprintResetMode = mode
    }

    /** Called by the platform's MAIN (render) thread each frame: send the queued reset if any. */
    fun drainPendingSprintReset() {
        val mode = pendingSprintResetMode ?: return
        pendingSprintResetMode = null
        sprintResetHandler?.invoke(mode)
    }

    fun registerSprintResetHandler(handler: (String) -> Unit) { sprintResetHandler = handler }

    // ========== Generic C0B Entity-Action Packet (SuperKnockback — 1.8 exclusive) ==========
    // Sends a C0BPacketEntityAction with an arbitrary action name (e.g. "START_SPRINTING",
    // "STOP_SPRINTING", "START_SNEAKING", "STOP_SNEAKING"). Used by SuperKnockback's
    // Old / SneakPacket modes (ported from LiquidBounce).
    private var sendEntityActionHandler: ((String) -> Unit)? = null

    /** Send a C0BPacketEntityAction for the given action name. */
    fun sendEntityAction(action: String) { sendEntityActionHandler?.invoke(action) }

    /** Send a burst of C0B actions in order (same packet behavior as LiquidBounce sendPackets). */
    fun sendEntityActions(vararg actions: String) {
        sendEntityActionHandler?.let { h -> actions.forEach { h(it) } }
    }

    fun registerEntityActionHandler(handler: (String) -> Unit) { sendEntityActionHandler = handler }

    // ========== Jump (JumpReset) ==========
    private var jumpHandler: (() -> Unit)? = null

    /**
     * Queued jump pulse. The module (background 20Hz thread) calls [queueJump]; the platform's
     * MAIN thread drains it via [drainPendingJump] and presses the jump key (KeyBinding) so MC's
     * own tick sees it and performs the jump. This keeps the real jump key write on the main thread
     * (no cross-thread entity mutation) and makes the Keystrokes HUD reflect it (it reads the same
     * key state). The pulse auto-releases after [JUMP_PRESS_MS] on the main thread.
     */
    @Volatile private var pendingJump: Boolean = false
    @Volatile private var jumpPressedSince: Long = 0L

    /** How long the jump key stays pressed per pulse (ms). */
    private const val JUMP_PRESS_MS = 80L

    /** Called by the module on the background thread: queue a jump to fire. */
    fun queueJump() {
        pendingJump = true
    }

    /** Whether a JumpReset pulse is currently holding the jump key (main-thread read). */
    fun isJumpPulseActive(): Boolean = jumpPressedSince != 0L

    /**
     * Called by the platform's MAIN (render) thread each frame: apply a queued jump press, and
     * auto-release it after the pulse window. Returns whether the jump key is currently pressed.
     */
    fun drainPendingJump(): Boolean {
        val now = System.currentTimeMillis()
        if (pendingJump) {
            pendingJump = false
            jumpPressedSince = now
            jumpHandler?.invoke() // press jump key (main thread)
            return true
        }
        if (jumpPressedSince != 0L && now - jumpPressedSince >= JUMP_PRESS_MS) {
            jumpPressedSince = 0L
            releaseJumpHandler?.invoke() // release jump key (main thread)
        }
        return jumpPressedSince != 0L
    }

    private var releaseJumpHandler: (() -> Unit)? = null
    fun registerJumpHandler(handler: () -> Unit) { jumpHandler = handler }
    fun registerReleaseJumpHandler(handler: () -> Unit) { releaseJumpHandler = handler }

    // ========== Crosshair Target ==========
    /**
     * The entity currently under the player's crosshair (objectMouseOver.entityHit), filled
     * by the adapter each tick alongside [onTick]. No nearest-entity fallback. Modules that
     * act on the target the player is actually hitting (WTap/STap/AutoBlock/BlockHit/
     * SuperKnockback) read this instead of the generic `target`, matching Raven.
     */
    @Volatile var crosshairTarget: TargetState? = null

    // ========== Forward Ray Target (HitSelect — player's forward line, not objectMouseOver) ==========
    // objectMouseOver.entityHit is unreliable mid-fight (the crosshair can briefly leave the
    // entity). HitSelect instead uses a dedicated forward raycast from the player's eyes along the
    // look direction (like Reach / JumpReset) and acts on whatever entity that line hits.
    private var forwardRayTargetProvider: (() -> TargetState?)? = null

    fun getForwardRayTarget(): TargetState? = forwardRayTargetProvider?.invoke()
    fun registerForwardRayTargetProvider(provider: () -> TargetState?) { forwardRayTargetProvider = provider }

    // ========== FOV Nearest Target (AimAssist — Nemui-style selection) ==========
    // AimAssist pulls toward the nearest entity that lies inside its FOV cone + range, mirroring
    // Nemui's getValidTarget (which picks the nearest entity within FOV). Unlike the generic tick
    // target (crosshair-first), this gives AimAssist a target even when the crosshair is slightly
    // off the entity, enabling the "pull-back" behavior.
    private var fovNearestTargetProvider: ((fov: Float, range: Float) -> TargetState?)? = null

    fun getFovNearestTarget(fov: Float, range: Float): TargetState? = fovNearestTargetProvider?.invoke(fov, range)
    fun registerFovNearestTargetProvider(provider: (fov: Float, range: Float) -> TargetState?) { fovNearestTargetProvider = provider }

    // ========== 3. Input: Keys & Synthetic ==========

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

    // ========== Attack Gate (HitSelect — decided on the MAIN thread at click time) ==========
    // A click selector must decide "swallow this click or not" at the exact instant of the click.
    // The 20Hz background tick is too slow (a click happens and is gone within ~10-20ms). So the
    // selector registers a provider that the platform calls on the render thread right before the
    // attack key is written — returning true = let the click through, false = swallow it.
    private var attackGateProvider: (() -> Boolean)? = null

    fun registerAttackGateProvider(provider: (() -> Boolean)?) { attackGateProvider = provider }
    fun currentAttackGate(): Boolean = attackGateProvider?.invoke() ?: true

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

    // ========== 4. Motion & Actions ==========

    // ========== Motion ==========
    private var motionApplier: ((Vec3) -> Unit)? = null

    fun applyMotion(motion: Vec3) {
        motionApplier?.invoke(motion)
    }

    fun registerMotionApplier(applier: (Vec3) -> Unit) {
        motionApplier = applier
    }

    // ========== Rotation ==========
    private var rotationSetter: ((Vec2) -> Unit)? = null

    fun setPlayerRotation(rotation: Vec2) {
        rotationSetter?.invoke(rotation)
    }

    fun registerRotationSetter(setter: (Vec2) -> Unit) {
        rotationSetter = setter
    }

    // ========== Rotation (AimAssist, BridgeAssist) ==========
    private var rotationApplier: ((Float, Float) -> Unit)? = null

    fun setPlayerRotation(yaw: Float, pitch: Float) { rotationApplier?.invoke(yaw, pitch) }
    fun registerRotationApplier(handler: (Float, Float) -> Unit) { rotationApplier = handler }

    // ========== Desired Rotation (AimAssist — computed on background, applied on main thread) ==========
    // The aim strategy computes the TARGET rotation + a per-frame interpolation fraction on the
    // 20Hz background tick; the MAIN thread interpolates toward it EVERY RENDER FRAME
    // (drainDesiredRotationFrame). MC's tick (20Hz) is too coarse for smooth aim — per-frame
    // interpolation on the render thread is ~3x smoother at 60fps. The strategy never writes the
    // real rotationYaw/Pitch fields directly (background write would be overwritten/race).
    @Volatile var desiredRotationYaw: Float? = null
    @Volatile var desiredRotationPitch: Float? = null
    /** Per-frame yaw interpolation fraction (0..1, set by the strategy). */
    @Volatile var desiredRotationFractionY: Float = 0.2f
    /** Per-frame pitch interpolation fraction (0..1, set by the strategy). */
    @Volatile var desiredRotationFractionP: Float = 0.1f

    // Persistent aim-animation state, updated ONLY on the main render thread. Nemui-style: the
    // CORRECTION offset (target - player) is accumulated across frames (never recomputed from the
    // player's angle each frame), so the correction converges smoothly and never 发飘/打转; because
    // it's applied on top of the player's current rotation, the player's own turns aren't fought.
    private var aimOffsetYaw: Float = 0f
    private var aimOffsetPitch: Float = 0f
    private var aimAnimActive: Boolean = false

    /**
     * Clear the pending aim target. Called when the strategy decides NOT to assist this tick
     * (no target, out of range, click not held, etc.) — otherwise the main thread would keep
     * pulling toward the last remembered target ("aims at one fixed spot"). Also called on disable.
     */
    fun clearDesiredRotation() {
        desiredRotationYaw = null
        desiredRotationPitch = null
        desiredRotationFractionY = 0.2f
        desiredRotationFractionP = 0.1f
        aimOffsetYaw = 0f
        aimOffsetPitch = 0f
        aimAnimActive = false
    }

    /**
     * Main-thread per-frame aim application: move [currentYaw]/[currentPitch] a fraction of the
     * way toward the desired rotation (exponential ease at render-frame rate). Returns false when
     * no desired rotation is pending. The desired values are NOT cleared — they stay as the
     * ongoing target until the strategy updates them (module disable clears them).
     *
     * The player-yield is applied HERE, per frame: the faster the player is turning (mouse
     * pixels this frame), the more the assist yields so the player leads their own turn. Computing
     * this on the render frame (not the 20Hz tick) keeps it stable — tick intervals jitter.
     *
     * @param currentYaw player's current yaw (read on the main thread).
     * @param currentPitch player's current pitch (read on the main thread).
     * @param mouseDeltaX raw mouse delta X this frame (pixels).
     * @param mouseDeltaY raw mouse delta Y this frame (pixels).
     */
    fun drainDesiredRotationFrame(
        currentYaw: Float,
        currentPitch: Float,
        mouseDeltaX: Float = 0f,
        mouseDeltaY: Float = 0f
    ): Boolean {
        val yaw = desiredRotationYaw ?: return false
        val pitch = desiredRotationPitch ?: return false
        // Player-yield: turning hard (>= YIELD_PIXELS px/frame) → assist yields fully; idle → full pull.
        val mouseMag = kotlin.math.sqrt(mouseDeltaX * mouseDeltaX + mouseDeltaY * mouseDeltaY)
        val yield = (1f - (mouseMag / YIELD_PIXELS)).coerceIn(0f, 1f)
        if (yield <= 0f) return false

        // Nemui-style persistent offset animation: we smooth the CORRECTION (target - player),
        // not an absolute angle. The offset persists across frames and converges exponentially, so
        // the assist never 发飘/打转; and because it's applied on top of the player's CURRENT
        // rotation, the player's own turns are never fought (the offset just re-targets).
        if (!aimAnimActive) {
            aimOffsetYaw = 0f
            aimOffsetPitch = 0f
            aimAnimActive = true
        }

        // Desired total correction this frame.
        val desiredOffsetYaw = normalizeAngle(yaw - currentYaw)
        val desiredOffsetPitch = pitch - currentPitch
        // Dead zone: already aligned → stop entirely (no micro-jitter at the edge).
        if (abs(desiredOffsetYaw) < DEAD_YAW && abs(desiredOffsetPitch) < DEAD_PITCH) {
            aimOffsetYaw = 0f
            aimOffsetPitch = 0f
            return false
        }

        // Soft-landing: the pull weakens as the crosshair gets close to the target — near the aim
        // point it drops to ~30%, so the assist "lets go" instead of hard-locking the crosshair.
        // This is the difference between 硬锁 (sticky lock) and 软吸附 (soft assist).
        val gap = abs(desiredOffsetYaw) + abs(desiredOffsetPitch)
        val relax = (gap / HARD_LOCK_ANGLE).coerceIn(SOFT_MIN, 1f)

        // Exponential ease of the offset toward the desired correction (Nemui SimpleAnimation),
        // scaled by the soft-landing relax.
        val fY = desiredRotationFractionY.coerceIn(0f, 1f) * yield * relax
        val fP = desiredRotationFractionP.coerceIn(0f, 1f) * yield * relax
        if (fY <= 0f && fP <= 0f) return false
        aimOffsetYaw = normalizeAngle(aimOffsetYaw + (desiredOffsetYaw - aimOffsetYaw) * fY)
        aimOffsetPitch = aimOffsetPitch + (desiredOffsetPitch - aimOffsetPitch) * fP

        val applyYaw = currentYaw + aimOffsetYaw
        val applyPitch = currentPitch + aimOffsetPitch

        // MC mouse GCD alignment (LB AimSimulator.getGCD): quantize so each rotation move is a step
        // the mouse can actually produce at this sensitivity — reads like a human hand.
        val gcd = mouseGcd()
        val qYaw = (kotlin.math.round((applyYaw - currentYaw) / gcd) * gcd).toFloat()
        val qPitch = (kotlin.math.round((applyPitch - currentPitch) / gcd) * gcd).toFloat()
        rotationApplier?.invoke(currentYaw + qYaw, currentPitch + qPitch)
        return true
    }

    /** Dead-zone (degrees): stop assisting once this close to the target. */
    private const val DEAD_YAW = 0.2f
    private const val DEAD_PITCH = 0.1f

    /**
     * Soft-landing scale (degrees): at this combined angular gap the assist is at full strength;
     * closer than that it relaxes down to [SOFT_MIN] so it doesn't hard-lock the crosshair.
     */
    private const val HARD_LOCK_ANGLE = 12f
    /** Soft-est pull strength it can drop to near the target (0..1). */
    private const val SOFT_MIN = 0.3f

    /** Mouse pixels/frame above which the assist fully yields to the player (they are turning). */
    private const val YIELD_PIXELS = 120f

    /** MC GCD (LiquidBounce AimSimulator.getGCD): the smallest rotation step a mouse can produce. */
    private fun mouseGcd(): Float {
        val f = mouseSensitivity * 0.6f + 0.2f
        return f * f * f * 8f * 0.15f
    }

    private fun normalizeAngle(angle: Float): Float {
        var a = angle % 360f
        if (a > 180f) a -= 360f
        if (a < -180f) a += 360f
        return a
    }

    private fun abs(v: Float): Float = if (v < 0f) -v else v

    // ========== Click Delay Reset (DelayRemover — 1.8 exclusive) ==========
    private var resetClickDelayHandler: (() -> Unit)? = null

    fun resetClickDelay() { resetClickDelayHandler?.invoke() }
    fun registerResetClickDelayHandler(handler: () -> Unit) { resetClickDelayHandler = handler }

    // ========== Jump Delay Reset (NoJumpDelay — Movement) ==========
    private var resetJumpDelayHandler: (() -> Unit)? = null

    fun resetJumpDelay() { resetJumpDelayHandler?.invoke() }
    fun registerResetJumpDelayHandler(handler: () -> Unit) { resetJumpDelayHandler = handler }

    // ========== Right-Click Delay (FastPlace — World) ==========
    private var rightClickDelayHandler: ((Int) -> Unit)? = null

    fun setRightClickDelay(ticks: Int) { rightClickDelayHandler?.invoke(ticks) }
    fun registerRightClickDelayHandler(handler: (Int) -> Unit) { rightClickDelayHandler = handler }

    // ========== 5. Target & Entity ==========

    // ========== Entity Info (AntiBot — Player) ==========
    private var entityTicksProvider: ((String) -> Int)? = null
    private var entityOnGroundChecker: ((String) -> Boolean)? = null

    fun getEntityTicksExisted(name: String): Int = entityTicksProvider?.invoke(name) ?: 0
    fun isEntityOnGround(name: String): Boolean = entityOnGroundChecker?.invoke(name) ?: false
    fun registerEntityTicksProvider(handler: (String) -> Int) { entityTicksProvider = handler }
    fun registerEntityOnGroundChecker(handler: (String) -> Boolean) { entityOnGroundChecker = handler }

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

    // ========== Reach (Reach — 1.8 exclusive) ==========
    private var reachSetter: ((Float) -> Unit)? = null

    fun setReach(distance: Float) { reachSetter?.invoke(distance) }
    fun registerReachSetter(handler: (Float) -> Unit) { reachSetter = handler }

    /**
     * Extended-reach raycast callback. The adapter (Forge/Fabric) implements this using platform
     * mappings: casts a ray from the player's eyes along the look direction for [reach] blocks,
     * enumerates entity AABBs, and overwrites `objectMouseOver` with the nearest hit (Raven model).
     * Returns true if the crosshair was overwritten.
     */
    @Volatile private var reachRaycast: ((Double) -> Boolean)? = null

    fun doReachRaycast(reach: Double): Boolean = reachRaycast?.invoke(reach) ?: false
    fun registerReachRaycast(handler: (Double) -> Boolean) { reachRaycast = handler }

    // ========== 6. Render & Display ==========

    // ========== Render Offset (ParallaxStrike — Player) ==========
    @Volatile var renderOffsetX: Float = 0f
    @Volatile var renderOffsetY: Float = 0f
    @Volatile var renderOffsetZ: Float = 0f

    /** Clear all render offsets (modules call this, adapter reads on render). */
    fun clearRenderOffset() {
        renderOffsetX = 0f; renderOffsetY = 0f; renderOffsetZ = 0f
    }

    // ========== Entity Position (KnockbackDisplay dealt-KB displacement) ==========
    // Platform callback: current position of a given entity id (world.getEntityByID → posX/Y/Z).
    // Read on the background tick thread (read-only MC access is safe).
    private var entityPositionProvider: ((Int) -> Vec3?)? = null

    fun getEntityPosition(entityId: Int): Vec3? = entityPositionProvider?.invoke(entityId)
    fun registerEntityPositionProvider(provider: (Int) -> Vec3?) { entityPositionProvider = provider }

    // ========== Target Filter (TargetFilter — Player category) ==========
    // Shared target-type filter: the TargetFilter module writes these; the platform target
    // selection (ForgeStateExtractor.isViableTarget + Reach raycast) reads them so the listed
    // combat modules only aim/act on the allowed entity types. True = type allowed.
    @Volatile var targetFilterPlayers: Boolean = true
    @Volatile var targetFilterMobs: Boolean = true

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

    // ========== 7. Player Assist ==========

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
}
