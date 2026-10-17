package io.doppel.adapter.common.module.movement

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.option.boolean
import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.core.strategy.movement.SocdResolver

/**
 * NullMove — SOCD cleaning for the movement keys (a.k.a. Snap Tap / Null binds).
 *
 * Vanilla adds the two keys of an axis together, so A + D cancels to zero and W + S cancels to zero.
 * This module applies the rule gaming keyboards ship in firmware instead: **the key pressed last
 * wins**, so a direction change happens without a neutral tick.
 *
 * ── Why this is not a velocity change
 *
 * Nothing here touches motion, packets or the player object. It removes one key from the input the
 * game reads, which is exactly what letting go of that key would do — the resulting movement is the
 * movement of a player who pressed only the newer key. There is no number for a movement check to
 * disagree with; the only signature is that the player never spends a tick with both keys down,
 * which a 1000 Hz keyboard also produces.
 *
 * ── Why the physical keys
 *
 * The correction is applied by writing the game's key state (a SUPPRESS claim), so reading the
 * *effective* state back would show the key as released and the module would keep its previous
 * verdict forever. Physical keys are the player's fingers, unaffected by anything this client does.
 *
 * SUPPRESS rather than OWNED: a tap module that wants a key pressed (WTap, a brace) must still win.
 * A correction should never silence another module's deliberate input.
 */
object NullMove : Module("NullMove", Category.MOVEMENT) {

    /** W + S: pressing S while W is held walks backwards immediately instead of stopping. */
    private val forwardAxis by boolean("ForwardAxis", true)

    /** A + D: the classic strafe snap. */
    private val strafeAxis by boolean("StrafeAxis", true)

    private val resolver = SocdResolver()

    private const val F = 1
    private const val B = 2
    private const val L = 4
    private const val R = 8

    private val tickListener: (PlayerState, TargetState?) -> Unit = { _, _ ->
        if (enabled) onTick()
    }

    private fun onTick() {
        val c = resolver.resolve(
            forward = EventBridge.physicalForwardDown,
            back = EventBridge.physicalBackDown,
            left = EventBridge.physicalLeftDown,
            right = EventBridge.physicalRightDown
        )
        // Re-asserted every tick rather than cached: a claim can be dropped from outside (a module
        // disable, a platform reset), and a cache would then believe in a suppression that is no
        // longer there. Four cheap map ops are worth not having that failure mode.
        put(c.suppressForward && forwardAxis, F)
        put(c.suppressBack && forwardAxis, B)
        put(c.suppressLeft && strafeAxis, L)
        put(c.suppressRight && strafeAxis, R)
    }

    private fun put(on: Boolean, bit: Int) {
        if (on) {
            when (bit) {
                F -> EventBridge.suppressForward(name)
                B -> EventBridge.suppressBack(name)
                L -> EventBridge.suppressLeft(name)
                else -> EventBridge.suppressRight(name)
            }
        } else {
            when (bit) {
                F -> EventBridge.releaseForward(name)
                B -> EventBridge.releaseBack(name)
                L -> EventBridge.releaseLeft(name)
                else -> EventBridge.releaseRight(name)
            }
        }
    }

    override fun onEnable() {
        resolver.reset()
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        resolver.reset()
        EventBridge.releaseForward(name)
        EventBridge.releaseBack(name)
        EventBridge.releaseLeft(name)
        EventBridge.releaseRight(name)
    }
}
