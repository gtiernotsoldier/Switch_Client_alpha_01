package io.doppel.core.ipc

import io.doppel.core.strategy.aim.AimConfig
import io.doppel.core.strategy.aim.AimInput
import io.doppel.core.strategy.aim.AimResult
import io.doppel.core.strategy.aim.AimStrategy
import io.doppel.core.strategy.click.ClickConfig
import io.doppel.core.strategy.click.ClickInput
import io.doppel.core.strategy.click.ClickResult
import io.doppel.core.strategy.click.ClickStrategy
import io.doppel.core.strategy.click.CooldownClickConfig
import io.doppel.core.strategy.click.CooldownClickStrategy

/**
 * The glue layer: strategies that own no decisions, only a socket.
 *
 * Each class here implements the same interface as its in-process twin, so the
 * adapter switches backends by constructing a different object and changing
 * nothing else. That is the entire point — a class in this file is allowed to
 * encode, ship, decode and map. It is not allowed to branch on the *content* of
 * a decision, because the moment it does, the two backends can disagree and the
 * parity fixtures stop meaning anything.
 *
 * ## Why the local implementations still exist
 *
 * They are the reference the parity tests compare against, and the degradation
 * path if the core is not running. Deleting them is a separate change that has
 * to happen after the adapter is wired to a supervisor that guarantees a core
 * process exists — otherwise every user without the binary gets a proxy that
 * never fires.
 *
 * ## Degradation
 *
 * A missed deadline returns the inoffensive answer, never the previous one:
 * `Skip` for aim and click. Re-applying last tick's rotation would make the
 * camera lag a frame behind a target that has already moved, which reads as
 * rubber-banding and is worse than not assisting at all.
 */
class RemoteAimStrategy(
    private val stub: CoreStub,
    private val tick: () -> Int
) : AimStrategy {

    override val name: String get() = "RemoteAimStrategy"

    private var pushed: AimConfig? = null

    override fun execute(config: AimConfig, state: AimStrategy.State, input: Any): AimResult {
        require(input is AimInput) { "RemoteAimStrategy expects AimInput" }

        // Config travels on its own frame and is only re-sent when it changes.
        // Per-tick would put a second round trip on the tick thread for values
        // that move when a slider moves.
        if (config != pushed) {
            stub.pushConfig(Wire.MODULE_AIM, encodeAimConfig(config))
            pushed = config
        }

        val d = stub.decideAim(
            input.player,
            input.target,
            input.mouseDeltaX,
            input.mouseDeltaY,
            input.sensitivity,
            tick()
        ) ?: return AimResult.Skip

        return if (d.apply) {
            AimResult.ApplyRotation(d.worldPoint, d.hitbox, d.fractionY, d.fractionP)
        } else {
            AimResult.Skip
        }
    }
}

class RemoteClickStrategy(
    private val stub: CoreStub,
    private val tick: () -> Int
) : ClickStrategy<ClickConfig, ClickStrategy.State> {

    override val name: String get() = "RemoteClickStrategy"

    private var pushed: ClickConfig? = null

    override fun execute(config: ClickConfig, state: ClickStrategy.State, input: Any): ClickResult {
        require(input is ClickInput) { "RemoteClickStrategy expects ClickInput" }

        if (config != pushed) {
            stub.pushConfig(Wire.MODULE_CLICK, encodeClickConfig(config))
            pushed = config
        }

        return stub.decideClick(input.player, input.target, input.attackCooldown, input.isFalling, tick())
    }
}

class RemoteCooldownClickStrategy(
    private val stub: CoreStub,
    private val tick: () -> Int
) : ClickStrategy<CooldownClickConfig, CooldownClickStrategy.CritState> {

    override val name: String get() = "RemoteCooldownClickStrategy"

    private var pushed: CooldownClickConfig? = null

    override fun execute(
        config: CooldownClickConfig,
        state: CooldownClickStrategy.CritState,
        input: Any
    ): ClickResult {
        require(input is ClickInput) { "RemoteCooldownClickStrategy expects ClickInput" }

        if (config != pushed) {
            stub.pushConfig(Wire.MODULE_CLICK, encodeCooldownClickConfig(config))
            pushed = config
        }

        return stub.decideClick(input.player, input.target, input.attackCooldown, input.isFalling, tick())
    }
}
