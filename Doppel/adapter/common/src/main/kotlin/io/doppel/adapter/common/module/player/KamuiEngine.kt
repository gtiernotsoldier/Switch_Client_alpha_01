package io.doppel.adapter.common.module.player

/**
 * Kamui — the phase state machine. Pure, deterministic, testable.
 *
 * No Minecraft, no RNG, no clock: the module feeds in one [TickEvents] per tick and lands
 * the returned commands on the platform. Everything time-shaped is a **tick count**, not a
 * timestamp — the engine cannot drift with frame drops, and a gold-style test can replay
 * a whole cast tick for tick.
 *
 * ## The one cycle
 *
 * ```
 * IDLE --trigger()--> PHASE --(cap reached / GUI / server re-sync)--> COOLDOWN --> IDLE
 *                        |
 *                        +--(death)--> IDLE (hard reset, no cooldown)
 * ```
 *
 * A phase holds the connection while the player sidesteps locally: the server keeps seeing
 * the fossil, in-flight swings resolve against it, and the RELEASE command lets the backlog
 * out the instant the phase ends. The command split mirrors the platform contract:
 *
 *  * [Command.CLAIM_HOLD] — refresh the vault claim this tick (packets are queued instead of
 *    sent; the platform keeps its own hard cap as the last-resort guard);
 *  * [Command.RELEASE_AND_FLUSH] — drop the claim AND ask for the one-sided flush. The flush
 *    request is what makes a release immune to a co-claimant (an active FakeLag window): a
 *    phase release is the whole point of the mechanic, so claims no longer decide.
 *
 * ## Why the state machine lives here, not in core-rs (yet)
 *
 * The packet vault is a platform-side mechanism the way ForgePacketInterceptor's held queue
 * is — this engine is the timing half of that mechanism, driven by key edges and vault
 * commands rather than by combat judgements. The moment Kamui grows a trigger DECISION
 * (AUTO/SEMI, PRD charge rolls, randomized phase lengths), those decisions migrate to
 * `core-rs::strategy::kamui` behind an appended wire op, with a Kotlin oracle and golden
 * fixtures — the manual engine here becomes the oracle's first column. Until then there is
 * deliberately no `kotlin.random` and no nanoTime in this file.
 *
 * Degradation direction: manual mode needs nothing from the core — if the core is
 * unreachable the module still phases (there is no judgement to degrade). See
 * PROTECTION.md §9.5 for the direction convention.
 */
class KamuiEngine(private var cfg: Config) {

    data class Config(val phaseTicks: Int, val hardCapTicks: Int, val cooldownTicks: Int)

    enum class State { IDLE, PHASE, COOLDOWN }

    /** What the module must land on the platform this tick, in order. */
    enum class Command { CLAIM_HOLD, RELEASE_AND_FLUSH }

    /** Per-tick environment. Everything that can end a phase early is an input, not a decision. */
    data class TickEvents(val guiOpen: Boolean, val dead: Boolean)

    var state: State = State.IDLE
        private set
    /** Ticks spent in the current phase (1-based once the first tick has run). */
    var phaseTick: Int = 0
        private set
    /** Ticks spent in the current cooldown. */
    var cooldownTick: Int = 0
        private set
    /** Completed phase starts, for logs and the HUD. */
    var casts: Int = 0
        private set

    /** Retune from config. Ignored while a phase is live so a cast is never retuned mid-flight. */
    fun applyConfig(newCfg: Config) {
        if (state == State.IDLE) cfg = newCfg
    }

    fun config(): Config = cfg

    fun canTrigger(): Boolean = state == State.IDLE

    /** A key edge in IDLE starts a phase. Returns false when the engine is busy. */
    fun trigger(): Boolean {
        if (state != State.IDLE) return false
        state = State.PHASE
        phaseTick = 0
        cooldownTick = 0
        casts++
        return true
    }

    /**
     * Advance one tick. The returned list is what the module must do this tick:
     * `CLAIM_HOLD` every phasing tick, `RELEASE_AND_FLUSH` exactly once at the end.
     */
    fun tick(events: TickEvents): List<Command> {
        when (state) {
            State.IDLE -> return emptyList()

            State.PHASE -> {
                phaseTick++
                // Safety overrides, in the order they are allowed to fire. A phase is never
                // worth a GUI stall (the player cannot play blink while a screen eats their
                // input) and never worth a runaway hold (the hard cap bounds a mistuned
                // Phase option no matter what).
                if (events.dead) return endPhase(hardReset = true)
                if (events.guiOpen) return endPhase()
                if (phaseTick >= effectiveCap()) return endPhase()
                return listOf(Command.CLAIM_HOLD)
            }

            State.COOLDOWN -> {
                cooldownTick++
                if (cooldownTick >= cfg.cooldownTicks) {
                    state = State.IDLE
                    cooldownTick = 0
                }
                return emptyList()
            }
        }
    }

    /** Forced release: module disable, dimension change, any path that must not leave a claim. */
    fun abort(): List<Command> {
        return if (state == State.PHASE) {
            endPhase(hardReset = true)
        } else {
            state = State.IDLE
            cooldownTick = 0
            emptyList()
        }
    }

    /** The phase never outlives its cap even if Phase is mistuned above MaxHold. */
    private fun effectiveCap(): Int = minOf(cfg.phaseTicks, cfg.hardCapTicks)

    private fun endPhase(hardReset: Boolean = false): List<Command> {
        if (hardReset) {
            state = State.IDLE
            phaseTick = 0
            cooldownTick = 0
        } else {
            state = State.COOLDOWN
            cooldownTick = 0
        }
        return listOf(Command.RELEASE_AND_FLUSH)
    }
}
