package io.switchlite.adapter.forge.v1_8_9.combat

import io.switchlite.core.SwitchCore
import io.switchlite.core.safety.SafetyWrapper
import io.switchlite.adapter.v1_8_9.util.StateExtractor
import io.switchlite.core.option.AimConfig
import io.switchlite.core.util.Vec2
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.client.event.RenderWorldLastEvent

object AimModule {
    private var cachedDecision: ((Vec2, io.switchlite.core.combat.model.PlayerState, io.switchlite.core.combat.model.TargetState?) -> Vec2)? = null
    private var lastConfigHash: Int = 0
    private val config: AimConfig = AimConfig() // Would load from ConfigManager in production

    @SubscribeEvent
    fun onClientTick(event: net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent) {
        val mc = net.minecraft.client.Minecraft.getMinecraft()
        if (mc.thePlayer == null || mc.theWorld == null) return
        if (mc.currentScreen != null) return // Don't aim in menus

        // 1. State extraction
        val playerState = StateExtractor.extractPlayerState(mc.thePlayer)
        val target = getCurrentTarget(mc)
        val targetState = target?.let { StateExtractor.extractTargetState(it, playerState.position) }
        val currentRotation = StateExtractor.extractRotation(mc.thePlayer)

        // 2. Configuration fingerprint check (performance optimization)
        val currentHash = config.hashCode()
        if (currentHash != lastConfigHash) {
            cachedDecision = { curr, player, tgt ->
                SwitchCore.aimStrategy.calculateAimRotation(curr, player, tgt, config)
            }
            lastConfigHash = currentHash
        }

        // 3. Safe execution of core strategy
        val newRotation = SafetyWrapper.execute(
            moduleId = "AimAssist",
            fallback = currentRotation,
            block = { cachedDecision!!(currentRotation, playerState, targetState) }
        )

        // 4. Write back to game (if rotation changed)
        if (newRotation != currentRotation) {
            StateExtractor.applyRotation(mc.thePlayer, newRotation)
        }
    }

    private fun getCurrentTarget(mc: net.minecraft.client.Minecraft): Any? {
        // Simplified target acquisition - would use raycasting in production
        return mc.objectMouseOver?.entityHit
    }
}
