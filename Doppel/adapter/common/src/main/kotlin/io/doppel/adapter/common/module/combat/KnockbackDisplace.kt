package io.doppel.adapter.common.module.combat

import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.option.int
import kotlin.random.Random

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
    private var lastStrafeLeft = false
    private var lastFlickNano = 0L
    /** Non-zero while the one-tick yaw offset should be applied. */
    private var flickRemainingTicks = 0
    private var flickYawOffset = 0f

    // ========== Listeners ==========
    // Fresh hit (attack landed) → queue the flick.
    private val attackListener: (TargetState?) -> Unit = {
        if (enabled) onAttack()
    }
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p)
    }

    private fun onAttack() {
        val now = System.nanoTime()
        if (now - lastFlickNano < cooldownMs * 1_000_000L) return
        if (notTakingKnockback && EventBridge.velocityPacketReceivedThisTick) return
        val dirSign = currentDirection()
        if (dirSign == 0) return
        // Queue a one-tick yaw offset (server sees the attacker's yaw at attack time).
        flickYawOffset = dirSign * angle
        flickRemainingTicks = 1
        lastFlickNano = now
    }

    private fun onTick(player: PlayerState) {
        // Track the last strafe direction for "use last strafe".
        if (EventBridge.isKeyLeftDown != EventBridge.isKeyRightDown) {
            lastStrafeLeft = EventBridge.isKeyLeftDown
        }
        if (flickRemainingTicks <= 0) return
        // Weapon condition (checked with the current held item).
        if (weaponOnly && player.weaponType == io.doppel.core.strategy.click.WeaponType.OTHER) {
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

    private fun currentDirection(): Int {
        return when (direction) {
            "Left" -> 1
            "Right" -> -1
            "Random" -> if (Random.nextBoolean()) 1 else -1
            "Strafe", "StrafeInverted" -> {
                val left = EventBridge.isKeyLeftDown
                val right = EventBridge.isKeyRightDown
                val base = when {
                    left && !right -> 1
                    right && !left -> -1
                    else -> if (useLastStrafe) (if (lastStrafeLeft) 1 else -1) else 0
                }
                if (direction == "StrafeInverted") -base else base
            }
            else -> 1
        }
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        lastFlickNano = 0L
        lastStrafeLeft = false
        flickRemainingTicks = 0
        EventBridge.registerAttackListener(attackListener)
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterAttackListener(attackListener)
        EventBridge.unregisterTickListener(tickListener)
        flickRemainingTicks = 0
        flickYawOffset = 0f
    }
}
