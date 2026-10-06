package io.doppel.adapter.common.module.combat

import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.core.strategy.click.WeaponType
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.HudLineProvider
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.option.int
import io.doppel.adapter.common.option.triggerOptions
import kotlin.random.Random

/**
 * Reach — dynamically extend attack range (Raven raycast model).
 *
 * On each qualifying click, cast a ray from the player's eyes along the look vector for a random
 * `min..max` distance, find the nearest entity AABB the segment intersects, and overwrite
 * `objectMouseOver` so the attack range genuinely extends AT attack time.
 *
 * Timing (this is the part that used to be wrong): the work happens on the MAIN THREAD once per
 * rendered frame via the platform's reach-frame advisor — the same point in the frame where the
 * game has just re-run its own ray trace and the next input pass has not run yet. Driving it from
 * the 20Hz background tick instead raced against that ray trace: the overwrite was usually
 * clobbered before the click was processed, so the hit landed with vanilla reach. See
 * [EventBridge.registerReachFrameAdvisor].
 *
 * Sampling model: the gates (chance / hitPer / delay / conditions) are sampled once per press and
 * the resulting distance is then held for the whole press, so every repeat click the game
 * generates while the button is down uses the extended trace.
 *
 * Conditions (Raven): weapon-only, moving-only, sprint-only, hit-through-blocks — plus a per-click
 * Chance. All gated through the core [io.doppel.core.strategy.reach.ReachRaycast] (pure algorithm)
 * and the platform `doReachRaycast` (mapping-driven raycast + objectMouseOver overwrite).
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
    /** Extend only every N presses (throttle). 1 = extend every click. */
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
    /** Previous frame's physical left-button state, for per-frame press-edge detection. */
    private var prevLeft = false
    /** Distance currently applied for this press, or 0.0 when no extension is active. */
    private var armedReach = 0.0
    /** Presses counted so far; the extension fires when this reaches hitPer. */
    private var clickCount = 0
    /** Timestamp of the last extension, for the Delay cooldown. */
    private var lastExtendNano = 0L

    /**
     * Player-dependent gates (weapon / moving / sprint), refreshed on the 20Hz tick where a
     * [PlayerState] exists, and read per frame by the advisor.
     */
    @Volatile private var conditionsOk = true

    // ========== Per-frame advisor (main thread) ==========
    private val frameAdvisor: () -> Double? = {
        if (!enabled) {
            prevLeft = false
            armedReach = 0.0
            null
        } else {
            val left = EventBridge.isLeftMousePhysicallyDown
            val edge = left && !prevLeft
            prevLeft = left
            if (!left) {
                // Release: the extension lasts exactly as long as the press does.
                armedReach = 0.0
            } else if (edge) {
                armedReach = sampleReach()
            }
            if (armedReach > 0.0) armedReach else null
        }
    }

    /** Apply the gates once per press; returns the distance to use, or 0.0 to skip this press. */
    private fun sampleReach(): Double {
        if (!conditionsOk) return 0.0
        // A screen is open: the player is not fighting, and clicks are handled by the GUI.
        if (EventBridge.isGuiOpen) return 0.0
        if (chance < 100 && Random.nextInt(100) >= chance) return 0.0

        // HitPer throttle: extend only every N presses.
        clickCount++
        if (clickCount < hitPer) return 0.0
        clickCount = 0

        // Delay cooldown: skip if we extended too recently.
        val now = System.nanoTime()
        if (delayMs > 0 && now - lastExtendNano < delayMs * 1_000_000L) return 0.0

        // Hit-through-blocks: skip extension when the crosshair currently hits a block.
        if (!hitThroughBlocks && EventBridge.isLookingAtBlock) return 0.0

        lastExtendNano = now
        return if (reachMax > reachMin) {
            (reachMin + Random.nextFloat() * (reachMax - reachMin)).toDouble()
        } else {
            reachMin.toDouble()
        }
    }

    // ========== Tick Listener (player-dependent gates only) ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        conditionsOk = enabled &&
            (!weaponOnly || p.weaponType != WeaponType.OTHER) &&
            (!movingOnly || p.isMoving) &&
            (!sprintOnly || p.isSprinting)
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        prevLeft = false
        armedReach = 0.0
        clickCount = 0
        lastExtendNano = 0L
        conditionsOk = true
        EventBridge.registerTickListener(tickListener)
        EventBridge.registerReachFrameAdvisor(frameAdvisor)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.registerReachFrameAdvisor(null)
        prevLeft = false
        armedReach = 0.0
        clickCount = 0
        conditionsOk = false
    }
}
