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

    // ========== Trigger (click-based, not hit-based) ==========
    private val chance by int("Chance", 100, 0..100, "%")
    /** Extend only every N clicks (throttle). 1 = extend every click. */
    private val hitPer by int("HitPer", 1, 1..10)
    /** Cooldown: skip extension for N ms after the last extension (keeps it bursty / natural). */
    private val delayMs by int("Delay", 0, 0..500, "ms")

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

    // ========== State ==========
    /** Previous frame's left-mouse state, for click-edge counting. */
    private var prevLeft = false
    /** Clicks counted so far; extension fires when this reaches hitPer. */
    private var clickCount = 0
    /** Timestamp of the last extension, for the Delay cooldown. */
    private var lastExtendNano = 0L

    // ========== Tick Listener ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p)
    }

    private fun onTick(player: PlayerState) {
        val left = EventBridge.isLeftMousePhysicallyDown
        val clickEdge = left && !prevLeft
        prevLeft = left

        // Only process on a fresh click (Raven triggers Reach on MouseEvent / click).
        if (!clickEdge) return
        if (chance < 100 && Random.nextInt(100) >= chance) return

        // HitPer throttle: extend only every N clicks.
        clickCount++
        if (clickCount < hitPer) return
        clickCount = 0

        // Delay cooldown: skip if we extended too recently.
        val now = System.nanoTime()
        if (delayMs > 0 && now - lastExtendNano < delayMs * 1_000_000L) return

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
        lastExtendNano = now
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        prevLeft = false
        clickCount = 0
        lastExtendNano = 0L
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        prevLeft = false
        clickCount = 0
    }
}
