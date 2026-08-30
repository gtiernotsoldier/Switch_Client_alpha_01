package io.doppel.adapter.common.module.player

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.int

/**
 * AntiBot — detect and filter bot entities to prevent mis-targeting.
 *
 * Three detection layers, any single method returning true → bot flagged:
 * 1. NamePattern: blank, all-numeric, or random-suffix names.
 * 2. GroundState: always on ground (bots never jump) or never on ground.
 * 3. TicksExisted: entities younger than the configured threshold (spawn-age).
 *
 * Called by combat modules (AimAssist, KillAura) via AntiBot.isBot(name).
 */
object AntiBot : Module("AntiBot", Category.PLAYER) {

    private val namePattern by boolean("NamePattern", true)
    private val groundState by boolean("GroundState", false)
    private val ticksExisted by boolean("TicksExisted", false)
    private val minTicks by int("MinTicks", 80, 10..200, "ticks")

    // Known bot name patterns
    private val botPatterns = listOf(
        Regex("^\\d+$"),                           // all-numeric: "12345"
        Regex("^[A-Za-z]{3,5}_[A-Za-z0-9]{3,8}$"), // prefix_suffix: "NPC_8a3f"
        Regex("_\\d{3,}$"),                         // trailing digits: "Steve_001"
        Regex("^[A-Z][a-z]+[A-Z]{2,}[a-z]+$"),      // camelGarbage: "ZombiMKPlayer"
    )

    /**
     * Check whether [targetName] is likely a bot.
     */
    fun isBot(targetName: String): Boolean {
        if (!enabled) return false
        if (targetName.isEmpty()) return true

        // 1. Name pattern
        if (namePattern && botPatterns.any { it.containsMatchIn(targetName) }) return true

        // 2. Ground state anomaly
        if (groundState) {
            val onGround = EventBridge.isEntityOnGround(targetName)
            // Bots often never leave the ground
            if (!onGround) return true
        }

        // 3. Ticks existed
        if (ticksExisted) {
            val ticks = EventBridge.getEntityTicksExisted(targetName)
            if (ticks in 1..<minTicks) return true
        }

        return false
    }

    override fun onEnable() {}
    override fun onDisable() {}
}
