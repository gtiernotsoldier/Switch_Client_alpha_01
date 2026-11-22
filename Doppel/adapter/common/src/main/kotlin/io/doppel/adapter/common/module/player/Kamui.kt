package io.doppel.adapter.common.module.player

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.HudLineProvider
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.int
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.ipc.KamuiAnswer
import io.doppel.ipc.Strategies

/**
 * Kamui — hold the connection for a few ticks while the player sidesteps, then let the
 * backlog out. The manual first slice of the phase family.
 *
 * ## What one cast is
 *
 * The trigger key starts a **phase**: the vault claim makes the platform queue every
 * outbound packet, so the server keeps seeing the fossil — the position the last sent
 * packet declared. In that window an attacker's swings settle against the fossil, while
 * the real body is already somewhere else. When the phase cap is reached the claim drops
 * and the flush request sends the whole backlog NOW: the server walks the held path in
 * order, every per-tick step of it physics-legal because it is the path the client
 * actually simulated, and the attacker's next swing measures against the new position.
 *
 * That is the whole mechanic of the slice: no synthetic movement, no auto-trigger. The
 * player picks the moment and does the sidestep; the module guarantees the protocol
 * timing and the guardrails. The trigger/geometry logic (AUTO/SEMI, PRD charges,
 * randomized phase lengths) is a future core-rs migration on this same op.
 *
 * ## Where the state lives
 *
 * The phase machine (IDLE / PHASE / COOLDOWN), the tick counters, the cast counter and
 * the knob set latched when the phase started live in the algorithm process
 * (`strategy::kamui`, wire op `PLAYER_KAMUI_TICK` = 14) and are matched tick-for-tick by
 * `KamuiParityTest`. This side keeps exactly what the core cannot do:
 *
 *  * **judgements** — the player being dead (`health <= 0f`), the GUI being open
 *    (already folded with [blinkOnGui]), the trigger key edge;
 *  * **marshalling** — the six arguments of one request per tick;
 *  * **platform writes** — the vault claim, the release + flush, the HUD readout.
 *
 * The trigger key edge is the ONE thing that legitimately crosses ticks on the JVM now
 * (a `@Volatile` pending flag): a key press has no tick of its own, so the edge must
 * survive the hop from the key thread to the next tick listener. Nothing else does —
 * the machine itself lives in the core, and whether an edge starts a phase is its
 * answer, not ours. There is **no RNG stream** — the machine is deterministic, no
 * `kotlin.random`, no clock.
 *
 * ## Guardrails (the module dies safely, every path)
 *
 *  * **Hold cap** — `Phase` is clamped by `MaxHold`; a mistuned Phase option cannot
 *    outlive the cap. The platform's own vault cap backs this up.
 *  * **GUI** — a screen opening mid-phase ends it (with the flush).
 *  * **Death** — hard reset, no cooldown, flush.
 *  * **Disable / claim leak** — `Module.disable` drops packet claims through
 *    [EventBridge.releaseAllClaims], and `onDisable` releases + flushes explicitly.
 *  * **Refused edges** — a key edge the core refuses while no phase is live (cooldown)
 *    has its keypress claim dropped on that tick's answer: at most one tick of hold on
 *    a refused edge, versus a missed hold on an accepted one.
 *  * **Server re-sync** — the platform flushes the backlog itself when the server
 *    re-asserts the position (teleport/correction); the fossil is gone, there is nothing
 *    left to hold.
 *  * **Co-claimants** — a routine lag window (FakeLag et al.) may outlive the phase, but
 *    the flush request is one-sided: the phase's backlog goes out regardless of claims.
 *
 * ## The trail (core-owned)
 *
 * While not phasing, this module's reported positions feed the trail ring — the
 * "what the server has seen" history Mark & Recall (Izanagi) walks back on. The
 * ring lives in the core (`strategy::izanagi`), fed by Izanagi's own op; this
 * module's contribution is the [EventBridge.kamuiPhaseActive] flag: while a
 * phase holds the connection, the reported position is the fossil, and the
 * flag freezes the feed so frames the server will never see cannot masquerade
 * as sent.
 *
 * ## Degradation direction (PROTECTION.md §9.5.3)
 *
 * No answer from the core → the module must NOT hold: it releases its claim and does
 * NOT flush. Holding is the state that can desync the view — a missing answer must not
 * leave packets queued behind a dead decision — while "not holding" is merely vanilla.
 * Same direction as LagRange and Backtrack. The core's phase machine keeps running
 * through a deadline miss, so the next CLAIM flag simply re-raises the claim.
 */
object Kamui : Module("Kamui", Category.PLAYER), HudLineProvider {

    /** Vault claim source — equals [name] so Module.disable's sweep finds it. */
    private const val SOURCE = "Kamui"

    private val mode by choices("Mode", arrayOf("Manual"))

    /** How long one phase lasts, in ticks (20 Hz). Kept short: the phase window is when we are hittable. */
    private val phaseTicks by int("Phase", 3, 1..10, "t")

    /** Absolute bound on one phase, whatever Phase says — the mistune guard. */
    private val maxHoldTicks by int("MaxHold", 10, 3..20, "t")

    /** Quiet time after a release before the next cast — the vault is not held continuously. */
    private val cooldownTicks by int("Cooldown", 8, 0..40, "t")

    /** GLFW code that starts one phase (-1 = unbound). Not the module toggle: that is Keybind. */
    private val triggerKey by int("TriggerKey", -1, -1..400)

    /** Currently only Manual. Kept as an option so the AUTO/SEMI modes slot in without a GUI change. */
    private val blinkOnGui by boolean("FlushOnGui", true)

    /**
     * The trigger key edge, registered by the key listener (key thread) and consumed
     * by the tick listener. `@Volatile`: written on the key thread, read+cleared on
     * the tick thread — a torn or stale read would lose the press or repeat it, and
     * both look like "the key did nothing" or "it cast twice".
     */
    @Volatile private var pendingTriggerEdge: Boolean = false

    /**
     * The last decoded answer, for the HUD between ticks. **Display state, not
     * decision state**: every decision this module makes comes from the CURRENT
     * tick's answer; this field only stops the HUD flickering between two answers.
     */
    @Volatile private var lastAnswer: KamuiAnswer? = null

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p)
    }

    private val keyListener: (Int, Boolean) -> Unit = { key, pressed ->
        if (enabled && pressed) onKeyEdge(key)
    }

    private fun onTick(player: PlayerState) {
        // Consume the edge first: registered between ticks, survives exactly one hop.
        val edge = pendingTriggerEdge
        pendingTriggerEdge = false

        val dead = player.health <= 0f
        val gui = blinkOnGui && EventBridge.isGuiOpen

        val answer = Strategies.kamuiTick(
            player, null,
            triggerEdge = edge,
            guiOpen = gui,
            dead = dead,
            phaseTicks = phaseTicks,
            maxHoldTicks = maxHoldTicks,
            cooldownTicks = cooldownTicks
        )

        if (answer == null) {
            // Degradation direction (PROTECTION.md §9.5.3): no answer → the module must
            // NOT hold. Holding is the state that can desync the view; a missing answer
            // must not leave packets queued behind a dead decision. No flush: nothing
            // was decided this tick, and the backlog drains on its own once the claim
            // is gone. The phase flag follows: with no decision there is no phase.
            EventBridge.releasePacketHold(SOURCE)
            EventBridge.kamuiPhaseActive = false
            return
        }

        lastAnswer = answer
        // The vault-flag pattern: Izanagi reads this to freeze its trail feed
        // while the reported position is the fossil, and the vault arbiter's
        // logs say who owns the position. Written from the CURRENT answer only.
        EventBridge.kamuiPhaseActive = answer.state == KamuiAnswer.STATE_PHASE

        when {
            answer.release -> release()
            answer.claim ->
                EventBridge.claimPacketHold(SOURCE, EventBridge.PACKET_PRIORITY_PHASE)
            edge && answer.state == KamuiAnswer.STATE_PHASE && answer.phaseTick == 0 -> {
                // The tick that consumed the edge STARTED the phase (state PHASE,
                // phaseTick 0, no flags — the machine evaluates the phase on the next
                // tick, exactly like the old engine's trigger()/tick() split). The claim
                // raised at the keypress covers the gap until the first CLAIM flag.
                markWorked()
            }
        }

        // A key edge the machine refused while no phase is live (cooldown, or the
        // cooldown that ended on this very tick): the keypress claim would otherwise
        // outlive the keypress with nothing ever answering CLAIM or RELEASE for it —
        // drop it here. An edge refused during a LIVE phase merges with that phase's
        // own claim, which its CLAIM flags refresh and its RELEASE drops — leave it.
        if (edge && answer.state != KamuiAnswer.STATE_PHASE) {
            EventBridge.releasePacketHold(SOURCE)
        }
    }

    private fun onKeyEdge(key: Int) {
        if (key != triggerKey || triggerKey == Module.NO_KEYBIND) return
        // A phase behind a GUI is a phase the player cannot steer — do not start one.
        if (EventBridge.isGuiOpen) return
        // The edge must survive from the key thread to the next tick — that is the
        // ONLY thing that crosses ticks on the JVM. Whether it starts a phase is the
        // core's answer, not ours.
        pendingTriggerEdge = true
        // Claim at the keypress, not on the next tick: the packet the very same tick
        // produces may already be the one that must be held. The core's CLAIM flag
        // refreshes the claim every phasing tick; if the core refuses the edge (a
        // phase is live or cooling down), that tick's answer drops the claim — at
        // most one tick of hold on a refused edge.
        EventBridge.claimPacketHold(SOURCE, EventBridge.PACKET_PRIORITY_PHASE)
    }

    /** End the hold: claim goes, backlog goes out regardless of any co-claimant. */
    private fun release() {
        EventBridge.releasePacketHold(SOURCE)
        EventBridge.requestPacketFlush(SOURCE, "phase release")
        markWorked()
    }

    override fun onEnable() {
        // A fresh enable inherits nothing: not a stale edge, not a stale HUD answer,
        // and not a machine the core still believes it owns — the Reset frame
        // (CONTRIBUTING step 5) drops the phase machine on the core side too.
        Strategies.resetPlayer()
        EventBridge.releasePacketHold(SOURCE)
        EventBridge.kamuiPhaseActive = false
        pendingTriggerEdge = false
        lastAnswer = null
        EventBridge.registerTickListener(tickListener)
        EventBridge.registerKeyListener(keyListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.unregisterKeyListener(keyListener)
        // A half-elapsed phase must not survive a toggle: release + flush, exactly
        // like the core's own end-of-phase, so the queue is never left held on a dead
        // module. The module cannot ask the core on its way down, so the last answer
        // and an unconsumed edge are the only honest witnesses of a live phase: the
        // answer's state is what the core last said, and a pending edge means a phase
        // was claimed at the keypress but the core has not been told yet.
        if (lastAnswer?.state == KamuiAnswer.STATE_PHASE || pendingTriggerEdge) {
            markWorked()
            EventBridge.releasePacketHold(SOURCE)
            EventBridge.requestPacketFlush(SOURCE, "phase release")
        }
        pendingTriggerEdge = false
        lastAnswer = null
        EventBridge.releasePacketHold(SOURCE)
        EventBridge.kamuiPhaseActive = false
        // The Reset frame — CONTRIBUTING step 5: the core must forget the cast too,
        // or a re-enable would resume a half-elapsed phase it still believes is live.
        Strategies.resetPlayer()
    }

    // ── HUD ──────────────────────────────────────────────────────────────────────────────────

    override fun hudValue(): String {
        val a = lastAnswer ?: return "ready"
        return when (a.state) {
            KamuiAnswer.STATE_PHASE -> "HOLD ${a.phaseTick}t"
            KamuiAnswer.STATE_COOLDOWN -> "CD ${a.cooldownTick}t"
            else -> "ready"
        }
    }

    override fun hudHighlight(): Boolean = lastAnswer?.state == KamuiAnswer.STATE_PHASE
}
