package io.doppel.adapter.common.module.combat

import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.ipc.Strategies
import io.doppel.contract.strategy.click.WeaponType
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.HudLineProvider
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.option.int
import io.doppel.adapter.common.option.triggerOptions

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
 * Chance. The ray/AABB test itself is `contract.util.Geometry.intersectBox` (pure algorithm);
 * the platform `doReachRaycast` supplies the mapping-driven entity list and overwrites
 * `objectMouseOver`.
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

    /**
     * Last tick's player snapshot.
     *
     * The advisor runs on the render thread, where no state object is handed in.
     * A tick-old snapshot is enough: the request only needs a well-formed frame,
     * and the core does not read the player for this op.
     */
    @Volatile private var latestPlayer: PlayerState? = null

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
        // Every gate, the throttle and the cooldown live in the core now; this
        // side supplies the inputs and decides nothing.
        val d = Strategies.reachSample(
            latestPlayer ?: return 0.0,
            null,
            System.nanoTime(),
            conditionsOk,
            EventBridge.isGuiOpen,
            chance,
            hitPer,
            delayMs,
            hitThroughBlocks,
            EventBridge.isLookingAtBlock,
            reachMin,
            reachMax
        ) ?: return 0.0

        // A missing answer leaves the press alone: extending reach without being
        // told to is the one thing this module must never do by accident.
        if (d > 0.0) markWorked() // the HUD row flashes on the press it actually extended
        return d
    }

    // ========== Tick Listener (player-dependent gates only) ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        latestPlayer = p
        conditionsOk = enabled &&
            (!weaponOnly || p.weaponType != WeaponType.OTHER) &&
            (!movingOnly || p.isMoving) &&
            (!sprintOnly || p.isSprinting)
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        prevLeft = false
        armedReach = 0.0
        conditionsOk = true
        Strategies.resetPlayer()
        EventBridge.registerTickListener(tickListener)
        EventBridge.registerReachFrameAdvisor(frameAdvisor)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.registerReachFrameAdvisor(null)
        prevLeft = false
        armedReach = 0.0
        conditionsOk = false
        latestPlayer = null
        Strategies.resetPlayer()
    }
}
