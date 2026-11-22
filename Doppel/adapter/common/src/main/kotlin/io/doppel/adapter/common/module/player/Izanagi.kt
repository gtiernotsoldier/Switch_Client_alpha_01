package io.doppel.adapter.common.module.player

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.HudLineProvider
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.int
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.ipc.IzanagiState
import io.doppel.ipc.IzanagiStep
import io.doppel.ipc.Strategies

/**
 * Izanagi — Mark & Recall: walk your own reported path back to a pinned frame.
 * Position rewind only — health, potions, inventory and burn ticks stay in the
 * present; the body goes back, the bill does not.
 *
 * ## What one recall is
 *
 * While the module runs, every tick's reported position feeds the core's trail
 * ring ("what the server has seen"). The mark key pins the newest frame as the
 * anchor — zero protocol cost, zero charge, single slot, overwrite. The recall
 * key starts a **replay**: the core walks the recorded path back one stored
 * frame per tick and the adapter writes each step as the player's position.
 * Every step is a frame the server already accepted once, so the replay is
 * physics-legal by construction — no synthetic movement, no speed computation
 * (the clamped-speed machinery for compressed recalls is the later migration;
 * this slice walks at exactly 1x, which is the strongest legal position there
 * is).
 *
 * ## Where the state lives (and why nothing races Kamui)
 *
 * The ring, the pin, the walk list, the stall counter and the cooldown live in
 * the algorithm process (`strategy::izanagi`, wire op `PLAYER_IZANAGI_TICK` =
 * 15), matched tick-for-tick by `IzanagiParityTest`. The arbitration with
 * Kamui is **core-internal**: the dispatch arm derives `kamui_busy` from the
 * sibling machine's state, so a mark or a recall against a live phase is
 * refused without any JVM-side cross-module reference — the two machines can
 * never both rewrite the reported position.
 *
 * ## The corridor check fails closed
 *
 * The platform registers a corridor checker (collision + danger sources along
 * the segment); a platform without one leaves [EventBridge.corridorChecker]
 * null, and a null checker means the module never marshals a recall at all —
 * rewinding through unvalidated geometry is exactly the failure the check
 * exists to prevent. A checker rejection stalls the walk; `StallCap` blocked
 * ticks abort it where it stands (no snap — the body stays mid-corridor).
 *
 * ## Platform write
 *
 * An advance step lands through [EventBridge.requestReplayPosition] — the
 * platform sets the player's position on its own thread. Platforms that
 * cannot reposition the player never set [EventBridge.supportsReplayPosition];
 * without the capability the module refuses recalls (the machine still marks,
 * but the pin ages out — nothing is spent).
 *
 * ## Degradation direction (PROTECTION.md §9.5.3)
 *
 * No answer from the core → the module does nothing this tick: no move, no
 * feed. Moving is the state that can desync the view; standing still is
 * merely vanilla. Same direction as the hold family.
 */
object Izanagi : Module("Izanagi", Category.PLAYER), HudLineProvider {

    /** How deep a recall may reach, in ticks — the environment's fuse, not the ring's. */
    private val maxDepth by int("MaxDepth", 20, 1..120, "t")

    /** Blocked-tick tolerance before an aborting replay gives up. */
    private val stallCap by int("StallCap", 5, 1..40, "t")

    /** Quiet time after a recall — 300 ticks = 15 s at 20 Hz. */
    private val cooldownTicks by int("Cooldown", 300, 20..600, "t")

    /** GLFW code that pins the anchor (-1 = unbound). */
    private val markKey by int("MarkKey", -1, -1..400)

    /** GLFW code that starts the replay (-1 = unbound). */
    private val recallKey by int("RecallKey", -1, -1..400)

    /** Kept so the AUTO slice (position-threat trigger) slots in without a GUI change. */
    private val auto by boolean("Auto", false)

    /** Key edges survive their hop from the key thread to the next tick. */
    @Volatile private var pendingMarkEdge: Boolean = false
    @Volatile private var pendingRecallEdge: Boolean = false

    /** Display state, not decision state — the HUD between two answers. */
    @Volatile private var lastStep: IzanagiStep? = null

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p)
    }

    private val keyListener: (Int, Boolean) -> Unit = { key, pressed ->
        if (enabled && pressed) onKeyEdge(key)
    }

    private fun onTick(player: PlayerState) {
        // Consume the edges first: registered between ticks, each survives exactly one hop.
        val markEdge = pendingMarkEdge
        pendingMarkEdge = false
        val recallEdge = pendingRecallEdge
        pendingRecallEdge = false

        val dead = player.health <= 0f
        val gui = EventBridge.isGuiOpen

        // feed = the server can actually see this position: a live Kamui phase
        // means the packets are queued, not sent — those frames must not
        // masquerade as server-seen. A missing answer feeds nothing either.
        val feed = !EventBridge.kamuiPhaseActive

        val corridorOk = EventBridge.checkCorridor(player.position.x, player.position.y, player.position.z)

        val step = Strategies.izanagiTick(
            player, null,
            feed = feed,
            tickClock = player.ticks.toInt(),
            markEdge = markEdge,
            recallEdge = recallEdge,
            corridorOk = corridorOk,
            guiOpen = gui,
            dead = dead,
            maxDepth = maxDepth,
            stallCap = stallCap,
            cooldownTicks = cooldownTicks
        )

        if (step == null) {
            // Degradation direction: no answer → no move, no feed. Standing
            // still is vanilla; moving on a guess desyncs the view.
            return
        }

        lastStep = step

        if (step.advance) {
            // The platform write: one request, consumed by the platform's tick
            // loop on its own thread. The replay step is a frame the server
            // accepted once — the write is a reposition, not a teleport.
            EventBridge.requestReplayPosition(step.x, step.y, step.z)
            markWorked()
        }
        if (step.started || step.aborted || step.done) markWorked()
    }

    private fun onKeyEdge(key: Int) {
        // The replay needs the platform write; without it, marking is allowed
        // (free) but a recall can never be honoured — refuse it at the edge.
        if (key == markKey && markKey != Module.NO_KEYBIND) {
            if (EventBridge.isGuiOpen) return
            pendingMarkEdge = true
        } else if (key == recallKey && recallKey != Module.NO_KEYBIND) {
            if (EventBridge.isGuiOpen) return
            if (!EventBridge.supportsReplayPosition || !EventBridge.corridorAvailable) return
            pendingRecallEdge = true
        }
    }

    override fun onEnable() {
        // A fresh enable inherits nothing — not a stale edge, not a trail the
        // core still believes it owns (the Reset frame drops the ring and pin).
        Strategies.resetPlayer()
        pendingMarkEdge = false
        pendingRecallEdge = false
        lastStep = null
        EventBridge.registerTickListener(tickListener)
        EventBridge.registerKeyListener(keyListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.unregisterKeyListener(keyListener)
        // A half-elapsed replay must not keep moving a dead module: the Reset
        // frame drops the walk on the core side; the last answer's ADVANCE flag
        // would have been consumed by the platform the tick it arrived.
        pendingMarkEdge = false
        pendingRecallEdge = false
        lastStep = null
        EventBridge.clearReplayPosition()
        Strategies.resetPlayer()
    }

    // ── HUD ──────────────────────────────────────────────────────────────────────────────────

    override fun hudValue(): String {
        val s = lastStep ?: return "no mark"
        return when (s.state) {
            IzanagiState.MARKED -> "marked"
            IzanagiState.REPLAY -> "R ${s.remaining}t"
            IzanagiState.COOLDOWN -> "CD"
            else -> "no mark"
        }
    }

    override fun hudHighlight(): Boolean = lastStep?.state == IzanagiState.REPLAY
}
