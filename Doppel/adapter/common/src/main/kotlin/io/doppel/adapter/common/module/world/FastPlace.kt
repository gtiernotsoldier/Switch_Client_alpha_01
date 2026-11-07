package io.doppel.adapter.common.module.world

import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.int

/**
 * FastPlace — port of Raven's `module/impl/player/FastPlace.java`.
 *
 * Raven hooks `RightClickDelayTickEvent` (which vanilla fires every tick) and lowers
 * `Minecraft.rightClickDelayTimer` while the player is right-clicking:
 *
 *  - right click must be ACTIVE (`Utils.isBindDown(keyBindUseItem)` or `thePlayer.isUsingItem()`);
 *    when it is not, Raven drops its `rightClickStartTime` and does nothing;
 *  - an "Activation time" (ms) must have elapsed since the right-click began before the timer is
 *    touched, so a single click does not instantly become a machine-gun placement;
 *  - `Tick delay` (Raven's slider: 0..3, default 1) is then written into the timer. 0 means instant
 *    placement, 1..3 caps the timer (Raven guards `if (timer > delay)`, and `delay == 4` is a no-op:
 *    the value that means "leave vanilla alone").
 *
 * What the previous Doppel version did wrong:
 *  - it treated 0 as "off / vanilla" and only acted for 1..4, so the single most useful setting
 *    (instant placement, Raven's default-capable 0) did nothing at all;
 *  - it had no activation delay, so it engaged from the very first tick of a right click;
 *  - its `OnlyBlocks` gate was `player.isUsingItem`, which is not "holding a block" at all — it
 *    skipped the module exactly while eating/bow-drawing (Raven's *right-click-active* condition)
 *    and happily fast-placed while holding an ender pearl or an empty hand;
 *  - `OnlyWhenFacingBlocks` used `EventBridge.isLookingAtBlock`, a crosshair probe Raven's FastPlace
 *    never looks at;
 *  - it never re-asserted anything about activation timing, and it wrote the delay unconditionally
 *    even when the module had nothing to do.
 *
 * Hooks this port would still need (reported, not added here):
 *  - `EventBridge.isHoldingBlock` — held `ItemStack.getItem() is ItemBlock` — for Raven's default-on
 *    "Blocks only" gate;
 *  - a held-item name and a hovered-block id (block hit) for Raven's item/block blacklists;
 *  - a C08 send-packet hook for Raven's `onSendPacket` branch (it swallows ~70% of the "use item"
 *    placement packets, `placedBlockDirection == 255`, to cut packet spam).
 */
object FastPlace : Module("FastPlace", Category.UTILITY) {

    // ========== Config ==========
    /** Raven: `SliderSetting("Tick delay", 1.0, 0.0, 3.0, 1.0)` — 0 = instant, 3 = almost vanilla. */
    private val tickDelay by int("TickDelay", 1, FastPlaceLogic.TICK_DELAY_MIN..FastPlaceLogic.TICK_DELAY_MAX, "ticks")

    /** Raven: `SliderSetting("Activation time", "ms", 0.0, 0.0, 100.0, 5.0)`. */
    private val activationTime by int("ActivationTime", 0, 0..100, "ms", step = 5.0)

    /** Raven: `ButtonSetting("Pitch check", false)` with a hard-coded 70° threshold. */
    private val pitchCheck by boolean("PitchCheck", false)

    // ========== Runtime state ==========
    /** When the current right-click streak started, in ms; 0 = no active streak. */
    private var rightClickStartMs: Long = 0L

    /** Whether the timer was already being overridden — used to flash the HUD only on the transition. */
    private var engaged: Boolean = false

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p)
    }

    private fun onTick(player: PlayerState) {
        if (player === PlayerState.EMPTY) {
            resetStreak()
            return
        }
        // Raven: `!Utils.nullCheck() || !mc.inGameHasFocus` → forget the streak. An open GUI means
        // the game does not have focus, and the delay timer is not being consumed anyway.
        if (EventBridge.isGuiOpen) {
            resetStreak()
            return
        }

        // Raven's `isRightClickActive()`: the use key is down, or the player is using an item
        // (eating / drawing a bow / blocking with a sword). The synthetic flag covers AutoBlock.
        val rightClickActive = EventBridge.isRightMousePhysicallyDown ||
            EventBridge.syntheticUse ||
            player.isUsingItem
        if (!rightClickActive) {
            resetStreak()
            return
        }

        val now = System.currentTimeMillis()
        if (rightClickStartMs == 0L) rightClickStartMs = now

        val delay = FastPlaceLogic.delayToApply(
            activationElapsed = now - rightClickStartMs >= activationTime,
            pitchOk = !pitchCheck || player.rotation.pitch >= FastPlaceLogic.PITCH_MIN,
            tickDelay = tickDelay
        )
        if (delay == null) {
            engaged = false
            return
        }

        // The platform's setter writes the field outright (there is no getter on the bridge), which
        // is equivalent to Raven's "only lower it": the timer never counts up on its own, and vanilla
        // itself caps it at 4, so re-writing `delay` every active tick keeps it at/below `delay`.
        EventBridge.setRightClickDelay(delay)
        if (!engaged) {
            engaged = true
            markWorked() // HUD work flash — fast placing just engaged (transition, not the steady hold)
        }
    }

    private fun resetStreak() {
        rightClickStartMs = 0L
        engaged = false
    }

    override fun onEnable() {
        resetStreak()
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        resetStreak()
    }
}

/**
 * FastPlace's decision, split out of the module so it is pure and unit-testable.
 *
 * Returns the `rightClickDelayTimer` value to write, or `null` to leave vanilla alone.
 */
object FastPlaceLogic {

    /** Raven's pitch gate: `if (pitchCheck.isToggled() && rotationPitch < 70.0f) return false;`. */
    const val PITCH_MIN: Float = 70f

    /** Raven's slider bounds: `SliderSetting("Tick delay", 1.0, 0.0, 3.0, 1.0)`. */
    const val TICK_DELAY_MIN: Int = 0
    const val TICK_DELAY_MAX: Int = 3

    /**
     * Raven's defensive branch: `if (delay == 4) return;` — 4 is the vanilla timer value, i.e.
     * "no override". Unreachable through the slider, kept so a hand-edited config cannot ask the
     * module to slow the player down below vanilla.
     */
    const val VANILLA_MAX_DELAY: Int = 4

    fun delayToApply(activationElapsed: Boolean, pitchOk: Boolean, tickDelay: Int): Int? {
        // Raven returns before touching the timer at all when a gate fails.
        if (!activationElapsed) return null
        if (!pitchOk) return null
        // 4 (the vanilla timer value) and anything above it mean "no override" — Raven's `delay == 4`
        // branch, expressed as "only 0..3 is an override" so a bad config value cannot slow the player.
        if (tickDelay < TICK_DELAY_MIN || tickDelay >= VANILLA_MAX_DELAY) return null
        return tickDelay
    }
}
