package io.doppel.adapter.common.module.combat

import io.doppel.contract.model.PlayerState
import io.doppel.ipc.Strategies
import io.doppel.contract.model.TargetState
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.option.int
import io.doppel.contract.strategy.click.WeaponType

/**
 * KnockbackDisplace Module (Slinky Knockback Displace, 1.8 exclusive).
 *
 * Displaces the DIRECTION of the knockback YOU DEAL when you attack — not your own received
 * knockback (that's Velocity's job). MC 1.8's server computes the knockback direction from the
 * ATTACKER's yaw, so on each attack we briefly offset the player's yaw (a "flick") before the
 * server sees it; the knockback then lands at the rotated angle. Distance unchanged.
 *
 * "Hide rotation from players" semantics: the flick lasts exactly one tick and is applied on the
 * main thread, so other players don't perceive a visible spin.
 *
 * Architecture:
 * 1. Logic in module: direction (left/right/random/strafe), cooldown, angle, conditions.
 * 2. On a fresh hit (EventBridge.notifyAttack) the module queues a one-tick yaw offset; the main
 *    thread applies it via EventBridge.setPlayerRotation for exactly one tick, then restores.
 */
object KnockbackDisplace : Module("KnockbackDisplace", Category.COMBAT) {

    // ========== Configuration ==========
    private val direction by choices("Direction", arrayOf("Left", "Right", "Random", "Strafe", "StrafeInverted"))
    private val useLastStrafe by boolean("UseLastStrafe", true)
    private val cooldownMs by int("Cooldown", 100, 0..2000, "ms")
    private val angle by float("Angle", 90.0f, -180.0f..180.0f, "degrees")

    // ========== Conditions ==========
    private val notTakingKnockback by boolean("NotTakingKnockback", false)
    private val weaponOnly by boolean("WeaponOnly", false)

    // ========== State ==========
    /**
     * The offset the core handed back for this attack.
     *
     * The core says *what* the flick is; the timing — one tick, then restore —
     * is a platform concern and stays here. Non-zero while a flick is queued.
     */
    private var flickRemainingTicks = 0
    private var flickYawOffset = 0f

    /**
     * Last tick's player snapshot.
     *
     * The attack listener fires on the attack itself, where no state object is
     * passed in; the snapshot only has to be recent enough to marshal a request,
     * and a tick-old one is (the core does not read the player for this op).
     */
    @Volatile
    private var latestPlayer: PlayerState? = null

    // ========== Listeners ==========
    // Fresh hit (attack landed) → queue the flick.
    private val attackListener: (TargetState?) -> Unit = {
        if (enabled) onAttack()
    }
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p)
    }

    private fun onAttack() {
        // Direction, cooldown and the strafe memory all live in the core now, so
        // one call replaces what used to be three checks and a random pick.
        val offset = Strategies.kbdAttack(
            latestPlayer ?: return,
            null,
            System.nanoTime(),
            directionCode(),
            useLastStrafe,
            cooldownMs,
            angle,
            notTakingKnockback,
            EventBridge.velocityPacketReceivedThisTick,
            EventBridge.isKeyLeftDown,
            EventBridge.isKeyRightDown
        ) ?: return

        if (offset == 0f) return
        // Queue a one-tick yaw offset (the server sees the attacker's yaw at attack time).
        flickYawOffset = offset
        flickRemainingTicks = 1
        markWorked() // the HUD row flashes on the flick that was queued
    }

    /** Wire value for the configured direction. */
    private fun directionCode(): Int = when (direction) {
        "Left" -> 0
        "Right" -> 1
        "Random" -> 2
        "Strafe" -> 3
        "StrafeInverted" -> 4
        else -> 0
    }

    private fun onTick(player: PlayerState) {
        latestPlayer = player
        if (flickRemainingTicks <= 0) return
        // Weapon condition (checked with the current held item).
        if (weaponOnly && player.weaponType == io.doppel.contract.strategy.click.WeaponType.OTHER) {
            flickRemainingTicks = 0
            flickYawOffset = 0f
            return
        }
        // Apply the yaw offset for exactly one tick, then restore.
        val yaw = player.rotation.yaw
        EventBridge.setPlayerRotation(yaw + flickYawOffset, player.rotation.pitch)
        flickRemainingTicks--
        if (flickRemainingTicks <= 0) flickYawOffset = 0f
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        Strategies.resetPlayer()
        flickRemainingTicks = 0
        EventBridge.registerAttackListener(attackListener)
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterAttackListener(attackListener)
        EventBridge.unregisterTickListener(tickListener)
        Strategies.resetPlayer()
        flickRemainingTicks = 0
        flickYawOffset = 0f
    }
}
