package io.doppel.adapter.common.module.player

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.api.TrackRecord
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.HudLineProvider
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.int
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState

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
 * randomized phase lengths) is the core-rs migration — see [KamuiEngine] for the line
 * where that split will be drawn.
 *
 * ## Guardrails (the module dies safely, every path)
 *
 *  * **Hold cap** — `Phase` is clamped by `MaxHold`; a mistuned Phase option cannot
 *    outlive the cap. The platform's own vault cap backs this up.
 *  * **GUI** — a screen opening mid-phase ends it (with the flush).
 *  * **Death** — hard reset, no cooldown, flush.
 *  * **Disable / claim leak** — `Module.disable` drops packet claims through
 *    [EventBridge.releaseAllClaims], and `onDisable` releases + flushes explicitly.
 *  * **Server re-sync** — the platform flushes the backlog itself when the server
 *    re-asserts the position (teleport/correction); the fossil is gone, there is nothing
 *    left to hold.
 *  * **Co-claimants** — a routine lag window (FakeLag et al.) may outlive the phase, but
 *    the flush request is one-sided: the phase's backlog goes out regardless of claims.
 *
 * ## The trail (TrackRecord)
 *
 * While not phasing, the module feeds the player's reported position into
 * [TrackRecord] — the "what the server has seen" ring buffer the desync readout and the
 * future Mark & Recall consume. While phasing, feeding stops: the buffer freezes at the
 * fossil instead of recording frames the server will never see.
 *
 * Degradation direction: manual mode needs nothing from the core — no judgement to
 * degrade, the module phases even with the core unreachable.
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

    private var engine = KamuiEngine(KamuiEngine.Config(phaseTicks, maxHoldTicks, cooldownTicks))

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p)
    }

    private val keyListener: (Int, Boolean) -> Unit = { key, pressed ->
        if (enabled && pressed) onKeyEdge(key)
    }

    private fun onTick(player: PlayerState) {
        val eng = engine
        val cfg = cachedConfig { KamuiEngine.Config(phaseTicks, maxHoldTicks, cooldownTicks) }
        if (eng.config() != cfg) eng.applyConfig(cfg)

        val dead = player.health <= 0f
        val gui = blinkOnGui && EventBridge.isGuiOpen
        val commands = eng.tick(KamuiEngine.TickEvents(guiOpen = gui, dead = dead))
        for (command in commands) {
            when (command) {
                KamuiEngine.Command.CLAIM_HOLD ->
                    EventBridge.claimPacketHold(SOURCE, EventBridge.PACKET_PRIORITY_PHASE)
                KamuiEngine.Command.RELEASE_AND_FLUSH -> release()
            }
        }

        // Feed the trail only while the server can actually see us: during a phase the local
        // position diverges from the fossil, and those frames must not masquerade as sent.
        if (eng.state != KamuiEngine.State.PHASE) {
            TrackRecord.push(
                TrackRecord.Frame(
                    tick = player.ticks.toInt(),
                    x = player.position.x, y = player.position.y, z = player.position.z,
                    yaw = player.rotation.yaw, pitch = player.rotation.pitch,
                    onGround = player.onGround
                )
            )
        }
    }

    private fun onKeyEdge(key: Int) {
        if (key != triggerKey || triggerKey == Module.NO_KEYBIND) return
        // A phase behind a GUI is a phase the player cannot steer — do not start one.
        if (EventBridge.isGuiOpen) return
        if (!engine.canTrigger()) return
        if (engine.trigger()) {
            // Claim at the keypress, not on the next tick: the packet the very same tick
            // produces may already be the one that must be held.
            EventBridge.claimPacketHold(SOURCE, EventBridge.PACKET_PRIORITY_PHASE)
            markWorked()
        }
    }

    /** End the hold: claim goes, backlog goes out regardless of any co-claimant. */
    private fun release() {
        EventBridge.releasePacketHold(SOURCE)
        EventBridge.requestPacketFlush(SOURCE, "phase release")
        markWorked()
    }

    override fun onEnable() {
        engine = KamuiEngine(KamuiEngine.Config(phaseTicks, maxHoldTicks, cooldownTicks))
        // A fresh enable inherits nothing: not a stale claim, not a stale resolved flag.
        EventBridge.releasePacketHold(SOURCE)
        EventBridge.registerTickListener(tickListener)
        EventBridge.registerKeyListener(keyListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.unregisterKeyListener(keyListener)
        // A half-elapsed phase must not survive a toggle: release + flush, exactly like
        // the engine's own end-of-phase, so the queue is never left held on a dead module.
        engine.abort().forEach { _ -> release() }
        EventBridge.releasePacketHold(SOURCE)
    }

    // ── HUD ──────────────────────────────────────────────────────────────────────────────────

    override fun hudValue(): String {
        return when (engine.state) {
            KamuiEngine.State.IDLE -> "ready"
            KamuiEngine.State.PHASE -> "HOLD ${engine.phaseTick}t"
            KamuiEngine.State.COOLDOWN -> "CD ${engine.cooldownTick}t"
        }
    }

    override fun hudHighlight(): Boolean = engine.state == KamuiEngine.State.PHASE
}
