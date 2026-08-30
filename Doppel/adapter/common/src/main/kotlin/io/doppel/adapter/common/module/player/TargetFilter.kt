package io.doppel.adapter.common.module.player

import io.doppel.adapter.common.api.EventBridge
import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.option.boolean

/**
 * TargetFilter (Player category) — a global target-type filter for the combat modules.
 *
 * Options: Players / Mobs. When a type is turned OFF, the platform target selection drops that
 * entity type, so the combat modules that act on the target only see the allowed ones:
 * AimAssist, Velocity, JumpReset, AutoBlock, BlockHit, WTap/STap, SprintReset, SuperKnockback,
 * Reach (raycast), HitSelect, TriggerBot.
 *
 * The module is a passive switch: it mirrors its options into [EventBridge.targetFilterPlayers] /
 * [EventBridge.targetFilterMobs], which the platform (ForgeStateExtractor.isViableTarget +
 * the Reach raycast) reads when selecting targets. Disabled module / both on = everything allowed.
 */
object TargetFilter : Module("TargetFilter", Category.PLAYER) {

    private val players by boolean("Players", true)
    private val mobs by boolean("Mobs", true)

    /** Keep the shared flags in sync with the (live-editable) options. */
    private val syncListener: (PlayerState, TargetState?) -> Unit = { _, _ ->
        if (enabled) {
            EventBridge.targetFilterPlayers = players
            EventBridge.targetFilterMobs = mobs
        }
    }

    override fun onEnable() {
        EventBridge.targetFilterPlayers = players
        EventBridge.targetFilterMobs = mobs
        EventBridge.registerTickListener(syncListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(syncListener)
        // Disabled = no filter = both allowed.
        EventBridge.targetFilterPlayers = true
        EventBridge.targetFilterMobs = true
    }
}
