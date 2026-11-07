package io.doppel.ipc

import io.doppel.contract.strategy.aim.AimConfig
import io.doppel.contract.strategy.aim.AimResult
import io.doppel.contract.strategy.aim.AimStrategy
import io.doppel.contract.strategy.click.ClickConfig
import io.doppel.contract.strategy.click.ClickResult
import io.doppel.contract.strategy.click.ClickStrategy
import io.doppel.contract.strategy.click.CooldownClickConfig
import io.doppel.contract.strategy.click.CritState

/**
 * Process-wide handle on the core session.
 *
 * The adapter asks [Strategies] for a strategy and never touches this directly.
 * Whoever owns the process lifecycle (the supervisor) assigns [stub] once the
 * socket is up, and clears it on shutdown — a null stub is the normal state on a
 * machine without the core binary, not an error.
 */
object CoreLink {
    @Volatile
    var stub: CoreStub? = null
}

/**
 * The single place the adapter turns "I need a strategy" into an object.
 *
 * ## Why a factory instead of a constructor
 *
 * `AimAssist` used to say `LegitAimStrategy()`. That one line is what keeps the
 * algorithm classes inside `agent.jar` and is also what would turn into
 * `NoClassDefFoundError` the moment they stop being packaged. Indirection here
 * means the choosing happens in one file instead of twenty-four.
 *
 * ## What happens when the core is not running
 *
 * The off-line implementations below do nothing, and doing nothing is the right
 * answer: an aim assist that keeps applying last tick's rotation while the
 * decision source is gone drifts the camera off target, and a clicker that
 * keeps its last verdict keeps clicking. Both are worse than visibly idle, and
 * both are unrecoverable once the core comes back. `PROTECTION.md` calls this
 * trade explicitly: the jar ships no algorithm, so no core means no module.
 */
object Strategies {

    /**
     * Frame correlation id.
     *
     * The core only echoes it back so a late reply can be matched to (and
     * discarded instead of) the request that is waiting. Nothing on the JVM side
     * reads the tick for logic, so a plain counter is enough — it does not have
     * to agree with the game's tick counter.
     */
    private var sequence = 0

    private fun nextTick(): Int {
        sequence += 1
        return sequence
    }

    fun aim(): AimStrategy {
        val stub = CoreLink.stub ?: return OfflineAim
        return RemoteAimStrategy(stub) { nextTick() }
    }

    fun click(): ClickStrategy<ClickConfig, ClickStrategy.State> {
        val stub = CoreLink.stub ?: return OfflineClick
        return RemoteClickStrategy(stub) { nextTick() }
    }

    /**
     * Concrete type on purpose: the 1.9+ adapter also calls `processTick`, which the
     * interface does not declare. Passing a null stub makes it inert.
     */
    fun cooldownClick(): RemoteCooldownClickStrategy =
        RemoteCooldownClickStrategy(CoreLink.stub) { nextTick() }

    /** True when a core session is attached; the HUD uses it to say so. */
    val online: Boolean get() = CoreLink.stub != null
}

private object OfflineAim : AimStrategy {
    override val name: String get() = "OfflineAim"
    override fun execute(config: AimConfig, state: AimStrategy.State, input: Any) = AimResult.Skip
}

private object OfflineClick : ClickStrategy<ClickConfig, ClickStrategy.State> {
    override val name: String get() = "OfflineClick"
    override fun execute(config: ClickConfig, state: ClickStrategy.State, input: Any) = ClickResult.Skip
}

