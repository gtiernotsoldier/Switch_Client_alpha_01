package io.switchlite.adapter.common.module.combat

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.choices
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.option.int
import kotlin.random.Random

/**
 * KnockbackDisplace Module (Slinky Knockback Displace, 1.8 exclusive).
 *
 * Abuses a vanilla Minecraft flaw to deal knockback at a different angle. The S12 knockback
 * motion vector is rotated around the Y axis by [angle] degrees before it lands — the knockback
 * distance stays the same, but its direction is displaced (left / right / random / strafe).
 *
 * Architecture compliance:
 * 1. Logic in module: direction selection (left/right/random/strafe), cooldown gating, angle.
 * 2. Platform glue: writes the desired angle into [EventBridge.knockbackDisplaceAngle]; the Netty
 *    thread (ForgePacketInterceptor → onVelocityPacket) rotates the motion vector by it.
 * 3. Algorithm (rotation math) lives in core-free adapter glue (pure 2D rotation).
 */
object KnockbackDisplace : Module("KnockbackDisplace", Category.COMBAT) {

    // ========== Configuration ==========
    /** Direction: Left / Right / Random / Strafe / Strafe (inverted). */
    private val direction by choices("Direction", arrayOf("Left", "Right", "Random", "Strafe", "StrafeInverted"))
    /** Use the last pressed strafe direction when no strafe key is held (Strafe modes). */
    private val useLastStrafe by boolean("UseLastStrafe", true)
    /** Cooldown between activations (ms). */
    private val cooldownMs by int("Cooldown", 100, 0..2000, "ms")
    /** Angle to displace knockback by, relative to looking direction (degrees, 0 = vanilla). */
    private val angle by float("Angle", 90.0f, -180.0f..180.0f, "degrees")

    // ========== Conditions ==========
    private val notTakingKnockback by boolean("NotTakingKnockback", false)
    private val weaponOnly by boolean("WeaponOnly", false)

    // ========== State ==========
    private var lastStrafeLeft = false
    private var lastActivationNano = 0L

    // ========== Tick Listener ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p)
    }

    private fun onTick(player: PlayerState) {
        // Cooldown gate.
        val now = System.nanoTime()
        if (now - lastActivationNano < cooldownMs * 1_000_000L) return

        // Weapon condition.
        if (weaponOnly && player.weaponType == io.switchlite.core.strategy.click.WeaponType.OTHER) return

        // "Not taking knockback" condition (Slinky): only displace when the player isn't already
        // being knocked back this tick (avoids fighting the incoming motion).
        if (notTakingKnockback && EventBridge.velocityPacketReceivedThisTick) return

        // Compute the displacement sign/direction.
        val dirSign = when (direction) {
            "Left" -> 1
            "Right" -> -1
            "Random" -> if (Random.nextBoolean()) 1 else -1
            "Strafe", "StrafeInverted" -> {
                val left = EventBridge.isKeyLeftDown
                val right = EventBridge.isKeyRightDown
                val base = when {
                    left && !right -> 1
                    right && !left -> -1
                    else -> {
                        if (useLastStrafe && lastStrafeLeft) 1 else if (useLastStrafe && !lastStrafeLeft) -1 else 0
                    }
                }
                if (direction == "StrafeInverted") -base else base
            }
            else -> 1
        }
        // Track last strafe direction for "use last strafe".
        if (EventBridge.isKeyLeftDown != EventBridge.isKeyRightDown) {
            lastStrafeLeft = EventBridge.isKeyLeftDown
        }

        // Strafe modes: when neither (and not using last), do nothing.
        if (dirSign == 0) {
            EventBridge.knockbackDisplaceAngle = 0f
            return
        }

        // Write the active displacement angle (net, with direction sign).
        EventBridge.knockbackDisplaceAngle = dirSign * angle
        lastActivationNano = now
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        lastActivationNano = 0L
        lastStrafeLeft = false
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.knockbackDisplaceAngle = 0f
    }
}
