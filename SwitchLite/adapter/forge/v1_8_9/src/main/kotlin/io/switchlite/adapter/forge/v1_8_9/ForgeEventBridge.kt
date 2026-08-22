package io.switchlite.adapter.forge.v1_8_9

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.api.IEventBridge
import io.switchlite.core.model.*
import io.switchlite.core.util.Vec2
import io.switchlite.core.util.Vec3
import io.switchlite.agent.MappingContext

/**
 * Forge 1.8.9 event bridge — pure reflection, zero MC/Forge compile dependencies.
 * Translates module-layer requests into Minecraft state changes via MappingContext.
 */
object ForgeEventBridge : IEventBridge {

    // Lazy class references (runtime only)
    private val mouseClass by lazy { Class.forName("org.lwjgl.input.Mouse") }
    private val mouseIsButtonDown by lazy {
        mouseClass.getMethod("isButtonDown", Int::class.javaPrimitiveType)
    }
    private val entityLivingBaseClass by lazy { Class.forName("net.minecraft.entity.EntityLivingBase") }
    private val itemArmorClass by lazy { Class.forName("net.minecraft.item.ItemArmor") }
    private val armorMaterialClass by lazy { Class.forName("net.minecraft.item.ItemArmor\$ArmorMaterial") }
    private val clothMaterial by lazy { armorMaterialClass.enumConstants.firstOrNull { it.toString() == "CLOTH" } }

    // Packet class constructors (lazy, created once)
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

    private val blocksClass by lazy { Class.forName("net.minecraft.init.Blocks") }
    private val blocksAir by lazy { blocksClass.getField("AIR").get(null) }
    private val movingObjectTypeClass by lazy { Class.forName("net.minecraft.util.MovingObjectPosition\$MovingObjectType") }
    private val movingObjectTypeBlock by lazy { movingObjectTypeClass.enumConstants.firstOrNull { it.toString() == "BLOCK" } }

    // Cached field references for writes (avoid repeated getField lookups)
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
    private val mcLeftClickCounterField by lazy { MappingContext.getField("forge:mc_leftClickCounter") }
    private val mcRightClickDelayField by lazy { MappingContext.getField("forge:mc_rightClickDelayTimer") }
    private val entityRendererFovField by lazy { MappingContext.getField("forge:entityRenderer_fovModifierHand") }
    private val gsGammaField by lazy { MappingContext.getField("forge:gs_gammaSetting") }
    private val inventoryCurrentItemField by lazy { MappingContext.getField("forge:inventory_currentItem") }
    private val playerJumpTicksField by lazy { MappingContext.getField("forge:player_jumpTicks") }
    private val mcObjectMouseOverField by lazy { MappingContext.getField("forge:mc_objectMouseOver") }

    @Volatile
    var pendingMotion: Vec3? = null

    /** Previous tick's crosshair-target hurt state (for attack rising-edge detection). */
    private var prevTargetHurt = false

    // ========== Helpers ==========
    private fun getMc(): Any? = try {
        MappingContext.invokeMethod(null, "forge:mc_getMinecraft")
    } catch (_: Exception) { null }

    private fun getPlayer(): Any? = try {
        getMc()?.let { MappingContext.getFieldValue(it, "forge:mc_thePlayer") }
    } catch (_: Exception) { null }

    private fun getWorld(): Any? = try {
        getMc()?.let { MappingContext.getFieldValue(it, "forge:mc_theWorld") }
    } catch (_: Exception) { null }

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

    /** Read the current pressed state of a game-settings key binding. */
    private fun readKeyPressed(gsKey: String): Boolean {
        return try {
            val mc = getMc() ?: return false
            val gs = MappingContext.getFieldValue(mc, "forge:mc_gameSettings") ?: return false
            val keyBind = MappingContext.getFieldValue(gs, gsKey) ?: return false
            keybindingPressedField?.getBoolean(keyBind) ?: false
        } catch (_: Exception) { false }
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
                "STOP_SPRINTING" -> EventBridge.serverSprintState = false
                "START_SPRINTING" -> EventBridge.serverSprintState = true
                else -> {}
            }
        } catch (_: Exception) {}
    }

    // ========== Registration ==========
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

    override fun registerListeners() {
        EventBridge.registerRotationSetter { rotation -> setPlayerRotation(rotation) }
        EventBridge.registerMotionApplier { motion -> applyMotion(motion) }

        EventBridge.registerSprintSetter { sprinting ->
            val player = getPlayer() ?: return@registerSprintSetter
            MappingContext.invokeMethod(player, "forge:player_setSprinting", sprinting)
            // Keep the server-side sprint mirror in sync with local sprint changes.
            EventBridge.serverSprintState = sprinting
        }

        // Generic C0B entity-action sender (SuperKnockback Old/SneakPacket, ported from LB).
        EventBridge.registerEntityActionHandler { actionName ->
            sendEntityAction(actionName)
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

        EventBridge.registerPressForwardHandler { setKeyBindPressed("forge:gs_keyBindForward", true) }
        EventBridge.registerReleaseForwardHandler { setKeyBindPressed("forge:gs_keyBindForward", false) }
        EventBridge.registerPressBackHandler { setKeyBindPressed("forge:gs_keyBindBack", true) }
        EventBridge.registerReleaseBackHandler { setKeyBindPressed("forge:gs_keyBindBack", false) }

        EventBridge.registerJumpHandler {
            try {
                val player = getPlayer() ?: return@registerJumpHandler
                MappingContext.invokeMethod(player, "forge:player_jump")
            } catch (_: Exception) {}
        }

        EventBridge.registerSprintResetHandler { mode ->
            val player = getPlayer() ?: return@registerSprintResetHandler
            when (mode) {
                "Nostop" -> {
                    try {
                        sendPacket(c0bConstructor.newInstance(player, stopSprintingAction))
                        sendPacket(c0bConstructor.newInstance(player, startSprintingAction))
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

        EventBridge.registerCancelAttackHandler {
            setKeyBindPressed("forge:gs_keyBindAttack", false)
        }

        EventBridge.registerResetClickDelayHandler {
            try {
                val mc = getMc() ?: return@registerResetClickDelayHandler
                mcLeftClickCounterField?.setInt(mc, 0)
            } catch (_: Exception) {}
        }

        EventBridge.registerResetJumpDelayHandler {
            try {
                val player = getPlayer() ?: return@registerResetJumpDelayHandler
                playerJumpTicksField?.setInt(player, 0)
            } catch (_: Exception) {}
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

        EventBridge.registerSwitchSlotHandler { slot ->
            try {
                val player = getPlayer() ?: return@registerSwitchSlotHandler
                inventoryCurrentItemField?.setInt(player, slot)
                sendPacket(c09Constructor.newInstance(slot))
            } catch (_: Exception) {}
        }

        EventBridge.registerGetBestSlotHandler {
            var bestSlot = -1
            var bestSpeed = 1.0f
            val player = getPlayer() ?: return@registerGetBestSlotHandler -1
            val mc = getMc()
            val objMouseOver = try { MappingContext.getFieldValue(mc, "forge:mc_objectMouseOver") } catch (_: Exception) { null }
            if (objMouseOver == null) return@registerGetBestSlotHandler -1

            val typeOfHit = try { MappingContext.getFieldValue(objMouseOver, "forge:movingObjectPosition_typeOfHit") } catch (_: Exception) { null }
            if (typeOfHit !== movingObjectTypeBlock) return@registerGetBestSlotHandler -1

            val blockPos = try { MappingContext.getFieldValue(objMouseOver, "forge:movingObjectPosition_blockPos") } catch (_: Exception) { null }
            val world = getWorld() ?: return@registerGetBestSlotHandler -1
            val blockState = try { MappingContext.invokeMethod(world, "forge:world_getBlockState", blockPos) } catch (_: Exception) { null }
            val block = try { MappingContext.invokeMethod(blockState, "forge:iblockstate_block") } catch (_: Exception) { null }

            val inventory = try { MappingContext.getFieldValue(player, "forge:player_inventory") } catch (_: Exception) { null }
            for (i in 0..8) {
                val stack = try { MappingContext.invokeMethod(inventory, "forge:inventory_getStackInSlot", i) } catch (_: Exception) { null }
                if (stack == null) continue
                val item = try { MappingContext.getFieldValue(stack, "forge:itemStack_item") } catch (_: Exception) { null }
                if (item == null || block == null) continue
                val speed = try { MappingContext.invokeMethod(item, "forge:item_getDigSpeed", stack, block) as? Float } catch (_: Exception) { null }
                if (speed != null && speed > bestSpeed) {
                    bestSpeed = speed
                    bestSlot = i
                }
            }
            if (bestSpeed > 1.0f) bestSlot else -1
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
                val blockPosClass = Class.forName("net.minecraft.util.BlockPos")
                val blockPos = blockPosClass.getConstructor(Double::class.java, Double::class.java, Double::class.java)
                    .newInstance(posX, posY - 1.0, posZ)
                val bs = MappingContext.invokeMethod(world, "forge:world_getBlockState", blockPos)
                val b = MappingContext.invokeMethod(bs, "forge:iblockstate_block")
                b !== blocksAir
            } catch (_: Exception) { false }
        }

        EventBridge.registerRotationApplier { yaw, pitch ->
            try {
                val player = getPlayer() ?: return@registerRotationApplier
                playerRotationYawField?.setFloat(player, yaw)
                playerRotationPitchField?.setFloat(player, pitch)
            } catch (_: Exception) {}
        }

        EventBridge.registerResetHurtCamHandler {
            try {
                val player = getPlayer() ?: return@registerResetHurtCamHandler
                playerHurtTimeField?.setInt(player, 0)
            } catch (_: Exception) {}
        }

        EventBridge.registerResetFovModifierHandler {
            try {
                val mc = getMc() ?: return@registerResetFovModifierHandler
                val er = MappingContext.getFieldValue(mc, "forge:mc_entityRenderer") ?: return@registerResetFovModifierHandler
                entityRendererFovField?.setFloat(er, 1.0f)
            } catch (_: Exception) {}
        }

        EventBridge.registerGammaSetter { gamma ->
            try {
                val mc = getMc() ?: return@registerGammaSetter
                val gs = MappingContext.getFieldValue(mc, "forge:mc_gameSettings") ?: return@registerGammaSetter
                gsGammaField?.setFloat(gs, gamma)
            } catch (_: Exception) {}
        }

        EventBridge.registerRightClickDelayHandler { ticks ->
            try {
                val mc = getMc() ?: return@registerRightClickDelayHandler
                mcRightClickDelayField?.setInt(mc, ticks)
            } catch (_: Exception) {}
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
                        val hasTag = try { MappingContext.getFieldValue(stack, "forge:itemStack_hasTagCompound") as? Boolean } catch (_: Exception) { false }
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

        EventBridge.registerAttackTrigger {
            setKeyBindPressed("forge:gs_keyBindAttack", true)
        }
    }

    override fun unregisterListeners() {
        EventBridge.reset()
    }

    private fun setPlayerRotation(rotation: Vec2) {
        val player = getPlayer() ?: return
        try {
            playerRotationYawField?.setFloat(player, rotation.yaw)
            playerRotationPitchField?.setFloat(player, rotation.pitch)
        } catch (_: Exception) {}
    }

    private fun applyMotion(motion: Vec3) {
        val player = getPlayer() ?: return
        try {
            MappingContext.getField("forge:entity_motionX")?.setDouble(player, motion.x)
            MappingContext.getField("forge:entity_motionY")?.setDouble(player, motion.y)
            MappingContext.getField("forge:entity_motionZ")?.setDouble(player, motion.z)
        } catch (_: Exception) {}
    }

    fun onVelocityPacket(packetHandle: Any): PlatformCommand {
        val player = ForgeStateExtractor.extractPlayerState()
        val targetId = ForgeStateExtractor.getCurrentTargetId()
        val target = if (targetId != null) ForgeStateExtractor.extractTargetState(targetId) else null

        val rawX = MappingContext.getFieldValue(packetHandle, "forge:velocity_motionX")
        val rawY = MappingContext.getFieldValue(packetHandle, "forge:velocity_motionY")
        val rawZ = MappingContext.getFieldValue(packetHandle, "forge:velocity_motionZ")

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
            originalMotion = Vec3(motionX, motionY, motionZ),
            player = player,
            target = target,
            packetHandle = packetHandle
        )

        EventBridge.notifyVelocityPacket(ctx)
        return EventBridge.onVelocityPacket(ctx)
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
     * Javassist hook — i.e. the MC main thread). Combat modules set their desired
     * state on the 20Hz background thread via EventBridge.syntheticAttack /
     * EventBridge.syntheticUse; this method is the only place that writes the
     * real KeyBinding.pressed fields, so there is no cross-thread race and MC's
     * main-thread input loop actually sees the press.
     *
     * The OR with the physical mouse button is essential: it preserves the player's
     * own clicks (never releases a manually-held attack) while still driving the
     * synthetic press from the main thread.
     */
    fun applySyntheticInput() {
        try {
            val mc = getMc() ?: return
            val gs = MappingContext.getFieldValue(mc, "forge:mc_gameSettings") ?: return
            val keyBindAttack = MappingContext.getFieldValue(gs, "forge:gs_keyBindAttack") ?: return
            if (EventBridge.syntheticAttackOverride) {
                // Full clicker active: drive a smooth, time-based press/release cadence
                // (Raven-style). Each click = press (hold ~delay/2) then release (at delay),
                // regenerated per cycle at the requested CPS. This avoids the one-shot-pulse
                // stutter from the 20Hz strategy. Also writes LWJGL Mouse.buttons so keystrokes
                // HUDs that read physical mouse state see the click.
                val now = EventBridge.syntheticAttack
                val keyCode = (MappingContext.invokeMethod(keyBindAttack, "forge:keybinding_keyCode") as? Int) ?: 0
                if (now && keyCode != 0) {
                    driveClickCadence(keyCode, leftClick = true)
                } else {
                    releaseClick(keyCode, leftClick = true)
                    attackCadenceActive = false
                }
                // Preserve the physical button in the underlying mouse buffer so a manual
                // click still reads as down (this path also ORs the physical state).
                setMouseButtonPhysical(0, EventBridge.syntheticAttack || isMouseButtonDown(0))
            } else {
                // Assist modules (ClickAssist/BlockHit/AutoBlock): augment the player's
                // own input — OR with the physical button so their press is not stolen.
                keybindingPressedField?.setBoolean(keyBindAttack, EventBridge.syntheticAttack || isMouseButtonDown(0))
                // Reflect the effective left-button state in the mouse buffer so the
                // Keystrokes LMB key flashes (ClickAssist compensation).
                setMouseButtonPhysical(0, EventBridge.syntheticAttack || isMouseButtonDown(0))
            }
            // Forward/back keys (WTap/STap) — applied on the main thread so the tap lands
            // in MC's input and the Keystrokes W/S keys flash. When a tap module overrides,
            // drive the key fully from the synthetic state.
            val keyBindForward = MappingContext.getFieldValue(gs, "forge:gs_keyBindForward")
            if (keyBindForward != null) {
                keybindingPressedField?.setBoolean(
                    keyBindForward,
                    if (EventBridge.syntheticForwardOverride) EventBridge.syntheticForward
                    else EventBridge.syntheticForward || readKeyPressed("forge:gs_keyBindForward")
                )
            }
            val keyBindBack = MappingContext.getFieldValue(gs, "forge:gs_keyBindBack")
            if (keyBindBack != null) {
                keybindingPressedField?.setBoolean(
                    keyBindBack,
                    if (EventBridge.syntheticBackOverride) EventBridge.syntheticBack
                    else EventBridge.syntheticBack || readKeyPressed("forge:gs_keyBindBack")
                )
            }
            val keyBindUse = MappingContext.getFieldValue(gs, "forge:gs_keyBindUseItem") ?: return
            if (EventBridge.syntheticUseOverride) {
                val nowUse = EventBridge.syntheticUse
                val useKeyCode = (MappingContext.invokeMethod(keyBindUse, "forge:keybinding_keyCode") as? Int) ?: 0
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

    // ── Time-based click cadence (Raven-style) ──
    // Each full clicker cadence: press on downTime, release on upTime, regenerated per cycle.
    private var attackCadenceActive = false
    private var attackDownTime = 0L
    private var attackUpTime = 0L
    private var attackPressed = false
    private var useCadenceActive = false
    private var useDownTime = 0L
    private var useUpTime = 0L
    private var usePressed = false
    private val clickRandom = java.util.Random()

    private fun sampleCps(): Int {
        val lo = EventBridge.clickMinCps.coerceAtLeast(1)
        val hi = EventBridge.clickMaxCps.coerceAtLeast(lo)
        if (lo == hi) return lo
        return lo + clickRandom.nextInt(hi - lo + 1)
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
            startCadence(leftClick, now)
        }
    }

    private fun cadenceActive(leftClick: Boolean): Boolean =
        if (leftClick) attackCadenceActive else useCadenceActive

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
        try {
            MappingContext.invokeMethod(null, "forge:keybinding_setKeyBindState", keyCode, false)
        } catch (_: Exception) {}
        setMouseButtonPhysical(if (leftClick) 0 else 1, false)
        if (leftClick) EventBridge.mouseButton0 = false else EventBridge.mouseButton1 = false
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
