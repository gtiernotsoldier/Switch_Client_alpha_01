package io.doppel.adapter.forge.v1_8_9

import io.doppel.adapter.common.api.EventBridge
import io.doppel.core.logging.CoreLogger
import io.doppel.adapter.common.api.IEventBridge
import io.doppel.adapter.common.module.movement.InvMoveRules
import io.doppel.core.model.*
import io.doppel.core.strategy.reach.ReachRaycast
import io.doppel.core.util.Vec2
import io.doppel.core.util.Vec3
import io.doppel.agent.MappingContext

/**
 * Forge 1.8.9 event bridge — pure reflection, zero MC/Forge compile dependencies.
 * Translates module-layer requests into Minecraft state changes via MappingContext.
 */
object ForgeEventBridge : IEventBridge {

    // ========== Reflection References (lazy, resolved once) ==========

    // — LWJGL Mouse & entity/item classes
    private val mouseClass by lazy { Class.forName("org.lwjgl.input.Mouse") }
    private val mouseIsButtonDown by lazy {
        mouseClass.getMethod("isButtonDown", Int::class.javaPrimitiveType)
    }
    /** Cursor position, for clicking inside an open screen (Raven's inventoryClick transform). */
    private val mouseGetX by lazy { mouseClass.getMethod("getX") }
    private val mouseGetY by lazy { mouseClass.getMethod("getY") }

    /**
     * LWJGL Keyboard — the only source of PHYSICAL key state in this class.
     * Never read `KeyBinding.pressed` back: this class writes that field, so reading it feeds our
     * own writes in and latches (MC only refreshes it on real key events). See [physicalKeyDown].
     */
    private val keyboardClass by lazy { Class.forName("org.lwjgl.input.Keyboard") }
    private val keyboardIsKeyDown by lazy {
        keyboardClass.getMethod("isKeyDown", Int::class.javaPrimitiveType)
    }
    private val entityLivingBaseClass by lazy { Class.forName("net.minecraft.entity.EntityLivingBase") }
    private val entityPlayerClass by lazy { Class.forName("net.minecraft.entity.player.EntityPlayer") }
    private val s27PacketClass by lazy { Class.forName("net.minecraft.network.play.server.S27PacketExplosion") }
    private val itemArmorClass by lazy { Class.forName("net.minecraft.item.ItemArmor") }
    private val armorMaterialClass by lazy { Class.forName("net.minecraft.item.ItemArmor\$ArmorMaterial") }
    private val clothMaterial by lazy { armorMaterialClass.enumConstants.firstOrNull { it.toString() == "CLOTH" } }

    // — packet classes
    /** Diagnostic counter for AutoTool's slot resolution (see the handler below). */
    private var autoToolDiag: Int = 0

    private val c02PacketUseEntityClass by lazy { Class.forName("net.minecraft.network.play.client.C02PacketUseEntity") }
    private val c02ActionClass by lazy { Class.forName("net.minecraft.network.play.client.C02PacketUseEntity\$Action") }
    private val attackAction by lazy { c02ActionClass.enumConstants.firstOrNull { it.toString() == "ATTACK" } }
    private val c02AttackConstructor by lazy {
        c02PacketUseEntityClass.getConstructor(
            Class.forName("net.minecraft.entity.Entity"),
            c02ActionClass
        )
    }

    private val c0bPacketClass by lazy { Class.forName("net.minecraft.network.play.client.C0BPacketEntityAction") }
    private val c0bActionClass by lazy { Class.forName("net.minecraft.network.play.client.C0BPacketEntityAction\$Action") }
    private val stopSprintingAction by lazy { c0bActionClass.enumConstants.firstOrNull { it.toString() == "STOP_SPRINTING" } }
    private val startSprintingAction by lazy { c0bActionClass.enumConstants.firstOrNull { it.toString() == "START_SPRINTING" } }
    private val startSneakingAction by lazy { c0bActionClass.enumConstants.firstOrNull { it.toString() == "START_SNEAKING" } }
    private val stopSneakingAction by lazy { c0bActionClass.enumConstants.firstOrNull { it.toString() == "STOP_SNEAKING" } }
    private val c0bConstructor by lazy {
        c0bPacketClass.getConstructor(
            Class.forName("net.minecraft.entity.Entity"),
            c0bActionClass
        )
    }

    /** Resolve a C0B action enum constant by name ("START_SPRINTING" etc.). */
    private fun c0bAction(name: String): Any? =
        c0bActionClass.enumConstants.firstOrNull { it.toString() == name }

    private val c04PacketClass by lazy { Class.forName("net.minecraft.network.play.client.C03PacketPlayer\$C04PacketPlayerPosition") }
    private val c04Constructor by lazy {
        c04PacketClass.getConstructor(
            Double::class.java, Double::class.java, Double::class.java, Boolean::class.java
        )
    }

    private val c09PacketClass by lazy { Class.forName("net.minecraft.network.play.client.C09PacketHeldItemChange") }
    private val c09Constructor by lazy {
        c09PacketClass.getConstructor(Int::class.javaPrimitiveType)
    }

    // — blocks & raycast support
    private val blocksClass by lazy { Class.forName("net.minecraft.init.Blocks") }

    /**
     * `Blocks.air`, or null when it cannot be resolved.
     *
     * `net.minecraft.init.Blocks` keeps its deobfuscated class name at runtime, but its **fields are
     * SRG**: AIR is `field_150340_c`. The old `getField("AIR")` therefore threw
     * NoSuchFieldException, the lazy initialiser handed it to the edge detector's catch, and every
     * edge test answered "not on an edge" — the second, independent reason Eagle never pressed
     * sneak (the first was the inverted comparison).
     *
     * Null is a usable answer: the caller treats "cannot tell" as "not on an edge", so a mapping
     * change can only make the module quiet, never make it sneak at the wrong moment.
     */
    private val blocksAirValue: Any? by lazy {
        val viaMapping = try { MappingContext.getField("forge:blocks_air")?.get(null) } catch (_: Exception) { null }
        viaMapping ?: try {
            blocksClass.getField("field_150340_c").get(null)
        } catch (_: Exception) {
            try { blocksClass.getField("AIR").get(null) } catch (_: Exception) { null }
        }
    }
    private val movingObjectTypeClass by lazy { Class.forName("net.minecraft.util.MovingObjectPosition\$MovingObjectType") }
    private val movingObjectTypeBlock by lazy { movingObjectTypeClass.enumConstants.firstOrNull { it.toString() == "BLOCK" } }

    // — cached mapping fields (avoid repeated getField lookups)
    private val keybindingPressedField by lazy { MappingContext.getField("forge:keybinding_pressed") }
    private val keybindingPressTimeField by lazy { MappingContext.getField("forge:keybinding_pressTime") }
    // LWJGL Mouse.buttons static ByteBuffer — writing it makes Mouse.isButtonDown(0) return
    // true, so keystrokes HUDs that read the physical mouse state see our synthetic click.
    private val mouseButtonsField by lazy {
        try { mouseClass.getDeclaredField("buttons").also { it.isAccessible = true } } catch (_: Exception) { null }
    }
    private val playerRotationYawField by lazy { MappingContext.getField("forge:player_rotationYaw") }
    private val playerRotationPitchField by lazy { MappingContext.getField("forge:player_rotationPitch") }
    private val playerHurtTimeField by lazy { MappingContext.getField("forge:entity_hurtTime") }
    // Members the render-frame overrides need that have no entry in the mapping table. Resolved by
    // SRG name (the runtime name under Forge 1.8.9) with the MCP name as the dev-env fallback —
    // same fallback order MappingContext uses. See resolveField().
    private val playerMaxHurtTimeField by lazy {
        resolveField("net.minecraft.entity.EntityLivingBase", "field_70738_aO", "maxHurtTime")
    }
    private val playerCapabilitiesField by lazy {
        resolveField("net.minecraft.entity.player.EntityPlayer", "field_71075_bZ", "capabilities")
    }
    private val capabilitiesAllowFlyingField by lazy {
        resolveField("net.minecraft.entity.player.PlayerCapabilities", "field_75101_c", "allowFlying")
    }
    /** `net.minecraft.client.gui.inventory.GuiContainer` — the container-screen base class. */
    private val guiContainerClass by lazy {
        try { Class.forName("net.minecraft.client.gui.inventory.GuiContainer") } catch (_: Exception) { null }
    }
    private val mcLeftClickCounterField by lazy { MappingContext.getField("forge:mc_leftClickCounter") }
    private val mcRightClickDelayField by lazy { MappingContext.getField("forge:mc_rightClickDelayTimer") }
    private val gsGammaField by lazy { MappingContext.getField("forge:gs_gammaSetting") }
    private val inventoryCurrentItemField by lazy { MappingContext.getField("forge:inventory_currentItem") }
    private val playerJumpTicksField by lazy { MappingContext.getField("forge:player_jumpTicks") }
    private val mcObjectMouseOverField by lazy { MappingContext.getField("forge:mc_objectMouseOver") }
    /** `mc.pointedEntity` — Raven sets this alongside objectMouseOver when it spoofs a pick. */
    private val mcPointedEntityField by lazy { MappingContext.getField("forge:mc_pointedEntity") }

    // ========== State ==========

    @Volatile
    var pendingMotion: Vec3? = null

    /** Previous tick's crosshair-target hurt state (for attack rising-edge detection). */
    private var prevTargetHurt = false

    // — click-cadence state (Raven-style)
    // Each full clicker cadence: press on downTime, release on upTime, regenerated per cycle.
    private var attackCadenceActive = false
    private var attackDownTime = 0L
    private var attackUpTime = 0L
    private var attackPressed = false
    // Butterfly double-click support: after the first press/release we schedule a second tight
    // press+release inside the same cycle (Slinky Butterfly pattern).
    private var attackButterflyPending = false
    private var attackButterflyDownTime = 0L
    private var attackButterflyUpTime = 0L
    private var useCadenceActive = false
    private var useDownTime = 0L
    private var useUpTime = 0L
    private var usePressed = false
    private val clickRandom = java.util.Random()
    // Simulate-exhaust state: occasionally slow the click rate like a tiring hand (Slinky).
    private var exhaustUntil = 0L

    // ========== Platform Helpers ==========

    private fun getMc(): Any? = try {
        MappingContext.invokeMethod(null, "forge:mc_getMinecraft")
    } catch (_: Exception) { null }

    private fun getPlayer(): Any? = try {
        getMc()?.let { MappingContext.getFieldValue(it, "forge:mc_thePlayer") }
    } catch (_: Exception) { null }

    private fun getWorld(): Any? = try {
        getMc()?.let { MappingContext.getFieldValue(it, "forge:mc_theWorld") }
    } catch (_: Exception) { null }

    /** The render view entity (what raycasts = the player normally). */
    private fun getRenderViewEntity(mc: Any?, fallback: Any?): Any? = try {
        MappingContext.invokeMethod(mc, "forge:mc_getRenderViewEntity") ?: fallback
    } catch (_: Exception) { fallback }

    /** Convert a MC Vec3 (net.minecraft.util.Vec3) to our core Vec3, or null. */
    private fun mcVec3ToVec3(mcVec3: Any?): Vec3? {
        return try {
            if (mcVec3 == null) return null
            val c = mcVec3.javaClass
            Vec3(
                c.getField("field_72450_a").getDouble(mcVec3),
                c.getField("field_72448_b").getDouble(mcVec3),
                c.getField("field_72449_c").getDouble(mcVec3)
            )
        } catch (_: Exception) { null }
    }

    /** Convert our core Vec3 to a MC Vec3 instance (net.minecraft.util.Vec3). */
    private fun coreVec3ToMcVec3(v: Vec3): Any? {
        return try {
            val c = Class.forName("net.minecraft.util.Vec3")
            val ctor = c.getConstructor(Double::class.java, Double::class.java, Double::class.java)
            ctor.newInstance(v.x, v.y, v.z)
        } catch (_: Exception) { null }
    }

    /** Min corner (minX/minY/minZ) of an MC AxisAlignedBB as our core Vec3, or null. */
    private fun bbMin(box: Any?): Vec3? {
        return try {
            if (box == null) return null
            val c = box.javaClass
            Vec3(
                c.getField("field_72340_a").getDouble(box),
                c.getField("field_72338_b").getDouble(box),
                c.getField("field_72339_c").getDouble(box)
            )
        } catch (_: Exception) { null }
    }

    /** Max corner (maxX/maxY/maxZ) of an MC AxisAlignedBB as our core Vec3, or null. */
    private fun bbMax(box: Any?): Vec3? {
        return try {
            if (box == null) return null
            val c = box.javaClass
            Vec3(
                c.getField("field_72336_d").getDouble(box),
                c.getField("field_72337_e").getDouble(box),
                c.getField("field_72334_f").getDouble(box)
            )
        } catch (_: Exception) { null }
    }

    private fun isMouseButtonDown(button: Int): Boolean = try {
        mouseIsButtonDown.invoke(null, button) as Boolean
    } catch (_: Exception) { false }

    private fun setKeyBindPressed(gsKey: String, pressed: Boolean) {
        try {
            val mc = getMc() ?: return
            val gs = MappingContext.getFieldValue(mc, "forge:mc_gameSettings") ?: return
            val keyBind = MappingContext.getFieldValue(gs, gsKey) ?: return
            keybindingPressedField?.set(keyBind, pressed)
        } catch (_: Exception) {}
    }

    /**
     * Physical state of a game-settings key binding, read from LWJGL's Keyboard.
     *
     * Deliberately NOT `KeyBinding.pressed`: this class writes that field every frame, so reading
     * it back feeds our own synthetic writes in — and the value latches, because MC only refreshes
     * it on a real key event. That is what left W released forever after a W-tap, and S / A / D
     * pressed forever after an S-tap or an A/D-tap stopped driving them: the module's release
     * could never win against the value we had written ourselves.
     */
    private fun physicalKeyDown(gsKey: String): Boolean {
        return try {
            val mc = getMc() ?: return false
            val gs = MappingContext.getFieldValue(mc, "forge:mc_gameSettings") ?: return false
            val keyBind = MappingContext.getFieldValue(gs, gsKey) ?: return false
            val keyCode = (MappingContext.getFieldValue(keyBind, "forge:keybinding_keyCode") as? Int) ?: 0
            if (keyCode == 0) false
            else (keyboardIsKeyDown.invoke(null, keyCode) as? Boolean) ?: false
        } catch (_: Exception) { false }
    }

    /**
     * Refresh the Keystrokes HUD key-state mirrors from the MAIN thread (render loop).
     * The background 20Hz tick previously sampled these, but a JumpReset pulse only presses the jump
     * key for ~80ms on the main thread, so the 20Hz sample could miss the window and the jump key
     * never lit up. Reading on the main thread every frame makes the HUD reflect the real key state.
     * The jump mirror also ORs the live JumpReset pulse state, because MC may consume/clear the
     * KeyBinding pressed field before the render frame — the pulse flag is the authoritative signal.
     */
    fun refreshKeyDisplayState() {
        try {
            EventBridge.isKeyJumpDown = physicalKeyDown("forge:gs_keyBindJump") || EventBridge.isJumpPulseActive()
            EventBridge.isKeyForwardDown = physicalKeyDown("forge:gs_keyBindForward")
            EventBridge.isKeyBackDown = physicalKeyDown("forge:gs_keyBindBack")
            EventBridge.isKeyLeftDown = physicalKeyDown("forge:gs_keyBindLeft")
            EventBridge.isKeyRightDown = physicalKeyDown("forge:gs_keyBindRight")
        } catch (_: Exception) {}
    }

    private fun sendPacket(packet: Any?) {
        try {
            val player = getPlayer() ?: return
            val sendQueue = MappingContext.getFieldValue(player, "forge:player_sendQueue") ?: return
            MappingContext.invokeMethod(sendQueue, "forge:netHandler_addToSendQueue", packet)
        } catch (_: Exception) {}
    }

    /**
     * Send a C0BPacketEntityAction for an arbitrary action name (e.g. "START_SPRINTING",
     * "STOP_SPRINTING", "START_SNEAKING", "STOP_SNEAKING"). Keeps the server-side sprint
     * mirror ([EventBridge.serverSprintState]) in sync for sprint actions.
     */
    private fun sendEntityAction(actionName: String) {
        try {
            val player = getPlayer() ?: return
            val action = c0bAction(actionName) ?: return
            sendPacket(c0bConstructor.newInstance(player, action))
            when (actionName) {
                "STOP_SPRINTING" -> EventBridge.reportServerSprintState(false)
                "START_SPRINTING" -> EventBridge.reportServerSprintState(true)
                else -> {}
            }
        } catch (_: Exception) {}
    }

    /**
     * 1.8.8/1.8.9 display name of an entity (getDisplayName -> getFormattedText).
     */
    private fun entityDisplayName(entity: Any?): String? {
        if (entity == null) return null
        return try {
            val comp = MappingContext.invokeMethod(entity, "forge:entity_name")
            MappingContext.invokeMethod(comp, "forge:ichatComponent_formattedText") as? String
        } catch (_: Exception) { null }
    }

    // ========== EventBridge Handler Registration ==========

    override fun registerListeners() {
        // ── Rotation & Motion ──
        EventBridge.registerRotationSetter { rotation -> setPlayerRotation(rotation) }
        EventBridge.registerMotionApplier { motion -> applyMotion(motion) }

        // ── Sprint / C0B ──
        EventBridge.registerSprintSetter { sprinting ->
            val player = getPlayer() ?: return@registerSprintSetter
            MappingContext.invokeMethod(player, "forge:player_setSprinting", sprinting)
            // Deliberately does NOT touch EventBridge.serverSprintState. The local flag and the
            // server's flag are different facts — that divergence is MC-69459 — and the mirror is
            // now written only where a C0B actually goes on the wire. Writing it here made the
            // "mirror" a copy of the local flag, which reduced SuperKnockback's
            // `player.isSprinting && serverSprintState` guard to a tautology. Fabric never wrote it
            // here, so the two platforms also disagreed about what the field meant.
        }

        // Generic C0B entity-action sender (SuperKnockback Old/SneakPacket, ported from LB).
        EventBridge.registerEntityActionHandler { actionName ->
            sendEntityAction(actionName)
        }

        EventBridge.registerSprintResetHandler { mode ->
            val player = getPlayer() ?: return@registerSprintResetHandler
            when (mode) {
                "Nostop" -> {
                    try {
                        sendPacket(c0bConstructor.newInstance(player, stopSprintingAction))
                        EventBridge.reportServerSprintState(false)
                        sendPacket(c0bConstructor.newInstance(player, startSprintingAction))
                        EventBridge.reportServerSprintState(true)
                    } catch (_: Exception) {}
                }
                "Silent" -> {
                    try {
                        val posX = MappingContext.getFieldValue(player, "forge:entity_posX") as? Double ?: 0.0
                        val posY = MappingContext.getFieldValue(player, "forge:entity_posY") as? Double ?: 0.0
                        val posZ = MappingContext.getFieldValue(player, "forge:entity_posZ") as? Double ?: 0.0
                        val onGround = MappingContext.getFieldValue(player, "forge:entity_onGround") as? Boolean ?: false
                        sendPacket(c04Constructor.newInstance(posX, posY, posZ, onGround))
                    } catch (_: Exception) {}
                }
            }
        }

        // ── Attack & Click ──
        EventBridge.registerAttackTrigger {
            setKeyBindPressed("forge:gs_keyBindAttack", true)
        }

        EventBridge.registerCancelAttackHandler {
            setKeyBindPressed("forge:gs_keyBindAttack", false)
        }

        EventBridge.registerResetClickDelayHandler {
            try {
                val mc = getMc() ?: return@registerResetClickDelayHandler
                mcLeftClickCounterField?.setInt(mc, 0)
            } catch (_: Exception) {}
        }

        EventBridge.registerReleaseUsingItemHandler {
            setKeyBindPressed("forge:gs_keyBindUseItem", false)
            try {
                val player = getPlayer() ?: return@registerReleaseUsingItemHandler
                MappingContext.invokeMethod(player, "forge:player_stopUsingItem")
            } catch (_: Exception) {}
        }

        EventBridge.registerPressUseItemHandler {
            setKeyBindPressed("forge:gs_keyBindUseItem", true)
        }

        // ── Jump ──
        EventBridge.registerJumpHandler {
            // Press the jump key (KeyBinding) on the main thread so MC's own tick performs the jump
            // and the Keystrokes HUD reflects it. No direct player.jump() — that would mutate the
            // entity from a background thread and skip the key display.
            setKeyBindPressed("forge:gs_keyBindJump", true)
        }
        EventBridge.registerReleaseJumpHandler {
            setKeyBindPressed("forge:gs_keyBindJump", false)
        }

        EventBridge.registerResetJumpDelayHandler {
            try {
                val player = getPlayer() ?: return@registerResetJumpDelayHandler
                playerJumpTicksField?.setInt(player, 0)
            } catch (_: Exception) {}
        }

        // ── Movement keys (WTap/STap) ──
        EventBridge.registerPressForwardHandler { setKeyBindPressed("forge:gs_keyBindForward", true) }
        EventBridge.registerReleaseForwardHandler { setKeyBindPressed("forge:gs_keyBindForward", false) }
        EventBridge.registerPressBackHandler { setKeyBindPressed("forge:gs_keyBindBack", true) }
        EventBridge.registerReleaseBackHandler { setKeyBindPressed("forge:gs_keyBindBack", false) }

        // ── Aim ──
        EventBridge.registerRotationApplier { yaw, pitch ->
            try {
                val player = getPlayer() ?: return@registerRotationApplier
                playerRotationYawField?.setFloat(player, yaw)
                playerRotationPitchField?.setFloat(player, pitch)
            } catch (_: Exception) {}
        }

        // ── Render ──
        // NoHurtCam / NoFOV are NOT driven from here any more. Writing `hurtTime` (what the old
        // resetHurtCam handler did) hides the "just got hit" signal HitSelect and Velocity read and
        // kills the red damage tint; re-writing the FOV modifier at 20 Hz raced the renderer, which
        // rebuilds it inside Minecraft.runTick(). Both modules now publish intent on EventBridge and
        // the values are applied per RENDER FRAME in applyFrameOverrides(), called from
        // ForgeBootstrap.render(). EventBridge's reset* handler API stays for the Fabric adapter,
        // which still registers it; nothing on Forge calls it.

        EventBridge.registerGammaSetter { gamma ->
            try {
                val mc = getMc() ?: return@registerGammaSetter
                val gs = MappingContext.getFieldValue(mc, "forge:mc_gameSettings") ?: return@registerGammaSetter
                gsGammaField?.setFloat(gs, gamma)
            } catch (_: Exception) {}
        }

        // ── World ──
        EventBridge.registerRightClickDelayHandler { ticks ->
            try {
                val mc = getMc() ?: return@registerRightClickDelayHandler
                mcRightClickDelayField?.setInt(mc, ticks)
            } catch (_: Exception) {}
        }

        // ── Player helpers ──
        EventBridge.registerSwitchSlotHandler { slot ->
            // Every step is named, because the previous version wrapped this in an empty catch: it threw
            // every time, the exception was swallowed, and the module looked like it was doing nothing —
            // while the log showed the search succeeding and the decision being made.
            try {
                val player = getPlayer()
                if (player == null) {
                    CoreLogger.error("[AutoTool] switch: no player")
                    return@registerSwitchSlotHandler
                }
                val field = inventoryCurrentItemField ?: try {
                    MappingContext.getField("forge:inventory_currentItem")
                } catch (_: Throwable) {
                    null
                }
                // The field belongs to InventoryPlayer, NOT to the player. Writing it on the player threw
                // "Can not set int field InventoryPlayer.field_70461_c to EntityPlayerSP" on every single
                // attempt — the search was right and the write was aimed at the wrong object.
                val inventory = try {
                    MappingContext.getFieldValue(player, "forge:player_inventory")
                } catch (_: Throwable) {
                    null
                }
                if (field == null) {
                    CoreLogger.error("[AutoTool] switch: InventoryPlayer.currentItem unresolved")
                } else if (inventory == null) {
                    CoreLogger.error("[AutoTool] switch: player.inventory unresolved")
                } else {
                    field.setInt(inventory, slot)
                }
                val packet = try {
                    c09Constructor.newInstance(slot)
                } catch (t: Throwable) {
                    CoreLogger.error("[AutoTool] switch: C09 construction failed: $t")
                    null
                }
                if (packet != null) sendPacket(packet)
                CoreLogger.info("[AutoTool] switched to slot $slot (field=${field != null}, packet=${packet != null})")
            } catch (t: Throwable) {
                CoreLogger.error("[AutoTool] switch to $slot failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        EventBridge.registerGetBestSlotHandler {
            var bestSlot = -1
            var bestSpeed = 1.0f
            // Diagnostic (once a second, only while this is asked for): AutoTool reported as not working
            // and the reason has to be nameable. This prints what the chain actually resolved — the ray
            // trace type, the block, and every hotbar slot's dig speed — so a failure says which step it
            // was instead of "nothing happened".
            // The first few calls are the interesting ones (a mining edge is rare), so they are never
            // throttled away; after that it is every twentieth call.
            val diag: (String) -> Unit = { msg ->
                val n = ++autoToolDiag
                if (n <= 5 || n % 20 == 0) CoreLogger.info("[AutoTool] $msg")
            }
            val player = getPlayer() ?: return@registerGetBestSlotHandler -1
            val mc = getMc()
            val objMouseOver = try { MappingContext.getFieldValue(mc, "forge:mc_objectMouseOver") } catch (_: Exception) { null }
            if (objMouseOver == null) {
                diag("no objectMouseOver (field_71476_x) — nothing under the crosshair")
                return@registerGetBestSlotHandler -1
            }

            val typeOfHit = try { MappingContext.getFieldValue(objMouseOver, "forge:movingObjectPosition_typeOfHit") } catch (_: Exception) { null }
            if (typeOfHit !== movingObjectTypeBlock) {
                diag("ray trace is not a block (type=${typeOfHit?.javaClass?.simpleName} block=${movingObjectTypeBlock?.javaClass?.simpleName} same=${typeOfHit === movingObjectTypeBlock})")
                return@registerGetBestSlotHandler -1
            }

            val blockPos = try { MappingContext.getFieldValue(objMouseOver, "forge:movingObjectPosition_blockPos") } catch (_: Exception) { null }
            val world = getWorld() ?: return@registerGetBestSlotHandler -1
            val blockState = try { MappingContext.invokeMethod(world, "forge:world_getBlockState", blockPos) } catch (_: Exception) { null }
            val block = try { MappingContext.invokeMethod(blockState, "forge:iblockstate_block") } catch (_: Exception) { null }

            val inventory = try { MappingContext.getFieldValue(player, "forge:player_inventory") } catch (_: Exception) { null }
            // Baseline: what is held right now. The reference clients compare against the held tool, not
            // against the bare hand, so holding the right pickaxe already means "no better tool" and the
            // module stops asking — without this it re-selected a slot every tick.
            val heldSlot = try {
                (MappingContext.getFieldValue(inventory, "forge:inventory_currentItem") as? Int) ?: 0
            } catch (_: Exception) {
                0
            }
            val heldStack = try {
                MappingContext.invokeMethod(inventory, "forge:inventory_getStackInSlot", heldSlot)
            } catch (_: Exception) {
                null
            }
            val heldItem = try {
                if (heldStack == null) null else MappingContext.getFieldValue(heldStack, "forge:itemStack_item")
            } catch (_: Exception) {
                null
            }
            if (heldItem != null && block != null) {
                val heldSpeed = try {
                    MappingContext.invokeMethod(heldItem, "forge:item_getDigSpeed", heldStack, block) as? Float
                } catch (_: Exception) {
                    null
                }
                if (heldSpeed != null) bestSpeed = heldSpeed
            }
            for (i in 0..8) {
                val stack = try { MappingContext.invokeMethod(inventory, "forge:inventory_getStackInSlot", i) } catch (_: Exception) { null }
                if (stack == null) continue
                val item = try { MappingContext.getFieldValue(stack, "forge:itemStack_item") } catch (_: Exception) { null }
                if (item == null || block == null) continue
                val speed = try { MappingContext.invokeMethod(item, "forge:item_getDigSpeed", stack, block) as? Float } catch (_: Exception) { null }
                if (i == 0) diag("block=${block?.javaClass?.simpleName} item0=${item.javaClass.simpleName} speed0=$speed")
                // Strictly better than what is held, and an actual tool (> 1.0 = better than a hand).
                if (speed != null && speed > bestSpeed && speed > 1.0f) {
                    bestSpeed = speed
                    bestSlot = i
                }
            }
            if (bestSlot >= 0) {
                diag("best slot=$bestSlot speed=$bestSpeed (held=$heldSlot)")
                bestSlot
            } else {
                diag("no tool beats hand (bestSpeed=$bestSpeed) — block=${block?.javaClass?.simpleName}")
                -1
            }
        }

        EventBridge.registerPressSneakHandler { setKeyBindPressed("forge:gs_keyBindSneak", true) }
        EventBridge.registerReleaseSneakHandler { setKeyBindPressed("forge:gs_keyBindSneak", false) }

        EventBridge.registerEdgeDetector {
            val player = getPlayer() ?: return@registerEdgeDetector false
            val onGround = MappingContext.getFieldValue(player, "forge:entity_onGround") as? Boolean ?: false
            if (!onGround) return@registerEdgeDetector false
            val world = getWorld() ?: return@registerEdgeDetector false
            val posX = MappingContext.getFieldValue(player, "forge:entity_posX") as? Double ?: 0.0
            val posY = MappingContext.getFieldValue(player, "forge:entity_posY") as? Double ?: 0.0
            val posZ = MappingContext.getFieldValue(player, "forge:entity_posZ") as? Double ?: 0.0
            try {
                // "On an edge" means the block carrying the player is AIR — Raven's
                // `isAirBlock(pos)` rule. This was negated, so Eagle pressed sneak on solid ground
                // and released at the drop, and BridgeAssist assisted everywhere except the edge.
                //
                // The Y offset is the reference's too: on a full block the support is the block at
                // posY-1, but with the feet at a fractional height (slab, stair, snow layer) the
                // block at posY is the support.
                val yOffset = if (posY % 1.0 == 0.0) 1.0 else 0.0
                val blockPosClass = Class.forName("net.minecraft.util.BlockPos")
                val blockPos = blockPosClass.getConstructor(Double::class.java, Double::class.java, Double::class.java)
                    .newInstance(posX, posY - yOffset, posZ)

                // Ask the world directly, exactly as the reference does...
                val asked = try {
                    MappingContext.invokeMethod(world, "forge:world_isAirBlock", blockPos) as? Boolean
                } catch (_: Exception) { null }
                if (asked != null) return@registerEdgeDetector asked

                // ...and fall back to comparing the block state's block against Blocks.air.
                val bs = MappingContext.invokeMethod(world, "forge:world_getBlockState", blockPos)
                val b = MappingContext.invokeMethod(bs, "forge:iblockstate_block")
                val air = blocksAirValue
                air != null && b === air
            } catch (_: Exception) { false }
        }

        // BridgeAssist's bridging readout: how many placeable blocks are left. ItemStack.stackSize
        // is SRG (`field_77994_a`), and the held stack is slot `inventory_currentItem`; "total" walks
        // slots 0..35, which for InventoryPlayer is the hotbar plus the main inventory.
        EventBridge.registerBlockCounter { mode ->
            try {
                val player = getPlayer() ?: return@registerBlockCounter 0
                val inventory = MappingContext.getFieldValue(player, "forge:player_inventory")
                    ?: return@registerBlockCounter 0
                val itemBlockClass = try { Class.forName("net.minecraft.item.ItemBlock") } catch (_: Exception) { null }
                    ?: return@registerBlockCounter 0
                val slots: List<Int> = if (mode == 1) {
                    listOf((MappingContext.getFieldValue(inventory, "forge:inventory_currentItem") as? Int) ?: 0)
                } else {
                    (0..35).toList()
                }
                var total = 0
                for (slot in slots) {
                    val stack = MappingContext.invokeMethod(inventory, "forge:inventory_getStackInSlot", slot) ?: continue
                    val item = MappingContext.getFieldValue(stack, "forge:itemStack_item") ?: continue
                    if (!itemBlockClass.isInstance(item)) continue
                    total += (MappingContext.getFieldValue(stack, "forge:itemStack_stackSize") as? Int) ?: 0
                }
                total
            } catch (_: Exception) {
                0
            }
        }

        EventBridge.registerReachSetter { distance ->
            val player = getPlayer() ?: return@registerReachSetter
            val world = getWorld() ?: return@registerReachSetter
            val targetId = ForgeStateExtractor.getCurrentTargetId() ?: return@registerReachSetter
            val entity = MappingContext.invokeMethod(world, "forge:world_getEntityByID", targetId) ?: return@registerReachSetter
            if (!entityLivingBaseClass.isInstance(entity)) return@registerReachSetter
            val isAlive = try { MappingContext.invokeMethod(entity, "forge:entityLivingBase_isEntityAlive") as? Boolean ?: false } catch (_: Exception) { false }
            if (!isAlive) return@registerReachSetter
            val dist = try {
                MappingContext.invokeMethod(player, "forge:entity_getDistanceToEntity", entity) as? Double ?: Double.MAX_VALUE
            } catch (_: Exception) { Double.MAX_VALUE }
            if (dist > distance) return@registerReachSetter
            try {
                val mopClass = Class.forName("net.minecraft.util.MovingObjectPosition")
                val mopCtor = mopClass.getConstructor(Class.forName("net.minecraft.entity.Entity"))
                mcObjectMouseOverField?.set(getMc(), mopCtor.newInstance(entity))
            } catch (_: Exception) {}
        }

        // Extended-reach raycast (Raven model): cast from the player's eyes along the look vector for
        // `reach` blocks, enumerate entity AABBs the segment intersects, pick the nearest, and
        // overwrite objectMouseOver so the attack range genuinely extends.
        EventBridge.registerReachRaycast { reachBlocks ->
            try {
                val mc = getMc() ?: return@registerReachRaycast false
                val player = getPlayer() ?: return@registerReachRaycast false
                val world = getWorld() ?: return@registerReachRaycast false
                val renderView = getRenderViewEntity(mc, player) ?: return@registerReachRaycast false

                // Eyes + look vector (1.8.9 func_174824_e / func_70676_i).
                val eyesObj = MappingContext.invokeMethod(renderView, "forge:entity_getPositionEyes", 1.0f) ?: return@registerReachRaycast false
                val lookObj = MappingContext.invokeMethod(renderView, "forge:entity_getLook", 1.0f) ?: return@registerReachRaycast false
                val eyes = mcVec3ToVec3(eyesObj) ?: return@registerReachRaycast false
                val look = mcVec3ToVec3(lookObj)?.normalize() ?: return@registerReachRaycast false

                // Extend the search AABB along the look vector (addCoord then expand, like Raven).
                val baseBox = MappingContext.invokeMethod(renderView, "forge:entity_getEntityBoundingBox") ?: return@registerReachRaycast false
                val aabbClass = Class.forName("net.minecraft.util.AxisAlignedBB")
                val addCoord = aabbClass.getMethod("func_72317_d", Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
                val expandBB = aabbClass.getMethod("func_72321_a", Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
                val extended = addCoord.invoke(baseBox, look.x * reachBlocks, look.y * reachBlocks, look.z * reachBlocks)
                val searchBox = expandBB.invoke(extended, 1.0, 1.0, 1.0) ?: return@registerReachRaycast false

                @Suppress("UNCHECKED_CAST")
                val entities = (MappingContext.invokeMethod(
                    world, "forge:world_getEntitiesWithinAABBExcludingEntity", renderView, searchBox
                ) as? List<Any>) ?: return@registerReachRaycast false

                // Nearest-hit search via core slab test.
                var bestEntity: Any? = null
                var bestHitVec: Vec3? = null
                var bestDist = reachBlocks
                for (entity in entities) {
                    // TargetFilter (Player category): skip entity types the user filtered out.
                    if (!isTargetTypeAllowed(entity)) continue
                    // Fail-open on purpose: if the mapping cannot be resolved we
                    // treat the entity as collidable and keep it. Dropping a
                    // target because a reflection call threw is how a broken
                    // mapping silently eats the whole feature.
                    val collidable = try { MappingContext.invokeMethod(entity, "forge:entity_canBeCollidedWith") as? Boolean ?: true } catch (_: Exception) { true }
                    if (!collidable) continue
                    val box = MappingContext.invokeMethod(entity, "forge:entity_getEntityBoundingBox") ?: continue
                    val bMin = bbMin(box) ?: continue
                    val bMax = bbMax(box) ?: continue
                    val t = ReachRaycast.intersectBox(eyes, look, bMin, bMax, reachBlocks) ?: continue
                    if (t < bestDist) {
                        bestDist = t
                        bestEntity = entity
                        bestHitVec = Vec3(eyes.x + look.x * t, eyes.y + look.y * t, eyes.z + look.z * t)
                    }
                }

                if (bestEntity != null && bestHitVec != null) {
                    val mopClass = Class.forName("net.minecraft.util.MovingObjectPosition")
                    val mopCtor = mopClass.getConstructor(Class.forName("net.minecraft.entity.Entity"), Class.forName("net.minecraft.util.Vec3"))
                    val mop = mopCtor.newInstance(bestEntity, coreVec3ToMcVec3(bestHitVec))
                    mcObjectMouseOverField?.set(mc, mop)
                    // Raven sets pointedEntity too: other code (and the client's own
                    // interaction paths) read it independently of objectMouseOver.
                    mcPointedEntityField?.set(mc, bestEntity)
                    return@registerReachRaycast true
                }
            } catch (_: Exception) {}
            false
        }

        EventBridge.registerScoreboardTeamChecker { name ->
            try {
                val world = getWorld() ?: return@registerScoreboardTeamChecker null
                val scoreboard = MappingContext.invokeMethod(world, "forge:world_scoreboard") ?: return@registerScoreboardTeamChecker null
                val teams = MappingContext.getFieldValue(scoreboard, "forge:scoreboard_teams") as? Collection<*> ?: return@registerScoreboardTeamChecker null
                for (team in teams) {
                    val members = MappingContext.getFieldValue(team, "forge:scorePlayerTeam_membershipCollection") as? Collection<*>
                    if (members != null && members.contains(name)) {
                        return@registerScoreboardTeamChecker MappingContext.getFieldValue(team, "forge:scorePlayerTeam_registeredName") as? String
                    }
                }
                null
            } catch (_: Exception) { null }
        }

        EventBridge.registerDisplayNameProvider { name ->
            try {
                val world = getWorld() ?: return@registerDisplayNameProvider name
                val loadedList = MappingContext.getFieldValue(world, "forge:world_loadedEntityList") as? List<*> ?: return@registerDisplayNameProvider name
                for (entity in loadedList) {
                    if (!entityLivingBaseClass.isInstance(entity)) continue
                    val entityName = entityDisplayName(entity) ?: continue
                    if (entityName == name) {
                        val displayName = MappingContext.invokeMethod(entity, "forge:entityLivingBase_displayName")
                        return@registerDisplayNameProvider MappingContext.invokeMethod(displayName, "forge:ichatComponent_formattedText") as? String ?: name
                    }
                }
                name
            } catch (_: Exception) { name }
        }

        EventBridge.registerArmorColorChecker { name ->
            try {
                val world = getWorld() ?: return@registerArmorColorChecker -1
                val loadedList = MappingContext.getFieldValue(world, "forge:world_loadedEntityList") as? List<*> ?: return@registerArmorColorChecker -1
                for (entity in loadedList) {
                    if (!entityLivingBaseClass.isInstance(entity)) continue
                    val entityName = entityDisplayName(entity) ?: continue
                    if (entityName != name) continue
                    for (i in 1..4) {
                        val stack = try { MappingContext.invokeMethod(entity, "forge:entityLivingBase_getEquipmentInSlot", i) } catch (_: Exception) { null }
                            ?: continue
                        if (!itemArmorClass.isInstance(MappingContext.getFieldValue(stack, "forge:itemStack_item"))) continue
                        // hasTagCompound() is a METHOD on ItemStack (func_77942_o) — reading it as
                        // a field always produced null, so armour colour lookup bailed out here.
                        val hasTag = try { MappingContext.invokeMethod(stack, "forge:itemStack_hasTagCompound") as? Boolean } catch (_: Exception) { false }
                        if (hasTag != true) continue
                        val tagCompound = try { MappingContext.getFieldValue(stack, "forge:itemStack_tagCompound") } catch (_: Exception) { null } ?: continue
                        val hasKey = try { MappingContext.invokeMethod(tagCompound, "forge:nbtBase_hasKey", "display", 10) as? Boolean } catch (_: Exception) { false }
                        if (hasKey != true) continue
                        val display = try { MappingContext.invokeMethod(tagCompound, "forge:nbtBase_getCompoundTag", "display") } catch (_: Exception) { null } ?: continue
                        return@registerArmorColorChecker try { MappingContext.invokeMethod(display, "forge:nbtBase_getInteger", "color") as? Int } catch (_: Exception) { -1 } ?: -1
                    }
                }
                -1
            } catch (_: Exception) { -1 }
        }

        EventBridge.registerEntityTicksProvider { name ->
            try {
                val world = getWorld() ?: return@registerEntityTicksProvider 0
                val loadedList = MappingContext.getFieldValue(world, "forge:world_loadedEntityList") as? List<*> ?: return@registerEntityTicksProvider 0
                for (entity in loadedList) {
                    if (!entityLivingBaseClass.isInstance(entity)) continue
                    val entityName = entityDisplayName(entity) ?: continue
                    if (entityName == name) {
                        return@registerEntityTicksProvider MappingContext.getFieldValue(entity, "forge:entity_ticksExisted") as? Int ?: 0
                    }
                }
                0
            } catch (_: Exception) { 0 }
        }

        EventBridge.registerEntityOnGroundChecker { name ->
            try {
                val world = getWorld() ?: return@registerEntityOnGroundChecker false
                val loadedList = MappingContext.getFieldValue(world, "forge:world_loadedEntityList") as? List<*> ?: return@registerEntityOnGroundChecker false
                for (entity in loadedList) {
                    if (!entityLivingBaseClass.isInstance(entity)) continue
                    val entityName = entityDisplayName(entity) ?: continue
                    if (entityName == name) {
                        return@registerEntityOnGroundChecker MappingContext.getFieldValue(entity, "forge:entity_onGround") as? Boolean ?: false
                    }
                }
                false
            } catch (_: Exception) { false }
        }

        // ── Entity position (KnockbackDisplay dealt-KB displacement) ──
        EventBridge.registerEntityPositionProvider { entityId ->
            try {
                val world = getWorld() ?: return@registerEntityPositionProvider null
                val entity = MappingContext.invokeMethod(world, "forge:world_getEntityByID", entityId) ?: return@registerEntityPositionProvider null
                Vec3(
                    MappingContext.getFieldValue(entity, "forge:entity_posX") as? Double ?: 0.0,
                    MappingContext.getFieldValue(entity, "forge:entity_posY") as? Double ?: 0.0,
                    MappingContext.getFieldValue(entity, "forge:entity_posZ") as? Double ?: 0.0
                )
            } catch (_: Exception) { null }
        }

        // ── Line-of-sight (AimAssist ThroughWalls / Single-lock re-check) ──
        EventBridge.registerLineOfSightProvider { entityId ->
            try {
                val mc = getMc() ?: return@registerLineOfSightProvider true
                val world = getWorld() ?: return@registerLineOfSightProvider true
                val player = getPlayer() ?: return@registerLineOfSightProvider true
                val entity = MappingContext.invokeMethod(world, "forge:world_getEntityByID", entityId) ?: return@registerLineOfSightProvider true
                val px = MappingContext.getFieldValue(player, "forge:entity_posX") as? Double ?: return@registerLineOfSightProvider true
                val py = MappingContext.getFieldValue(player, "forge:entity_posY") as? Double ?: return@registerLineOfSightProvider true
                val pz = MappingContext.getFieldValue(player, "forge:entity_posZ") as? Double ?: return@registerLineOfSightProvider true
                val eyeHeight = MappingContext.invokeMethod(player, "forge:player_eyeHeight") as? Double ?: 1.62
                val eyeVec = coreVec3ToMcVec3(Vec3(px, py + eyeHeight, pz)) ?: return@registerLineOfSightProvider true
                val tx = MappingContext.getFieldValue(entity, "forge:entity_posX") as? Double ?: return@registerLineOfSightProvider true
                val ty = MappingContext.getFieldValue(entity, "forge:entity_posY") as? Double ?: return@registerLineOfSightProvider true
                val tz = MappingContext.getFieldValue(entity, "forge:entity_posZ") as? Double ?: return@registerLineOfSightProvider true
                // Aim at the body center (bounding box center), not the feet, so the check
                // matches where AimAssist actually points.
                val bb = MappingContext.getFieldValue(entity, "forge:entity_boundingBox")
                val bodyY = if (bb != null) {
                    val minY = MappingContext.getFieldValue(bb, "forge:bb_minY") as? Double
                    val maxY = MappingContext.getFieldValue(bb, "forge:bb_maxY") as? Double
                    if (minY != null && maxY != null) (minY + maxY) / 2.0 else ty
                } else ty
                val targetVec = coreVec3ToMcVec3(Vec3(tx, bodyY, tz)) ?: return@registerLineOfSightProvider true
                val hit = MappingContext.invokeMethod(world, "forge:world_rayTraceBlocks", eyeVec, targetVec, false, true, false)
                hit == null
            } catch (_: Exception) { true }
        }

        // ── Forward ray target (HitSelect — own forward ray, not objectMouseOver) ──
        // objectMouseOver.entityHit is unreliable mid-fight (the crosshair can briefly leave the
        // entity). This casts its own ray from the player's eyes along the look direction (the same
        // technique Reach uses) and returns the entity the line hits, if any.
        EventBridge.registerForwardRayTargetProvider {
            try {
                val mc = getMc() ?: return@registerForwardRayTargetProvider null
                val world = getWorld() ?: return@registerForwardRayTargetProvider null
                val player = getPlayer() ?: return@registerForwardRayTargetProvider null
                val renderView = getRenderViewEntity(mc, player) ?: return@registerForwardRayTargetProvider null
                val eyesObj = MappingContext.invokeMethod(renderView, "forge:entity_getPositionEyes", 1.0f) ?: return@registerForwardRayTargetProvider null
                val lookObj = MappingContext.invokeMethod(renderView, "forge:entity_getLook", 1.0f) ?: return@registerForwardRayTargetProvider null
                val eyes = mcVec3ToVec3(eyesObj) ?: return@registerForwardRayTargetProvider null
                val look = mcVec3ToVec3(lookObj)?.normalize() ?: return@registerForwardRayTargetProvider null

                val maxReach = 4.5
                val endMc = coreVec3ToMcVec3(Vec3(eyes.x + look.x * maxReach, eyes.y + look.y * maxReach, eyes.z + look.z * maxReach))
                // Nearest block along the ray caps how far an entity can be hit (no wallhack).
                var reach = maxReach
                val blockHit = MappingContext.invokeMethod(world, "forge:world_rayTraceBlocks", eyesObj, endMc, false, true, false)
                if (blockHit != null) {
                    val hitVecObj = MappingContext.getFieldValue(blockHit, "forge:movingObjectPosition_hitVec")
                    val hitVec = mcVec3ToVec3(hitVecObj)
                    if (hitVec != null) {
                        val dx = hitVec.x - eyes.x; val dy = hitVec.y - eyes.y; val dz = hitVec.z - eyes.z
                        reach = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                    }
                }

                val baseBox = MappingContext.invokeMethod(renderView, "forge:entity_getEntityBoundingBox") ?: return@registerForwardRayTargetProvider null
                val aabbClass = Class.forName("net.minecraft.util.AxisAlignedBB")
                val addCoord = aabbClass.getMethod("func_72317_d", Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
                val expandBB = aabbClass.getMethod("func_72321_a", Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
                val extended = addCoord.invoke(baseBox, look.x * reach, look.y * reach, look.z * reach)
                val searchBox = expandBB.invoke(extended, 1.0, 1.0, 1.0) ?: return@registerForwardRayTargetProvider null

                @Suppress("UNCHECKED_CAST")
                val entities = (MappingContext.invokeMethod(world, "forge:world_getEntitiesWithinAABBExcludingEntity", renderView, searchBox) as? List<Any>) ?: return@registerForwardRayTargetProvider null

                var bestEntity: Any? = null
                var bestT = reach
                for (e in entities) {
                    if (!isTargetTypeAllowed(e)) continue
                    // Same fail-open rule as the forward raycast above.
                    val collidable = try { MappingContext.invokeMethod(e, "forge:entity_canBeCollidedWith") as? Boolean ?: true } catch (_: Exception) { true }
                    if (!collidable) continue
                    val box = MappingContext.invokeMethod(e, "forge:entity_getEntityBoundingBox") ?: continue
                    val bMin = bbMin(box) ?: continue
                    val bMax = bbMax(box) ?: continue
                    val t = ReachRaycast.intersectBox(eyes, look, bMin, bMax, reach) ?: continue
                    if (t < bestT) {
                        bestT = t
                        bestEntity = e
                    }
                }
                if (bestEntity == null) return@registerForwardRayTargetProvider null
                val id = MappingContext.getFieldValue(bestEntity, "forge:entity_entityId") as? Int ?: return@registerForwardRayTargetProvider null
                ForgeStateExtractor.extractTargetState(id)
            } catch (_: Exception) { null }
        }

        // AimAssist's Nemui-style target selector: nearest viable entity inside the FOV cone + range.
        // Runs on the background tick thread (read-only MC access). Wrapped so a mapping failure
        // never kills module dispatch.
        EventBridge.registerFovNearestTargetProvider { fov, range ->
            try {
                val id = ForgeStateExtractor.getFovNearestTargetId(fov, range) ?: return@registerFovNearestTargetProvider null
                ForgeStateExtractor.extractTargetState(id)
            } catch (_: Exception) { null }
        }
    }

    /** TargetFilter (Player category) gate: whether this entity type may be a combat target.
     *  Players = EntityPlayer; everything else living = mob. Both-on (module disabled) = allow all. */
    private fun isTargetTypeAllowed(entity: Any): Boolean {
        return if (entityPlayerClass.isInstance(entity)) {
            EventBridge.targetFilterPlayers
        } else if (entityLivingBaseClass.isInstance(entity)) {
            EventBridge.targetFilterMobs
        } else {
            true // non-living (items, projectiles) — not a combat target, never blocked
        }
    }

    override fun unregisterListeners() {
        EventBridge.reset()
    }

    // ========== Platform Entry Points ==========

    private fun setPlayerRotation(rotation: Vec2) {
        val player = getPlayer() ?: return
        try {
            playerRotationYawField?.setFloat(player, rotation.yaw)
            playerRotationPitchField?.setFloat(player, rotation.pitch)
        } catch (_: Exception) {}
    }

    /** Rotate the knockback motion vector around Y by [EventBridge.knockbackDisplaceAngle] (local
     *  KnockbackDisplace). 0 angle = unchanged. Never throws. */
    private fun applyKnockbackDisplace(motion: Vec3): Vec3 {
        val angle = EventBridge.knockbackDisplaceAngle
        if (angle == 0f) return motion
        return try {
            val rad = Math.toRadians(angle.toDouble())
            val cos = kotlin.math.cos(rad)
            val sin = kotlin.math.sin(rad)
            Vec3(
                motion.x * cos - motion.z * sin,
                motion.y,
                motion.x * sin + motion.z * cos
            )
        } catch (_: Exception) { motion }
    }

    private fun applyMotion(motion: Vec3) {
        val player = getPlayer() ?: return
        try {
            MappingContext.getField("forge:entity_motionX")?.setDouble(player, motion.x)
            MappingContext.getField("forge:entity_motionY")?.setDouble(player, motion.y)
            MappingContext.getField("forge:entity_motionZ")?.setDouble(player, motion.z)
        } catch (_: Exception) {}
    }

    /**
     * Player velocity packet entry point (S12 entity velocity / S27 explosion), called from the
     * Netty thread. Hardened: any failure here (e.g. the target scan racing the main thread's
     * entity-list mutation during an explosion) must NEVER escape to Netty and kill the
     * connection — always degrade to Pass.
     */
    fun onVelocityPacket(packetHandle: Any): PlatformCommand {
        return try {
            val player = ForgeStateExtractor.extractPlayerState()
            val targetId = ForgeStateExtractor.getCurrentTargetId()
            val target = if (targetId != null) ForgeStateExtractor.extractTargetState(targetId) else null

            // S12 motion fields are Int (1/8000 blocks/tick); S27 explosion motion fields are
            // Float. Try S12 keys first; for S27 packets they resolve to a different class, so
            // read the S27-specific keys instead (never throw — getFieldValue degrades to null).
            var rawX = MappingContext.getFieldValue(packetHandle, "forge:velocity_motionX")
            var rawY = MappingContext.getFieldValue(packetHandle, "forge:velocity_motionY")
            var rawZ = MappingContext.getFieldValue(packetHandle, "forge:velocity_motionZ")
            if (rawX == null && s27PacketClass.isInstance(packetHandle)) {
                rawX = MappingContext.getFieldValue(packetHandle, "forge:S27PacketExplosion_motionX")
                rawY = MappingContext.getFieldValue(packetHandle, "forge:S27PacketExplosion_motionY")
                rawZ = MappingContext.getFieldValue(packetHandle, "forge:S27PacketExplosion_motionZ")
            }

            val motionX: Double
            val motionY: Double
            val motionZ: Double

            when {
                rawX is Int -> {
                    motionX = rawX / 8000.0
                    motionY = (rawY as? Int ?: 0) / 8000.0
                    motionZ = (rawZ as? Int ?: 0) / 8000.0
                }
                rawX is Double -> {
                    motionX = rawX
                    motionY = rawY as? Double ?: 0.0
                    motionZ = rawZ as? Double ?: 0.0
                }
                rawX is Float -> {
                    motionX = rawX.toDouble()
                    motionY = (rawY as? Float ?: 0f).toDouble()
                    motionZ = (rawZ as? Float ?: 0f).toDouble()
                }
                else -> {
                    motionX = 0.0; motionY = 0.0; motionZ = 0.0
                }
            }

            val ctx = VelocityContext(
                // NOTE: Knockback Displace is applied IN THE INTERCEPTOR by rewriting the S12
                // packet fields (applyDisplaceToPacket) BEFORE this — MC lands the packet's own
                // values on the vanilla path, so the packet rewrite is the effective path. This
                // originalMotion is the (already displaced) packet value.
                originalMotion = Vec3(motionX, motionY, motionZ),
                player = player,
                target = target,
                packetHandle = packetHandle,
                isKnockbackHit = !s27PacketClass.isInstance(packetHandle)
            )

            EventBridge.notifyVelocityPacket(ctx)
            EventBridge.onVelocityPacket(ctx)
        } catch (_: Exception) {
            PlatformCommand.Pass(Vec3(0.0, 0.0, 0.0))
        }
    }

    fun onTick() {
        // Apply pending motion override
        // Guard: only clear pendingMotion if the player is available to apply it.
        // If the player is null (e.g., just disconnected), preserve pendingMotion
        // for the next tick so the velocity modification is not silently lost.
        pendingMotion?.let { motion ->
            val player = getPlayer()
            if (player != null) {
                applyMotion(motion)
                pendingMotion = null
            }
        }

        // Hardened: a reflection/mapping failure anywhere in extraction must NEVER stop
        // module dispatch. Each extractor already falls back safely internally; wrap the
        // remaining calls so EventBridge.onTick is always reached. Previously an unguarded
        // throw here (e.g. extractTargetState) silently killed every combat/player module's
        // tick — which is exactly how AutoBlock's Srg/Switch stopped responding.
        val player = runCatching { ForgeStateExtractor.extractPlayerState() }.getOrElse { PlayerState.EMPTY }
        val target = runCatching {
            val targetId = ForgeStateExtractor.getCurrentTargetId()
            if (targetId != null) ForgeStateExtractor.extractTargetState(targetId) else null
        }.getOrNull()
        val crosshair = runCatching {
            val crosshairId = ForgeStateExtractor.getCrosshairTargetId()
            if (crosshairId != null) ForgeStateExtractor.extractTargetState(crosshairId) else null
        }.getOrNull()
        EventBridge.crosshairTarget = crosshair

        // Fresh-hit attack notification (SuperKnockback). Reliable 20Hz "just got hit" signal:
        // the crosshair target's hurtTime jumps >0 on a fresh hit and is 0 between hits, so the
        // 0 -> >0 rising edge marks an attack. Mirrors LB's AttackEvent for modules that need it.
        if (crosshair != null) {
            val hurt = crosshair.hurtTime > 0
            if (hurt && !prevTargetHurt) {
                EventBridge.notifyAttack(crosshair)
            }
            prevTargetHurt = hurt
        } else {
            prevTargetHurt = false
        }

        EventBridge.onTick(player, target)
    }

    /**
     * Apply synthetic input (attack / use-item) on the MC RENDER thread.
     *
     * Called from ForgeBootstrap.render() (which runs inside the Display.update()
     * bytecode hook — i.e. the MC main thread). Combat modules set their desired
     * state on the 20Hz background thread via EventBridge.syntheticAttack /
     * EventBridge.syntheticUse; this method is the only place that writes the
     * real KeyBinding.pressed fields, so there is no cross-thread race and MC's
     * main-thread input loop actually sees the press.
     *
     * The OR with the physical mouse button is essential: it preserves the player's
     * own clicks (never releases a manually-held attack) while still driving the
     * synthetic press from the main thread.
     */
    // ========== ClickAssist — real extra clicks through AWT Robot ==========
    //
    // Raven generates its assist clicks with java.awt.Robot: mouseRelease + mousePress using the
    // legacy masks BUTTON1_MASK (16) / BUTTON3_MASK (4). Because that is a real OS event it
    // re-enters the client through LWJGL and is processed exactly like the player's own click — no
    // shared synthetic flags, and it behaves identically in the world, in GUIs and while mining.
    //
    // The echo problem: our own emitted press shows up as a physical press on the next poll, so it
    // must not queue another assist click. Raven clears a boolean when the echo arrives and has a
    // known hole — the flag is only cleared inside its "no screen / not eating" branch, so a GUI or
    // an item use starting before the echo leaves it armed and it then swallows the player's next
    // real click. A short time window closes that hole without depending on which branch runs.
    private val clickRobot by lazy {
        try { java.awt.Robot() } catch (_: Throwable) { null }
    }
    private var assistPrevLeftDown = false
    private var assistPrevRightDown = false
    private var assistEmittedLeftNano = 0L
    private var assistEmittedRightNano = 0L
    private var assistOwedLeftRelease = false
    private var assistOwedRightRelease = false
    /** Physical press edges inside this window after our own emission are our Robot echo. */
    private val assistEchoWindowNs = 250_000_000L

    /**
     * Per-frame ClickAssist driver, called from the render hook on the MC main thread. Emits extra
     * clicks only on physical press edges and only when the module's advisor asks for them.
     */
    fun applyClickAssistFrame() {
        val robot = clickRobot ?: return
        try {
            assistButton(robot, 0)
            assistButton(robot, 1)
        } catch (_: Throwable) {}
    }

    private fun assistButton(robot: java.awt.Robot, button: Int) {
        val mask = if (button == 0) 16 else 4 // BUTTON1_MASK / BUTTON3_MASK — the masks Raven uses
        val down = isMouseButtonDown(button)
        val wasDown = if (button == 0) assistPrevLeftDown else assistPrevRightDown
        if (button == 0) assistPrevLeftDown = down else assistPrevRightDown = down

        // Un-stick (Raven's fix()): if we left a synthetic press behind and the player is no longer
        // holding that button, release it. Only ever runs while the player is NOT holding, so it
        // can never release the player's own click.
        val owed = if (button == 0) assistOwedLeftRelease else assistOwedRightRelease
        if (owed && !down) {
            try { robot.mouseRelease(mask) } catch (_: Throwable) {}
            if (button == 0) assistOwedLeftRelease = false else assistOwedRightRelease = false
        }

        if (!down || wasDown) return // physical press edges only

        val now = System.nanoTime()
        val lastEmit = if (button == 0) assistEmittedLeftNano else assistEmittedRightNano
        if (lastEmit != 0L && now - lastEmit < assistEchoWindowNs) return // our own echo

        val extra = EventBridge.askClickAssist(button)
        if (extra <= 0) return

        repeat(extra.coerceIn(1, 3)) {
            robot.mouseRelease(mask)
            robot.mousePress(mask)
        }
        if (button == 0) {
            assistEmittedLeftNano = now
            assistOwedLeftRelease = true
        } else {
            assistEmittedRightNano = now
            assistOwedRightRelease = true
        }
    }

    fun applySyntheticInput() {
        try {
            val mc = getMc() ?: return
            val gs = MappingContext.getFieldValue(mc, "forge:mc_gameSettings") ?: return
            val keyBindAttack = MappingContext.getFieldValue(gs, "forge:gs_keyBindAttack") ?: return
            // Refresh the shared physical-button flag here — main thread, once per frame — instead
            // of only on the 20Hz tick. Reach's press edge and every tap module's "is the player
            // really clicking" check read it, and a ~50ms-stale sample both misses fast clicks and
            // flickers. This is the same LWJGL state MC reads in its next input pass.
            val physicalAttack = isMouseButtonDown(0)
            EventBridge.isLeftMousePhysicallyDown = physicalAttack

            // A screen is open: MC does not process world keybinds there, and a synthetic press
            // left on a KeyBinding leaks into the world the instant the screen closes (a stuck
            // attack/mining key). So write no world input at all. Clicks INSIDE the screen are
            // driven separately, through the screen's own mouse handler, when AutoClicker's InGui
            // option is on (raven-bs guards its world path the same way and clicks inventories
            // separately).
            if (EventBridge.isGuiOpen) {
                val attackKeyCode = (MappingContext.getFieldValue(keyBindAttack, "forge:keybinding_keyCode") as? Int) ?: 0
                if (attackKeyCode != 0) releaseClick(attackKeyCode, leftClick = true)
                val guiUseBind = MappingContext.getFieldValue(gs, "forge:gs_keyBindUseItem")
                val guiUseKeyCode = (guiUseBind?.let { MappingContext.getFieldValue(it, "forge:keybinding_keyCode") } as? Int) ?: 0
                if (guiUseKeyCode != 0) releaseClick(guiUseKeyCode, leftClick = false)
                EventBridge.mouseButton0 = physicalAttack
                EventBridge.mouseButton1 = isMouseButtonDown(1)
                if (EventBridge.clickInGuiAllowed && physicalAttack) driveGuiClickCadence(mc)
                else guiClickLastNano = 0L
                return
            }
            if (EventBridge.syntheticAttackOverride) {
                // Full clicker active: drive a smooth, time-based press/release cadence
                // (Raven-style). Each click = press (hold ~delay/2) then release (at delay),
                // regenerated per cycle at the requested CPS. This avoids the one-shot-pulse
                // stutter from the 20Hz strategy. Also writes LWJGL Mouse.buttons so keystrokes
                // HUDs that read physical mouse state see the click.
                // attackAllowed (HitSelect) also gates here: when false the cadence is held in the
                // release state, so the click selector can swallow AutoClicker's clicks too.
                val now = EventBridge.syntheticAttack
                // keyCode is a FIELD on KeyBinding, not a method — invokeMethod() always
                // returned null here, so keyCode stayed 0 and the synthetic click cadence
                // never ran (see ForgeBootstrap for the same fix on the movement keys).
                val keyCode = (MappingContext.getFieldValue(keyBindAttack, "forge:keybinding_keyCode") as? Int) ?: 0
                // BreakBlocks: the player is mining (crosshair on a block, attack physically held).
                // Hold the key down and skip the burst cadence — pulsing hands the key back up
                // between clicks, so vanilla restarts the block's damage from zero every cycle and
                // the block can never break. raven-bs holds the key in exactly this situation.
                val miningHold = EventBridge.clickHoldOnBlock && physicalAttack && EventBridge.isLookingAtBlock
                if (miningHold && keyCode != 0) {
                    try {
                        MappingContext.invokeMethod(null, "forge:keybinding_setKeyBindState", keyCode, true)
                    } catch (_: Exception) {}
                    setMouseButtonPhysical(0, true)
                    EventBridge.mouseButton0 = true
                    attackCadenceActive = false
                    attackPressed = false
                } else if (now && keyCode != 0 && EventBridge.currentAttackGate()) {
                    driveClickCadence(keyCode, leftClick = true)
                } else {
                    releaseClick(keyCode, leftClick = true)
                    attackCadenceActive = false
                }
                // Preserve the physical button in the underlying mouse buffer so a manual
                // click still reads as down (this path also ORs the physical state).
                setMouseButtonPhysical(0, EventBridge.syntheticAttack || physicalAttack)
            } else {
                // Assist modules (ClickAssist/BlockHit/AutoBlock): augment the player's
                // own input — OR with the physical button so their press is not stolen.
                // attackAllowed (HitSelect) can force the attack key OFF to swallow clicks
                // that would land inside the target's i-frame window (wasted hits). Decided on
                // the main thread at this exact frame via the attack-gate provider.
                val gateAllowed = EventBridge.currentAttackGate()
                val effectiveAttack = EventBridge.syntheticAttack || (physicalAttack && gateAllowed)
                keybindingPressedField?.setBoolean(keyBindAttack, effectiveAttack)
                // Keep the physical mouse buffer reflecting the real button for the Keystrokes
                // HUD (do not rewrite it on a swallowed click).
                setMouseButtonPhysical(0, EventBridge.syntheticAttack || physicalAttack)
            }
            // Forward/back keys (WTap/STap) — applied on the main thread so the tap lands
            // in MC's input and the Keystrokes W/S keys flash. When a tap module overrides,
            // drive the key fully from the synthetic state.
            val keyBindForward = MappingContext.getFieldValue(gs, "forge:gs_keyBindForward")
            if (keyBindForward != null) {
                keybindingPressedField?.setBoolean(
                    keyBindForward,
                    if (EventBridge.syntheticForwardOverride) EventBridge.syntheticForward
                    else EventBridge.syntheticForward || physicalKeyDown("forge:gs_keyBindForward")
                )
            }
            val keyBindBack = MappingContext.getFieldValue(gs, "forge:gs_keyBindBack")
            if (keyBindBack != null) {
                keybindingPressedField?.setBoolean(
                    keyBindBack,
                    if (EventBridge.syntheticBackOverride) EventBridge.syntheticBack
                    else EventBridge.syntheticBack || physicalKeyDown("forge:gs_keyBindBack")
                )
            }
            // Strafe keys (ADTap) — keyboard only, always OR-ed with the physical key
            // so the player's own strafing is never stolen. No override path by design.
            val keyBindLeft = MappingContext.getFieldValue(gs, "forge:gs_keyBindLeft")
            if (keyBindLeft != null) {
                keybindingPressedField?.setBoolean(
                    keyBindLeft,
                    EventBridge.syntheticLeft || physicalKeyDown("forge:gs_keyBindLeft")
                )
            }
            val keyBindRight = MappingContext.getFieldValue(gs, "forge:gs_keyBindRight")
            if (keyBindRight != null) {
                keybindingPressedField?.setBoolean(
                    keyBindRight,
                    EventBridge.syntheticRight || physicalKeyDown("forge:gs_keyBindRight")
                )
            }
            val keyBindUse = MappingContext.getFieldValue(gs, "forge:gs_keyBindUseItem") ?: return
            if (EventBridge.syntheticUseOverride) {
                val nowUse = EventBridge.syntheticUse
                val useKeyCode = (MappingContext.getFieldValue(keyBindUse, "forge:keybinding_keyCode") as? Int) ?: 0
                if (nowUse && useKeyCode != 0) {
                    driveClickCadence(useKeyCode, leftClick = false)
                } else {
                    releaseClick(useKeyCode, leftClick = false)
                    useCadenceActive = false
                }
                setMouseButtonPhysical(1, EventBridge.syntheticUse || isMouseButtonDown(1))
            } else {
                keybindingPressedField?.setBoolean(keyBindUse, EventBridge.syntheticUse || isMouseButtonDown(1))
                // Reflect the effective right-button state (incl. AutoBlock/BlockHit
                // synthetic use) in the mouse buffer so the Keystrokes RMB key flashes.
                setMouseButtonPhysical(1, EventBridge.syntheticUse || isMouseButtonDown(1))
            }
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════
    //  Render-frame overrides — NoHurtCam / NoFOV
    // ═══════════════════════════════════════════
    //
    // Both modules used to write entity/renderer fields from the 20 Hz tick. That is the wrong clock
    // for either of them: the game reads both fields during its own per-frame render pass, and the
    // FOV modifier is additionally rebuilt once per game tick. Everything below runs on the MC main
    // thread from ForgeBootstrap.render() (the Display.update() hook = once per rendered frame,
    // after MC has drawn), and every write is guarded by an EventBridge flag that defaults to false,
    // so with the modules off these functions do nothing at all.

    /** `maxHurtTime` written while NoHurtCam is on — see [applyHurtCamOverride]. */
    private const val HURT_CAM_NEUTRAL_MAX_HURT_TIME = 1_000_000

    /** The only value vanilla ever assigns to `maxHurtTime` (`hurtTime = maxHurtTime = 10`). */
    private const val VANILLA_MAX_HURT_TIME = 10

    /** The system properties the injected camera redirect reads — keep in step with Transformer. */
    private const val PROP_FREELOOK_YAW = "doppel.freelookYaw"
    private const val PROP_FREELOOK_PITCH = "doppel.freelookPitch"

    /** Whether the previous frame re-applied the movement keys (InvMove) — see [applyGuiMovement]. */
    private var guiMovementApplied = false

    /**
     * NoHurtCam — cancel the vanilla hurt-camera *tilt* at render time.
     *
     * 1.8.9 source (`EntityRenderer.hurtCameraEffect`):
     * ```
     * float f = (float) entitylivingbase.hurtTime - partialTicks;
     * if (f < 0.0F) return;
     * f = f / (float) entitylivingbase.maxHurtTime;
     * f = MathHelper.sin(f * f * f * f * (float) Math.PI);
     * float f2 = entitylivingbase.attackedAtYaw;
     * GlStateManager.rotate(-f2, 0, 1, 0);
     * GlStateManager.rotate(-f * 14.0F, 0, 0, 1);   // ← the shake
     * GlStateManager.rotate(f2, 0, 1, 0);           // ← these two cancel out
     * ```
     * `maxHurtTime` is the divisor of the shake angle and nothing else: raising it drives the roll to
     * ~0°, which is what "cancel the camera tilt" means here. This deliberately does **not** touch
     * `hurtTime` — that is the "just got hit" signal (`hurtTime > 0`) HitSelect and Velocity read,
     * and it drives the red damage overlay. The large value also cannot leak into `hurtTime`: every
     * assignment in vanilla is the chained `this.hurtTime = this.maxHurtTime = 10;` (three sites in
     * `EntityLivingBase` plus `EntityPlayerSP.setPlayerSPHealth`), so `hurtTime` is always set from
     * the fresh vanilla 10 before our value could be read.
     *
     * Only written while `hurtTime > 0` (i.e. only when the renderer could draw a tilt at all) and
     * only when the field still holds the vanilla 10, so nothing of ours is left lying around between
     * fights, and cleared back to 10 on the disable edge. One frame per hit can still carry the
     * vanilla kick — the hit resets `maxHurtTime` inside the game tick that precedes that frame's
     * render, and no hook of ours runs in between; a fully seamless cancel needs the same
     * `GlStateManager.rotate` redirect Raven uses, which lives in the agent's Transformer.
     */
    private fun applyHurtCamOverride() {
        try {
            val field = playerMaxHurtTimeField ?: return
            val player = getPlayer()
            if (EventBridge.suppressHurtCam) {
                if (player == null) return
                val hurtTime = playerHurtTimeField?.getInt(player) ?: return
                if (hurtTime <= 0) return
                if (field.getInt(player) == VANILLA_MAX_HURT_TIME) {
                    field.setInt(player, HURT_CAM_NEUTRAL_MAX_HURT_TIME)
                }
            } else if (player != null && field.getInt(player) == HURT_CAM_NEUTRAL_MAX_HURT_TIME) {
                field.setInt(player, VANILLA_MAX_HURT_TIME)
            }
        } catch (_: Exception) {}
    }


    // ── Mouse delta: the look input, taken the way the reference clients take it ──
    //
    // Raven's overrideMouse() calls mouseHelper.mouseXYChange() and reads deltaX/deltaY; this does the
    // same and then ZEROES them, so the application vanilla makes later in the tick moves nothing and the
    // body keeps its rotation without anyone having to put it back.
    private fun applyMouseDelta() {
        try {
            val mc = getMc() ?: return
            val helper = MappingContext.getFieldValue(mc, "forge:mc_mouseHelper") ?: return
            // Read the delta the game itself stored this tick — deliberately WITHOUT calling
            // mouseXYChange(), which re-reads Mouse.getDX(): that is consumed once per frame, so calling
            // it from here stored zeroes and the angles never moved. The stored deltaX/deltaY stay valid
            // until the game's next refresh, which is exactly why the reference clients read them.
            val dx = (MappingContext.getFieldValue(helper, "forge:mouseHelper_deltaX") as? Int) ?: 0
            val dy = (MappingContext.getFieldValue(helper, "forge:mouseHelper_deltaY") as? Int) ?: 0
            if (dx != 0 || dy != 0) {
                freelookDeltaSeen = true
                // Freelook accumulates HERE, once per rendered frame, not in the 20 Hz tick: the camera
                // is drawn every frame, so a tick-rate angle is a camera that steps 20 times a second.
                // This is the same scaling vanilla applies to the mouse, times the module's sensitivity.
                if (EventBridge.freeLookActive) {
                    val sens = EventBridge.mouseSensitivity * 0.6f + 0.2f
                    val mult = sens * sens * sens * 8.0f
                    val scale = mult * 0.15f * EventBridge.freelookSensitivity
                    val flip = if (EventBridge.freelookInvertY) 1f else -1f
                    EventBridge.freeLookYaw += dx * scale
                    EventBridge.freeLookPitch =
                        (EventBridge.freeLookPitch + dy * scale * flip).coerceIn(-90f, 90f)
                }
                // Zero them so the turn vanilla makes later in the tick moves nothing; the body then keeps
                // its rotation without anyone having to put it back.
                MappingContext.getField("forge:mouseHelper_deltaX")?.setInt(helper, 0)
                MappingContext.getField("forge:mouseHelper_deltaY")?.setInt(helper, 0)
            } else if (EventBridge.freeLookActive && !freelookDeltaSeen) {
                // One line, once: if the angles stay at zero, this is the place that says so.
                freelookDeltaSeen = true
                io.doppel.core.logging.CoreLogger.error(
                    "[Freelook] MouseHelper.deltaX/deltaY both zero — no look input is reaching the camera"
                )
            }
            val gs = MappingContext.getFieldValue(mc, "forge:mc_gameSettings")
            if (gs != null && mouseSensitivityField == null) {
                mouseSensitivityField = gs.javaClass.declaredFields.firstOrNull { it.name.contains("mouseSensitivity") }
            }
            mouseSensitivityField?.let {
                EventBridge.mouseSensitivity = try { it.getFloat(gs) } catch (_: Exception) { 0.5f }
            }
        } catch (_: Exception) {}
    }

    // ── Freelook: publish the angles the injected camera redirect reads ──
    //
    // The transform in EntityRenderer / ActiveRenderInfo / RenderGlobal replaces every read of
    // Entity.rotationYaw/rotationPitch with "the value in this system property, or the entity's own".
    // A property is the one channel that crosses to those classes with no ClassLoader on the path, and
    // it is absent whenever the module is off, so the vanilla value flows through untouched.
    private val gsThirdPersonViewField by lazy { MappingContext.getField("forge:gs_thirdPersonView") }

    /** The player's own third-person choice, put back when Freelook lets go (Raven's previousPerspective). */
    private var freeLookSavedPerspective: Int? = null
    @Volatile private var mouseSensitivityField: java.lang.reflect.Field? = null
    @Volatile private var freelookDeltaSeen = false
    private var freeLookLogged = false

    private fun applyFreeLook() {
        try {
            val mc = getMc() ?: return
            val gs = MappingContext.getFieldValue(mc, "forge:mc_gameSettings")
            val view = gsThirdPersonViewField

            if (!EventBridge.freeLookActive) {
                System.clearProperty(PROP_FREELOOK_YAW)
                System.clearProperty(PROP_FREELOOK_PITCH)
                freelookDeltaSeen = false
                if (freeLookSavedPerspective != null) {
                    try {
                        if (gs != null && view != null) view.setInt(gs, freeLookSavedPerspective!!)
                    } catch (_: Exception) {}
                    freeLookSavedPerspective = null
                }
                freeLookLogged = false
                return
            }

            System.setProperty(PROP_FREELOOK_YAW, java.lang.Float.toString(EventBridge.freeLookYaw))
            System.setProperty(PROP_FREELOOK_PITCH, java.lang.Float.toString(EventBridge.freeLookPitch))

            // Raven's enterPerspective(): third person, so the vanilla orbit camera does the work and the
            // player's body keeps rendering with its own rotation.
            try {
                if (gs != null && view != null) {
                    if (freeLookSavedPerspective == null) freeLookSavedPerspective = view.getInt(gs)
                    if (view.getInt(gs) == 0) view.setInt(gs, 1)
                }
            } catch (_: Exception) {}

            if (!freeLookLogged) {
                freeLookLogged = true
                io.doppel.core.logging.CoreLogger.info(
                    "[Freelook] camera angles published (yaw=" + EventBridge.freeLookYaw +
                        ", pitch=" + EventBridge.freeLookPitch + "), third-person view = " +
                        (freeLookSavedPerspective ?: -1) + " -> 1"
                )
            }
        } catch (_: Exception) {}
    }

    /**
     * Called once per rendered frame from ForgeBootstrap.render() (MC main thread).
     * No-op unless a render module asked for it.
     */
    fun applyFrameOverrides() {
        applyHurtCamOverride()
        applyMouseDelta()
        applyFreeLook()
    }

    // ═══════════════════════════════════════════
    //  GUI movement — InvMove
    // ═══════════════════════════════════════════

    /**
     * InvMove — re-apply the movement key states while a screen is open (MC main thread, per frame).
     *
     * Vanilla drops movement with a screen open because opening it calls `KeyBinding.unPressAllKeys()`
     * and, for every screen that is not chat, `Minecraft.runTick()` skips its keyboard loop entirely —
     * so the KeyBindings `MovementInputFromOptions.updatePlayerMoveState()` reads stay down. Writing the
     * *state* back (never consuming a key event, so the screen still gets every press) is what keeps
     * the player walking; MC's own next-tick movement-input update then sees the same keys it would
     * have seen without the screen, which is the "refresh the movement input" part — no extra call is
     * needed.
     *
     * The states come from the module layer's *effective* accessors, so a WTap/STap/ADTap synthetic
     * key or a live JumpReset pulse behaves inside the screen exactly as it does outside, and the
     * Keystrokes HUD keeps telling the truth. Nothing here runs unless [EventBridge.keepMovementInGui]
     * is set by InvMove, and the module's options decide the screen filter and the sprint handling.
     */
    fun applyGuiMovement() {
        try {
            val mc = getMc() ?: return
            val screen = MappingContext.getFieldValue(mc, "forge:mc_currentScreen")
            val containerScreen = screen != null && guiContainerClass?.isInstance(screen) == true
            if (!InvMoveRules.shouldReapplyMovement(
                    keepMovementInGui = EventBridge.keepMovementInGui,
                    screenOpen = screen != null,
                    onlyInventory = EventBridge.guiMovementOnlyInventory,
                    containerScreen = containerScreen
                )
            ) {
                // We were holding keys we own, and now we must not (module switched off, or the
                // screen filter changed under us): release them, because MC will not — with a
                // container screen open its keyboard loop is skipped, so a press left behind here
                // would survive until the screen closes and then walk the player on its own.
                // The screen==null case is deliberately NOT released: on the frame the screen closes
                // the state we wrote is still the player's real key state (it tracks the physical
                // keys every frame), and clearing it would drop a W the player is still holding.
                if (guiMovementApplied && screen != null) releaseGuiMovementKeys()
                guiMovementApplied = false
                return
            }

            guiMovementApplied = true
            setKeyBindPressed("forge:gs_keyBindForward", EventBridge.isKeyForwardActive)
            setKeyBindPressed("forge:gs_keyBindBack", EventBridge.isKeyBackActive)
            setKeyBindPressed("forge:gs_keyBindLeft", EventBridge.isKeyLeftActive)
            setKeyBindPressed("forge:gs_keyBindRight", EventBridge.isKeyRightActive)
            setKeyBindPressed("forge:gs_keyBindJump", EventBridge.isKeyJumpActive)
            // Shift is part of "keep walking": unPressAllKeys() drops sneaking as well.
            setKeyBindPressed("forge:gs_keyBindSneak", physicalKeyDown("forge:gs_keyBindSneak"))

            val player = getPlayer() ?: return
            val sprinting = try {
                MappingContext.invokeMethod(player, "forge:player_isSprinting") as? Boolean ?: false
            } catch (_: Exception) { false }

            if (InvMoveRules.shouldSuppressSprinting(
                    EventBridge.guiMovementKeepSprint, sprinting
                )
            ) {
                // "Keep sprint" off: a re-applied move input would let vanilla's own
                // `!isSprinting() && moveForward >= 0.8 && sprintKey` rule hold the sprint state,
                // so force it off like the reference does.
                EventBridge.setSprinting(false)
            } else {
                val caps = playerCapabilitiesField?.let { f -> f.get(player) }
                val allowFlying = caps?.let { capabilitiesAllowFlyingField?.getBoolean(it) } == true
                if (InvMoveRules.shouldKeepSprinting(
                        keepSprint = EventBridge.guiMovementKeepSprint,
                        alreadySprinting = sprinting,
                        forwardActive = EventBridge.isKeyForwardActive,
                        foodLevel = EventBridge.foodLevel,
                        allowFlying = allowFlying
                    )
                ) {
                    EventBridge.setSprinting(true)
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Release every movement KeyBinding InvMove may have pressed. Used only on the transition out of
     * the re-application while a screen is still open (module off / screen filter changed), where
     * nothing else will clear them: MC skips its keyboard loop for those screens, so a press left
     * behind would keep the player walking as soon as the screen closes.
     */
    private fun releaseGuiMovementKeys() {
        setKeyBindPressed("forge:gs_keyBindForward", false)
        setKeyBindPressed("forge:gs_keyBindBack", false)
        setKeyBindPressed("forge:gs_keyBindLeft", false)
        setKeyBindPressed("forge:gs_keyBindRight", false)
        setKeyBindPressed("forge:gs_keyBindJump", false)
        setKeyBindPressed("forge:gs_keyBindSneak", false)
    }

    /**
     * Resolve a member that has no entry in the mapping table: SRG name first (the runtime name under
     * Forge 1.8.9), MCP name second (deobfuscated/dev environments) — the same fallback order
     * MappingContext uses. The declaring class is walked up the hierarchy, because a member can sit on
     * a superclass in one mapping and on the subclass in another. Returns null rather than throwing,
     * so an unresolvable name degrades the feature to "off" instead of crashing the render loop.
     */
    private fun resolveField(className: String, srgName: String, mcpName: String): java.lang.reflect.Field? {
        return try {
            var clazz: Class<*>? = Class.forName(className)
            var found: java.lang.reflect.Field? = null
            while (clazz != null && found == null) {
                found = try {
                    clazz.getDeclaredField(srgName)
                } catch (_: NoSuchFieldException) {
                    try {
                        clazz.getDeclaredField(mcpName)
                    } catch (_: NoSuchFieldException) {
                        null
                    }
                }
                clazz = clazz.superclass
            }
            found?.also { it.isAccessible = true }
        } catch (_: Exception) {
            null
        }
    }

    // ========== Click Cadence Engine ==========

    private fun sampleCps(): Int {
        val lo = EventBridge.clickMinCps.coerceAtLeast(1)
        val hi = EventBridge.clickMaxCps.coerceAtLeast(lo)
        val base = if (EventBridge.clickRandomize) {
            lo + clickRandom.nextInt(hi - lo + 1)
        } else {
            lo // Randomize off → fixed speed (Slinky: only recommend off on trusted servers)
        }
        // Simulate exhaust: occasionally click slower for a short window (tiring hand).
        if (EventBridge.clickExhaust) {
            val now = System.currentTimeMillis()
            if (now >= exhaustUntil && clickRandom.nextInt(20) == 0) {
                // Every ~20 cycles, slow down for 300-700ms.
                exhaustUntil = now + 300L + clickRandom.nextInt(400)
            }
            if (now < exhaustUntil) {
                return (base * 0.7f).toInt().coerceAtLeast(1)
            }
        }
        return base
    }

    /**
     * Drive one press/release cadence while the clicker is active. Emits via
     * KeyBinding.setKeyBindState + onTick (fires the attack AND shows on keystrokes
     * HUDs) and keeps LWJGL Mouse.buttons in sync.
     */
    private fun driveClickCadence(keyCode: Int, leftClick: Boolean) {
        val now = System.currentTimeMillis()
        if (!cadenceActive(leftClick)) {
            startCadence(leftClick, now)
        }
        val downTime = if (leftClick) attackDownTime else useDownTime
        val upTime = if (leftClick) attackUpTime else useUpTime
        val pressed = if (leftClick) attackPressed else usePressed

        if (!pressed && now >= downTime) {
            // Press now, hold until upTime.
            pressKey(keyCode, leftClick)
            setPressed(leftClick, true)
        } else if (pressed && now >= upTime) {
            // Cycle complete: release, then start the next cadence.
            releaseKey(keyCode, leftClick)
            setPressed(leftClick, false)
            // Butterfly: schedule a tight second press+release inside the SAME cycle before the
            // next cadence (legit butterfly double-click, recommended >15 CPS to avoid flagging).
            if (leftClick && EventBridge.clickPattern == "Butterfly") {
                val gap = 25L + clickRandom.nextInt(30) // 25-55ms between the two clicks
                attackButterflyPending = true
                attackButterflyDownTime = now + gap
                attackButterflyUpTime = now + gap + 40L
            }
            startCadence(leftClick, now)
        }

        // Butterfly second click (attack side only).
        if (leftClick && attackButterflyPending) {
            if (now >= attackButterflyDownTime && !attackPressed) {
                pressKey(keyCode, leftClick)
                setPressed(leftClick, true)
            }
            if (attackPressed && now >= attackButterflyUpTime) {
                releaseKey(keyCode, leftClick)
                setPressed(leftClick, false)
                attackButterflyPending = false
            }
        }
    }

    private fun cadenceActive(leftClick: Boolean): Boolean =
        if (leftClick) attackCadenceActive else useCadenceActive

    /** Timestamp of the last synthetic click inside an open screen (AutoClicker "InGui"). */
    private var guiClickLastNano = 0L

    /**
     * Click inside an open screen at the clicker's CPS, at the cursor position.
     *
     * MC's screens consume real LWJGL mouse EVENTS (`Mouse.next()`), which cannot be synthesised by
     * writing the button buffer — so the click is delivered by calling the screen's own
     * `mouseClicked(x, y, button)` with the cursor mapped from window to GUI coordinates. This is
     * the same technique Raven uses (XD `Utils.inventoryClick`, b+ `inInvClick`). It runs on the
     * main thread from the render hook, which is where a screen expects input.
     */
    private fun driveGuiClickCadence(mc: Any) {
        try {
            val screen = MappingContext.getFieldValue(mc, "forge:mc_currentScreen") ?: return
            val cps = sampleCps().coerceAtLeast(1)
            val interval = 1_000_000_000L / cps
            val now = System.nanoTime()
            if (guiClickLastNano != 0L && now - guiClickLastNano < interval) return
            guiClickLastNano = now

            val dw = MappingContext.getFieldValue(mc, "forge:mc_displayWidth") as? Int ?: return
            val dh = MappingContext.getFieldValue(mc, "forge:mc_displayHeight") as? Int ?: return
            val sw = MappingContext.getFieldValue(screen, "forge:guiScreen_width") as? Int ?: return
            val sh = MappingContext.getFieldValue(screen, "forge:guiScreen_height") as? Int ?: return
            if (dw <= 0 || dh <= 0 || sw <= 0 || sh <= 0) return

            val mouseX = mouseGetX.invoke(null) as Int
            val mouseY = mouseGetY.invoke(null) as Int
            // Vanilla's window -> GUI coordinate transform.
            val guiX = mouseX * sw / dw
            val guiY = sh - mouseY * sh / dh - 1

            MappingContext.invokeMethod(screen, "forge:guiScreen_mouseClicked", guiX, guiY, 0)
            EventBridge.recordClick(0)
        } catch (_: Exception) {}
    }

    private fun startCadence(leftClick: Boolean, now: Long) {
        val cps = sampleCps().coerceAtLeast(1)
        val delay = 1000L / cps
        // Press ~half the cycle in, release at the full cycle (Raven timing).
        val downTime = now + delay / 2 - clickRandom.nextInt(10)
        val upTime = now + delay
        if (leftClick) {
            attackCadenceActive = true
            attackDownTime = downTime
            attackUpTime = upTime
            attackPressed = false
        } else {
            useCadenceActive = true
            useDownTime = downTime
            useUpTime = upTime
            usePressed = false
        }
    }

    private fun pressKey(keyCode: Int, leftClick: Boolean) {
        try {
            MappingContext.invokeMethod(null, "forge:keybinding_setKeyBindState", keyCode, true)
            MappingContext.invokeMethod(null, "forge:keybinding_onTick", keyCode)
        } catch (_: Exception) {}
        setMouseButtonPhysical(if (leftClick) 0 else 1, true)
        EventBridge.recordClick(if (leftClick) 0 else 1)
        // Mirror the effective button state so the Keystrokes HUD flashes exactly on the
        // click pulse (same as Raven reading Mouse.isButtonDown).
        if (leftClick) EventBridge.mouseButton0 = true else EventBridge.mouseButton1 = true
    }

    private fun releaseKey(keyCode: Int, leftClick: Boolean) {
        // Hand the key back to the PLAYER's own state instead of forcing it up.
        //
        // Forcing it up is what broke block breaking: every clicker cycle released the attack key,
        // vanilla reset the mining progress, and a block could never be broken while the clicker
        // ran. It also means a synthetic release can never steal a physically held button.
        // LWJGL's poll refreshes the button buffer every frame, so this reads the real hardware
        // state (not our own synthetic write).
        val physical = isMouseButtonDown(if (leftClick) 0 else 1)
        try {
            MappingContext.invokeMethod(null, "forge:keybinding_setKeyBindState", keyCode, physical)
        } catch (_: Exception) {}
        setMouseButtonPhysical(if (leftClick) 0 else 1, physical)
        if (leftClick) EventBridge.mouseButton0 = physical else EventBridge.mouseButton1 = physical
    }

    private fun releaseClick(keyCode: Int, leftClick: Boolean) {
        if (cadenceActive(leftClick) || (if (leftClick) attackPressed else usePressed)) {
            releaseKey(keyCode, leftClick)
            setPressed(leftClick, false)
        }
        if (leftClick) { attackCadenceActive = false; attackPressed = false }
        else { useCadenceActive = false; usePressed = false }
    }

    private fun setPressed(leftClick: Boolean, value: Boolean) {
        if (leftClick) attackPressed = value else usePressed = value
    }

    /**
     * Write the physical mouse-button state into LWJGL's internal Mouse.buttons buffer so
     * Mouse.isButtonDown(button) reflects our synthetic click (and the player's manual hold).
     * Same technique as Raven's setMouseButtonState. Never throws.
     */
    private fun setMouseButtonPhysical(button: Int, down: Boolean) {
        try {
            val bf = mouseButtonsField?.get(null) as? java.nio.ByteBuffer ?: return
            bf.put(button, (if (down) 1 else 0).toByte())
        } catch (_: Exception) {}
    }
}
