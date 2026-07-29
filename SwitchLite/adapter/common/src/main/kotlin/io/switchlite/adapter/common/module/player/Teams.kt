package io.switchlite.adapter.common.module.player

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.boolean

/**
 * Teams — multi-method teammate detection for friendly-fire prevention.
 *
 * Four independent checks, priortiy order:
 * 1. ScoreboardTeam — Minecraft scoreboard team system.
 * 2. GommeSW — GommeHD SkyWars "T[number]" name prefix matching.
 * 3. NameColor — first colour code in formatted display name.
 * 4. ArmorColor — leather armour dye colour matching.
 *
 * Any single method returning true → teammate confirmed.
 * Used by combat modules (KillAura, HitSelect) via isInYourTeam().
 */
object Teams : Module("Teams", Category.PLAYER) {

    private val scoreboardTeam by boolean("ScoreboardTeam", true)
    private val nameColor by boolean("NameColor", true)
    private val armorColor by boolean("ArmorColor", true)
    private val gommeSW by boolean("GommeSW", false)

    /**
     * Check whether [targetName] is on the same team as the local player.
     * Callers: KillAura, HitSelect, TriggerBot, Reach, etc.
     */
    fun isInYourTeam(targetName: String): Boolean {
        if (!enabled) return false

        // 1. Scoreboard team
        if (scoreboardTeam) {
            val team = EventBridge.getScoreboardTeam(targetName) ?: ""
            val myTeam = EventBridge.getScoreboardTeam("") ?: ""
            if (team.isNotEmpty() && team == myTeam) return true
        }

        val displayName = EventBridge.getDisplayName(targetName)

        // 2. GommeSW: name starts with "T" + digit, digit must match
        if (gommeSW) {
            val gommeRegex = Regex("^T(\\d+)")
            val myName = EventBridge.getDisplayName("")
            val myMatch = gommeRegex.find(myName)
            val theirMatch = gommeRegex.find(displayName)
            if (myMatch != null && theirMatch != null && myMatch.groupValues[1] == theirMatch.groupValues[1])
                return true
        }

        // 3. Name colour: extract first §x colour code
        if (nameColor) {
            val colorRegex = Regex("§[0-9a-f]")
            val myColor = colorRegex.find(EventBridge.getDisplayName(""))?.value
            val theirColor = colorRegex.find(displayName)?.value
            if (myColor != null && myColor == theirColor) return true
        }

        // 4. Armor colour
        if (armorColor) {
            val myColor = EventBridge.getArmorDyeColor("")
            val theirColor = EventBridge.getArmorDyeColor(targetName)
            if (myColor >= 0 && myColor == theirColor) return true
        }

        return false
    }

    // Teams is a passive utility — no tick listener needed.
    override fun onEnable() {}
    override fun onDisable() {}
}
