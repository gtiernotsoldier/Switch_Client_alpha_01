package io.doppel.adapter.common.module.combat

import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.contract.strategy.click.WeaponType
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.int
import io.doppel.ipc.ClickAssistFlag
import io.doppel.ipc.Strategies

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
 * The adapter still asks "how many extra clicks for this button edge?", but the answer now comes
 * from the core over IPC: the guard chain, the 500 ms edge history and both rolls live there. This
 * side packs the predicates into a bit field and emits whatever the core allows — no local state,
 * no local RNG.
 *
 * Guard chain follows b+ (`ClickAssist.java:65-119`), with our stricter consumable test:
 *   GUI open → nothing; eating/blocking → nothing; per-button toggles; weapons-only (sword/axe);
 *   only-while-targeting (an entity on the crosshair); right-click only on blocks; above-5-CPS
 *   (the core's own CPS estimate, fed by these very edges — XD's equivalent is dead code); chance
 *   roll. The order matters: gates that reject must not spend a roll.
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
    /**
     * Latest player snapshot, refreshed on the 20Hz tick.
     *
     * The advisor fires on the render thread, where no state object is handed
     * in; a tick-old snapshot is enough to marshal the request. The core does
     * not read the player for this op — the predicates travel as bits.
     */
    @Volatile private var latestPlayer: PlayerState? = null

    // ========== Advisor (called by the adapter on every physical press edge, main thread) ==========
    private val advisor: (Int) -> Int = { button ->
        if (!enabled) 0 else evaluate(button)
    }

    private fun evaluate(button: Int): Int {
        // No snapshot yet (the first tick has not landed): adding a click
        // without the core's approval is the one thing this module must never
        // do, so the conservative answer is zero.
        val player = latestPlayer ?: return 0

        var f = 0
        if (button != 0) f = f or ClickAssistFlag.RIGHT_BUTTON
        if (EventBridge.isGuiOpen) f = f or ClickAssistFlag.GUI_OPEN
        if (player.isUsingItem) f = f or ClickAssistFlag.USING_ITEM
        if (player.isBlocking) f = f or ClickAssistFlag.BLOCKING
        if (player.weaponType == WeaponType.SWORD || player.weaponType == WeaponType.AXE) {
            f = f or ClickAssistFlag.IS_WEAPON
        }
        if (EventBridge.crosshairTarget != null) f = f or ClickAssistFlag.HAS_TARGET
        if (EventBridge.isLookingAtBlock) f = f or ClickAssistFlag.LOOKING_AT_BLOCK
        if (leftClickEnabled) f = f or ClickAssistFlag.LEFT_ENABLED
        if (rightClickEnabled) f = f or ClickAssistFlag.RIGHT_ENABLED
        if (weaponsOnly) f = f or ClickAssistFlag.WEAPONS_ONLY
        if (onlyAimingEntity) f = f or ClickAssistFlag.ONLY_AIMING_ENTITY
        if (onlyBlocks) f = f or ClickAssistFlag.ONLY_BLOCKS
        if (above5Cps) f = f or ClickAssistFlag.ABOVE_5_CPS

        // The guard chain, the 500 ms edge history and both rolls live in the
        // core now; this side supplies the inputs and decides nothing.
        val extra = Strategies.clickAssist(
            player, null, System.nanoTime(), f, probability, modeCode()
        ) ?: return 0

        // Only a decision that actually clicks counts as work; a rejected press
        // must not flash the row.
        if (extra > 0) markWorked()
        return extra
    }

    /** Wire value for the configured mode; matches `strategy::click_assist::Mode`. */
    private fun modeCode(): Int = when (mode) {
        "Double" -> 1
        "Random" -> 2
        else -> 0
    }

    // ========== Tick listener (player snapshot only) ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        latestPlayer = p
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        latestPlayer = null
        // A press history the core still remembers would rate the next click
        // against the previous activation's rhythm.
        Strategies.resetPlayer()
        EventBridge.registerClickAssistAdvisor(advisor)
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.registerClickAssistAdvisor(null)
        EventBridge.unregisterTickListener(tickListener)
        Strategies.resetPlayer()
        latestPlayer = null
    }
}
