package io.switchlite.adapter.common.module.combat

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.HudLineProvider
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.option.int
import io.switchlite.adapter.common.option.triggerOptions
import kotlin.random.Random

/**
 * Reach — dynamically extend attack range (Raven raycast model).
 *
 * Mirrors Raven's `Reach`: on each qualifying click, cast a ray from the player's eyes along the look
 * vector for a random `min..max` distance, find the nearest entity AABB the segment intersects, and
 * overwrite `objectMouseOver` so the attack range genuinely extends. Unlike the old hurt-rising-edge
 * hack (which only fired after a hit already landed), this extends the reach AT attack time.
 *
 * Conditions (Raven): weapon-only, moving-only, sprint-only, hit-through-blocks — plus a per-click
 * Chance. All gated through the core [ReachRaycast] (pure algorithm) and platform `doReachRaycast`
 * (mapping-driven raycast + objectMouseOver overwrite in the adapter).
 */
object Reach : Module("Reach", Category.COMBAT), HudLineProvider {

    // ========== HUD value ==========
    override fun hudValue(): String = "$reachMin-$reachMax"
    override fun hudHighlight(): Boolean = true

    // ========== Reach Distance ==========
    private val reachMin by float("Min", 3.1f, 3.0f..6.0f, "blocks")
    private val reachMax by float("Max", 3.3f, 3.0f..6.0f, "blocks")

    // ========== Trigger ==========
    private val chance by int("Chance", 100, 0..100, "%")

    // ========== Raven Conditions ==========
    private val weaponOnly by boolean("WeaponOnly", false)
    private val movingOnly by boolean("MovingOnly", false)
    private val sprintOnly by boolean("SprintOnly", false)
    private val hitThroughBlocks by boolean("HitThroughBlocks", false)

    // ========== Unified conditions (kept for trigger-panel compat) ==========
    private val onlyPlane by boolean("OnlyPlane", true)
    private val onlyMove by boolean("OnlyMove", false)
    private val onlyMoveForward by boolean("OnlyMoveForward", false)
    private val onlyWhenTargetGoesBack by boolean("OnlyWhenTargetGoesBack", false)

    @Suppress("UNUSED_VARIABLE")
    private val triggerOptions by triggerOptions("Trigger") {
        onlyGround = onlyPlane
        onlyMove = this@Reach.onlyMove
        onlyMoveForward = this@Reach.onlyMoveForward
        onlyWhenTargetGoesBack = this@Reach.onlyWhenTargetGoesBack
    }

    // ========== Tick Listener ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p)
    }

    private fun onTick(player: PlayerState) {
        // Per-click chance roll (only when physically clicking — Raven triggers on MouseEvent).
        if (!EventBridge.isLeftMousePhysicallyDown) return
        if (chance < 100 && Random.nextInt(100) >= chance) return

        // Weapon / moving / sprint gates (Raven's call() pre-checks).
        if (weaponOnly && player.weaponType == io.switchlite.core.strategy.click.WeaponType.OTHER) return
        if (movingOnly && !player.isMoving) return
        if (sprintOnly && !player.isSprinting) return

        // Hit-through-blocks: skip extension when the crosshair currently hits a block.
        if (!hitThroughBlocks && EventBridge.isLookingAtBlock) return

        // Random reach in min..max, then run the platform raycast to overwrite objectMouseOver.
        val reach = if (reachMax > reachMin) {
            reachMin + Random.nextFloat() * (reachMax - reachMin)
        } else reachMin
        EventBridge.doReachRaycast(reach.toDouble())
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
    }
}
