package io.switchlite.adapter.forge.v1_8_9

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.api.IEventBridge
import io.switchlite.adapter.common.render.HitBoxCategory
import io.switchlite.adapter.common.render.HitBoxEntity
import io.switchlite.adapter.common.render.HitBoxFrame
import io.switchlite.core.model.*
import io.switchlite.core.strategy.reach.ReachRaycast
import io.switchlite.core.util.Vec2
import io.switchlite.core.util.Vec3
import io.switchlite.agent.MappingContext

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
    private val entityLivingBaseClass by lazy { Class.forName("net.minecraft.entity.EntityLivingBase") }
    private val entityPlayerClass by lazy { Class.forName("net.minecraft.entity.player.EntityPlayer") }
    private val entityItemClass by lazy { Class.forName("net.minecraft.entity.item.EntityItem") }
    private val itemArmorClass by lazy { Class.forName("net.minecraft.item.ItemArmor") }
    private val armorMaterialClass by lazy { Class.forName("net.minecraft.item.ItemArmor\$ArmorMaterial") }
    private val clothMaterial by lazy { armorMaterialClass.enumConstants.firstOrNull { it.toString() == "CLOTH" } }

    // — packet classes
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
    private val blocksAir by lazy { blocksClass.getField("AIR").get(null) }
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
    private val mcLeftClickCounterField by lazy { MappingContext.getField("forge:mc_leftClickCounter") }
    private val mcRightClickDelayField by lazy { MappingContext.getField("forge:mc_rightClickDelayTimer") }
    private val entityRendererFovField by lazy { MappingContext.getField("forge:entityRenderer_fovModifierHand") }
    private val gsGammaField by lazy { MappingContext.getField("forge:gs_gammaSetting") }
    private val inventoryCurrentItemField by lazy { MappingContext.getField("forge:inventory_currentItem") }
    private val playerJumpTicksField by lazy { MappingContext.getField("forge:player_jumpTicks") }
    private val mcObjectMouseOverField by lazy { MappingContext.getField("forge:mc_objectMouseOver") }

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
    private var useCadenceActive = false
    private var useDownTime = 0L
    private var useUpTime = 0L
    private var usePressed = false
    private val clickRandom = java.util.Random()

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

    /** Read the current pressed state of a game-settings key binding. */
    private fun readKeyPressed(gsKey: String): Boolean {
        return try {
            val mc = getMc() ?: return false
            val gs = MappingContext.getFieldValue(mc, "forge:mc_gameSettings") ?: return false
            val keyBind = MappingContext.getFieldValue(gs, gsKey) ?: return false
            keybindingPressedField?.getBoolean(keyBind) ?: false
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
            EventBridge.isKeyJumpDown = readKeyPressed("forge:gs_keyBindJump") || EventBridge.isJumpPulseActive()
            EventBridge.isKeyForwardDown = readKeyPressed("forge:gs_keyBindForward")
            EventBridge.isKeyBackDown = readKeyPressed("forge:gs_keyBindBack")
            EventBridge.isKeyLeftDown = readKeyPressed("forge:gs_keyBindLeft")
            EventBridge.isKeyRightDown = readKeyPressed("forge:gs_keyBindRight")
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
                "STOP_SPRINTING" -> EventBridge.serverSprintState = false
                "START_SPRINTING" -> EventBridge.serverSprintState = true
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
            // Keep the server-side sprint mirror in sync with local sprint changes.
            EventBridge.serverSprintState = sprinting
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

        // ── World ──
        EventBridge.registerRightClickDelayHandler { ticks ->
            try {
                val mc = getMc() ?: return@registerRightClickDelayHandler
                mcRightClickDelayField?.setInt(mc, ticks)
            } catch (_: Exception) {}
        }

        // ── Player helpers ──
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
                    val collidable = try { MappingContext.invokeMethod(entity, "forge:entity_canBeCollidedWith") as? Boolean ?: false } catch (_: Exception) { false }
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

        // ── HitBox frame (Render — collected inside the renderWorldPass world hook) ──
        EventBridge.registerHitBoxFrameProvider {
            try {
                val mc = getMc() ?: return@registerHitBoxFrameProvider null
                val world = getWorld() ?: return@registerHitBoxFrameProvider null
                val player = getPlayer() ?: return@registerHitBoxFrameProvider null
                val timer = MappingContext.getFieldValue(mc, "forge:mc_timer")
                val partialTicks = (MappingContext.getFieldValue(timer, "forge:timer_renderPartialTicks") as? Float) ?: 1.0f
                val playerId = MappingContext.getFieldValue(player, "forge:entity_entityId") as? Int ?: -1
                val viewer = getRenderViewEntity(mc, player)
                val vx = MappingContext.getFieldValue(viewer, "forge:entity_posX") as? Double ?: 0.0
                val vy = MappingContext.getFieldValue(viewer, "forge:entity_posY") as? Double ?: 0.0
                val vz = MappingContext.getFieldValue(viewer, "forge:entity_posZ") as? Double ?: 0.0

                val loaded = MappingContext.getFieldValue(world, "forge:world_loadedEntityList") as? List<*> ?: return@registerHitBoxFrameProvider null
                val entities = mutableListOf<HitBoxEntity>()
                for (item in loaded) {
                    val entity = item ?: continue
                    val category = classifyHitBox(entity, playerId) ?: continue
                    val isDead = MappingContext.getFieldValue(entity, "forge:entity_isDead") as? Boolean ?: false
                    if (isDead) continue
                    val id = MappingContext.getFieldValue(entity, "forge:entity_entityId") as? Int ?: continue
                    val px = MappingContext.getFieldValue(entity, "forge:entity_posX") as? Double ?: continue
                    val py = MappingContext.getFieldValue(entity, "forge:entity_posY") as? Double ?: continue
                    val pz = MappingContext.getFieldValue(entity, "forge:entity_posZ") as? Double ?: continue
                    val ppx = MappingContext.getFieldValue(entity, "forge:entity_prevPosX") as? Double ?: px
                    val ppy = MappingContext.getFieldValue(entity, "forge:entity_prevPosY") as? Double ?: py
                    val ppz = MappingContext.getFieldValue(entity, "forge:entity_prevPosZ") as? Double ?: pz
                    // Interpolate the feet position (partial-tick smoothness like vanilla rendering).
                    val rx = ppx + (px - ppx) * partialTicks
                    val ry = ppy + (py - ppy) * partialTicks
                    val rz = ppz + (pz - ppz) * partialTicks
                    val bb = MappingContext.getFieldValue(entity, "forge:entity_getEntityBoundingBox") ?: continue
                    val minX = MappingContext.getFieldValue(bb, "forge:bb_minX") as? Double ?: continue
                    val minY = MappingContext.getFieldValue(bb, "forge:bb_minY") as? Double ?: continue
                    val minZ = MappingContext.getFieldValue(bb, "forge:bb_minZ") as? Double ?: continue
                    val maxX = MappingContext.getFieldValue(bb, "forge:bb_maxX") as? Double ?: continue
                    val maxY = MappingContext.getFieldValue(bb, "forge:bb_maxY") as? Double ?: continue
                    val maxZ = MappingContext.getFieldValue(bb, "forge:bb_maxZ") as? Double ?: continue
                    // Shift the real box by the interpolation delta so it tracks the rendered model.
                    val dx = rx - px
                    val dy = ry - py
                    val dz = rz - pz
                    entities.add(HitBoxEntity(
                        entityId = id,
                        category = category,
                        className = entity.javaClass.simpleName,
                        renderPosX = rx, renderPosY = ry, renderPosZ = rz,
                        boxMinX = minX + dx, boxMinY = minY + dy, boxMinZ = minZ + dz,
                        boxMaxX = maxX + dx, boxMaxY = maxY + dy, boxMaxZ = maxZ + dz
                    ))
                }
                HitBoxFrame(vx, vy, vz, entities)
            } catch (_: Exception) { null }
        }
    }

    /** Classify an entity for the HitBox overlay; null = not drawn (projectiles, boats, ...). */
    private fun classifyHitBox(entity: Any, playerId: Int): HitBoxCategory? {
        val id = try { MappingContext.getFieldValue(entity, "forge:entity_entityId") as? Int } catch (_: Exception) { null } ?: return null
        if (id == playerId) return HitBoxCategory.OWN
        return when {
            entityPlayerClass.isInstance(entity) -> HitBoxCategory.PLAYER
            entityItemClass.isInstance(entity) -> HitBoxCategory.ITEM
            entityLivingBaseClass.isInstance(entity) -> HitBoxCategory.MOB
            else -> null
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

    // ========== Click Cadence Engine ==========

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
