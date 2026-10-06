package io.doppel.adapter.common.module.combat

import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.core.strategy.click.WeaponType
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.int
import kotlin.random.Random

/**
 * ClickAssist — emit one or more EXTRA real mouse clicks per physical click ("boost your CPS").
 *
 * Rewritten against Raven (b+ / bs / XD are ~95% the same module): the adapter produces the extra
 * clicks with `java.awt.Robot` (release + press with the legacy masks 16/4), so they are real OS
 * events that re-enter the client through LWJGL and are processed exactly like the player's own
 * click — in the world, inside GUIs, and while mining.
 *
 * Why the previous implementation misbehaved: it never touched a mouse. It latched
 * `EventBridge.syntheticAttack` / `syntheticUse` from a 20Hz tick listener and never cleared them,
 * while AutoClicker and HitSelect write those same flags — so an assist click either never became
 * a click, or pinned the attack key down until something else happened to clear the flag. This
 * module is now a pure decision function: the adapter asks "how many extra clicks for this button
 * edge?" and the module answers. No shared state, no ticks, no flags.
 *
 * Guard chain follows b+ (`ClickAssist.java:65-119`), with our stricter consumable test:
 *   GUI open → nothing; eating/blocking → nothing; per-button toggles; weapons-only (sword/axe);
 *   only-while-targeting (an entity on the crosshair); right-click only on blocks; above-5-CPS
 *   (our own CPS estimate, fed by these very edges — XD's equivalent is dead code); chance roll.
 */
object ClickAssist : Module("ClickAssist", Category.COMBAT) {

    // ========== Core ==========
    /** Extra clicks per assist: Normal = 1 (Raven's behaviour), Double = 2, Random = 0..2. */
    private val mode by choices("Mode", arrayOf("Normal", "Double", "Random"))

    /** Chance an accepted click is actually boosted (b+ uses a single shared slider). */
    private val probability by int("Chance", 80, 0..100, "%")

    // ========== Filters ==========
    private val leftClickEnabled by boolean("LeftClick", true)
    private val rightClickEnabled by boolean("RightClick", false)
    private val weaponsOnly by boolean("WeaponsOnly", true)
    private val onlyAimingEntity by boolean("OnlyAimingEntity", false)
    private val onlyBlocks by boolean("OnlyBlocks", true)
    private val above5Cps by boolean("Above5CPS", false)

    // ========== State ==========
    /** Latest player snapshot, refreshed on the 20Hz tick; the advisor itself is stateless. */
    @Volatile private var latestPlayer: PlayerState? = null

    /** Timestamps of physical click edges, for the "above 5 CPS" gate. */
    private val recentClicks = ArrayDeque<Long>(16)

    // ========== Advisor (called by the adapter on every physical press edge, main thread) ==========
    private val advisor: (Int) -> Int = { button ->
        if (!enabled) 0 else evaluate(button)
    }

    private fun evaluate(button: Int): Int {
        val now = System.nanoTime()
        recentClicks.addLast(now)
        while (recentClicks.isNotEmpty() && recentClicks.first() < now - 500_000_000L) {
            recentClicks.removeFirst()
        }

        // A screen is open, or the player is consuming/blocking an item: never assist.
        if (EventBridge.isGuiOpen) return 0
        val player = latestPlayer ?: return 0
        if (player.isUsingItem || player.isBlocking) return 0

        if (button == 0) {
            if (!leftClickEnabled) return 0
            if (weaponsOnly && player.weaponType != WeaponType.SWORD && player.weaponType != WeaponType.AXE) return 0
            if (onlyAimingEntity && EventBridge.crosshairTarget == null) return 0
        } else {
            if (!rightClickEnabled) return 0
            if (onlyBlocks && !EventBridge.isLookingAtBlock) return 0
        }

        if (above5Cps && estimateCps(now) < 5f) return 0
        if (probability < 100 && Random.nextInt(100) >= probability) return 0

        val extra = when (mode) {
            "Double" -> 2
            "Random" -> Random.nextInt(3)
            else -> 1
        }
        // Only a decision that actually clicks counts as work; the early returns above are all
        // "did nothing" and must not flash the row.
        if (extra > 0) markWorked()
        return extra
    }

    /** Manual CPS from the last 500ms of physical click edges. */
    private fun estimateCps(nowNs: Long): Float {
        if (recentClicks.size < 2) return 0f
        val windowNs = nowNs - recentClicks.first()
        if (windowNs <= 0) return 0f
        return (recentClicks.size - 1).toFloat() / (windowNs / 1_000_000_000f)
    }

    // ========== Tick listener (player snapshot only) ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        latestPlayer = p
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        latestPlayer = null
        recentClicks.clear()
        EventBridge.registerClickAssistAdvisor(advisor)
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.registerClickAssistAdvisor(null)
        EventBridge.unregisterTickListener(tickListener)
        latestPlayer = null
        recentClicks.clear()
    }
}
