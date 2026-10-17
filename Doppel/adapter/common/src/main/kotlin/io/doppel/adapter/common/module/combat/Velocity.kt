package io.doppel.adapter.common.module.combat

import io.doppel.core.model.*
import io.doppel.core.strategy.velocity.ClickVelocityStrategy
import io.doppel.core.strategy.velocity.LegitVelocityStrategy
import io.doppel.core.strategy.velocity.SprintVelocityStrategy
import io.doppel.core.strategy.velocity.VelocityConfig
import io.doppel.core.strategy.velocity.VelocityMode
import io.doppel.core.strategy.velocity.VelocityResult
import io.doppel.core.strategy.velocity.VelocityStrategy
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.HudLineProvider
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.option.int
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.triggerOptions
import kotlin.math.roundToInt

/**
 * Velocity Module — knockback reduction.
 *
 * Modes: Legit (range-random scaling), Click (click-burst), Sprint (input-side,
 * packet untouched).
 * (Delay was split out into the standalone KnockbackDelay module.)
 *
 * ## Sprint mode is a different animal from the other two
 *
 * Legit and Click rewrite the S12 velocity vector: the server computes V, the
 * client sends (1-r)·V. Grim's KnockbackHandler records the V it sent, predicts
 * the player's position from it and compares — so every rewritten hit opens with
 * a first-tick position error of r·0.4 blocks that no amount of tuning removes
 * (`behavioral-tests/velocity_decay_lab.py`).
 *
 * Sprint mode does not delegate to a [VelocityStrategy], because there is no
 * result to compute — it returns `Pass(originalMotion)` and the packet is
 * applied by vanilla untouched. It manipulates two things the server already
 * reads instead:
 *
 *  1. **Movement input.** Vanilla `knockBack()` does `motion /= 2` and *then*
 *     subtracts the knockback, so the velocity you already had is half your
 *     fate. The knockback points away from the attacker, so the cancelling
 *     direction is *toward the source*; on a hit we press the keys that point
 *     there for [sprintHoldTicks] ticks.
 *  2. **The server-side sprint mirror** (`EventBridge.serverSprintState`), which
 *     is what the server's movement prediction for us is computed from. Vanilla
 *     clears sprint on several damage paths; we re-assert it.
 *
 * Both are inputs the server *expects* a client to change, so the resulting
 * motion is the server's own computation and there is no prediction error to
 * find. Vanilla itself desyncs the two sprint states whenever a player attacks
 * while holding sprint (MC-69459, Unresolved), which is why a server cannot
 * police the asserted state without flagging every normal sprint-attacker.
 *
 * Honest bounds: this is a weaker but quieter channel than Legit, not a stronger
 * one. The cancellation is limited by your own speed against the knockback arc.
 *
 * Architecture note: Sprint is the one mode whose algorithm lives in the module
 * rather than in a [VelocityStrategy] — it is a tick-driven input action, not a
 * per-packet transformation, and there is deliberately no result object.
 */
object Velocity : Module("Velocity", Category.COMBAT), HudLineProvider {

    // ========== HUD value (read once on enable/config change) ==========
    /**
     * What the HUD row shows: the *ranges the user configured*, not a derived single number.
     *
     * It used to print the midpoint of each pair — a 0%..100% horizontal range showed as "50%",
     * which is a number the user never set and which disagrees with the two values the panel shows.
     */
    override fun hudValue(): String = when (mode) {
        "Click" -> "Click $clicksMin-$clicksMax clicks"
        "Sprint" -> "Sprint brace ${sprintHoldTicks}t @ ${sprintGateP}/${sprintGateQ}%"
        else -> "Legit ${pctRange(horizontalMin, horizontalMax)}/${pctRange(verticalMin, verticalMax)}"
    }

    /**
     * One retention range as the HUD spells it: `80%-100%`, collapsing to a single value when both
     * ends are equal (`100%`) so an unchanged axis does not read as `100%-100%`.
     */
    private fun pctRange(min: Float, max: Float): String {
        val lo = (min * 100f).roundToInt()
        val hi = (max * 100f).roundToInt()
        return if (lo == hi) "$lo%" else "${minOf(lo, hi)}%-${maxOf(lo, hi)}%"
    }

    override fun hudHighlight(): Boolean = true

    // ========== Mode ==========
    private val mode by choices("Mode", arrayOf("Legit", "Click", "Sprint"))

    // ========== Sprint mode (packet never touched — see the object KDoc) ==========
    /** How many ticks to keep pressing toward the knockback source after a hit. */
    private val sprintHoldTicks by int("SprintHoldTicks", 4, 1..12, "ticks")
    /** Re-assert the server-side sprint flag after a hit. */
    private val sprintSyncServerSprint by boolean("SprintSync", true)
    // The gate is two numbers, not a cadence enum. Defaults 25/25 are memoryless
    // at the human sprint-reset alignment rate; measured against the human
    // population a memoryless gate scores run-length D≈0.01 while a
    // deterministic cadence (the old EveryNth) scores D≈0.21.
    private val sprintGateP by int("SprintGateBraced", 25, 0..100, "%")
    private val sprintGateQ by int("SprintGateIdle", 25, 0..100, "%")
    /** cos of the half-angle of the press sectors: cos(67.5°) ≈ 0.38. */
    private val sprintSector by float("SprintSector", 0.38f, 0.05f..0.95f)

    // ========== Legit — horizontal (min/max separate) ==========
    private val horizontalMin by float("HorizontalMin", 0.4f, 0.0f..1.0f)
    private val horizontalMax by float("HorizontalMax", 0.6f, 0.0f..1.0f)

    // ========== Legit — vertical (min/max separate) ==========
    private val verticalMin by float("VerticalMin", 0.4f, 0.0f..1.0f)
    private val verticalMax by float("VerticalMax", 0.6f, 0.0f..1.0f)

    // ========== Gate — one chain, two numbers ==========
    // Replaces the old `Chance` option plus the module-side `NaturalMode` combo gate. Those were
    // two independent dice, and the second was cadence-shaped ("every Nth hit", or a chance that
    // ramps with the combo) — the one shape a run-length detector is built for. Measured against
    // the human sprint-reset population a cadence scores D ≈ 0.17–0.30, while a rate-matched
    // memoryless gate scores ≈ 0.03. Equal values below = memoryless at that rate; unequal values
    // are what a real human's autocorrelation would look like, which needs a recording to set.
    /** P(reduce | the previous hit was reduced). */
    private val reduceGivenReduced by int("ReduceGivenReduced", 25, 0..100, "%")
    /** P(reduce | the previous hit was skipped). */
    private val reduceGivenSkipped by int("ReduceGivenSkipped", 25, 0..100, "%")

    // ========== Legit: only reduce on the frame the player is hit (LB port) ==========
    // The check itself moved into LegitVelocityStrategy; this only feeds the config.
    private val onlyOnHitFrame by boolean("OnlyOnHitFrame", false)

    // ========== Condition flags (six independent toggles) ==========
    private val onlyMove by boolean("OnlyMove", false)
    private val onlyMoveForward by boolean("OnlyMoveForward", false)
    private val onlyWhenTargetGoesBack by boolean("OnlyWhenTargetGoesBack", false)
    private val onlyGround by boolean("OnlyGround", true)
    private val onlyCurrentView by boolean("OnlyCurrentView", false)
    private val disabledInAir by boolean("DisabledInAir", true)

    // ========== Unified trigger engine ==========
    private val triggerOptions by triggerOptions("Trigger") {
        onlyMove = this@Velocity.onlyMove
        onlyMoveForward = this@Velocity.onlyMoveForward
        onlyWhenTargetGoesBack = this@Velocity.onlyWhenTargetGoesBack
        onlyGround = this@Velocity.onlyGround
        onlyCurrentView = this@Velocity.onlyCurrentView
        disabledInAir = this@Velocity.disabledInAir
    }

    // ========== Click mode config ==========
    private val clicksMin by int("ClicksMin", 2, 1..10)
    private val clicksMax by int("ClicksMax", 5, 1..10)
    private val hurtTimeToClick by int("HurtTimeToClick", 8, 0..10)
    private val whenFacingEnemyOnly by boolean("WhenFacingEnemyOnly", true)
    private val maxAngleDifference by float("MaxAngleDifference", 90f, 0f..180f, "degrees")
    private val clickRange by float("ClickRange", 3.0f, 0.0f..6.0f, "blocks")

    // ========== Core Strategies (Algorithm lives here) ==========
    private val legitStrategy = LegitVelocityStrategy()
    private val clickStrategy = ClickVelocityStrategy()
    private val sprintStrategy = SprintVelocityStrategy()
    private val strategyState = VelocityStrategy.State()

    /** Named listener reference so we can unregister exactly this handler (chain-friendly). */
    private val velocityListenerRef: (VelocityContext) -> PlatformCommand = { ctx ->
        if (enabled) onVelocityPacket(ctx) else PlatformCommand.Pass(ctx.originalMotion)
    }

    // ========== Sprint mode state ==========
    // The arithmetic and the gate live in SprintVelocityStrategy (Core); only the adapter-side
    // bookkeeping of "am I currently holding keys" stays here.
    @Volatile private var braceKeysHeld = false

    /**
     * Wall-clock expiry of the current brace window.
     *
     * The window is counted in ticks, and a tick counter only advances when ticks actually run. If
     * the tick source stalls — world change, disconnect, a lag spike that skips the client tick —
     * a purely tick-based window resumes afterwards and keeps the keys held for its full length in
     * a world that may not even be the same one. This deadline makes the window expire in real
     * time instead, so a stall costs at most one tick of held keys rather than `SprintHoldTicks`.
     */
    @Volatile private var braceDeadlineNanos = 0L

    // ========== Config Builder ==========
    private fun buildConfig(): VelocityConfig = VelocityConfig(
        mode = when (mode) {
            "Legit" -> VelocityMode.LEGIT
            "Click" -> VelocityMode.CLICK
            "Sprint" -> VelocityMode.SPRINT
            else -> VelocityMode.LEGIT
        },
        horizontalMin = horizontalMin,
        horizontalMax = horizontalMax,
        verticalMin = verticalMin,
        verticalMax = verticalMax,
        reduceGivenReduced = reduceGivenReduced,
        reduceGivenSkipped = reduceGivenSkipped,
        onlyOnHitFrame = onlyOnHitFrame,
        triggerOptions = triggerOptions,
        clickBurstMin = clicksMin,
        clickBurstMax = clicksMax,
        hurtTimeToClick = hurtTimeToClick,
        whenFacingEnemyOnly = whenFacingEnemyOnly,
        maxAngleDifference = maxAngleDifference,
        clickRange = clickRange,
        sprintHoldTicks = sprintHoldTicks,
        sprintGateBraced = sprintGateP,
        sprintGateIdle = sprintGateQ,
        sprintSector = sprintSector
    )

    /** Select the active strategy based on mode. */
    private fun currentStrategy(): VelocityStrategy = when (mode) {
        "Legit" -> legitStrategy
        "Click" -> clickStrategy
        else -> legitStrategy
    }

    // ========== Entry Point ==========

    /** Throttle for the velocity result diagnostic. */
    @Volatile private var velDiagCount = 0

    fun onVelocityPacket(ctx: VelocityContext): PlatformCommand {
        // Sprint mode never rewrites the packet — it only observes the hit and arms an
        // input-side response (see the object KDoc). Returning Pass here is the whole
        // point: vanilla applies the knockback itself, so there is nothing to detect.
        if (mode == "Sprint") return onSprintKnockback(ctx)

        // The hit-frame filter and the gate both live in LegitVelocityStrategy now — the module
        // assembles the config and maps the result, which is all the architecture asks of it.
        return reduceNormally(cachedConfig { buildConfig() }, ctx)
    }

    // ========== Sprint mode ==========

    /**
     * A knockback arrived. The strategy owns the condition check, the gate roll and the arm; this
     * method only reports whether it acted and hands the packet straight back to vanilla. That is
     * the whole mode: it contributes nothing to the packet stream.
     */
    private fun onSprintKnockback(ctx: VelocityContext): PlatformCommand {
        if (sprintStrategy.arm(cachedConfig { buildConfig() }, strategyState, ctx)) {
            // Two ticks of slack past the window so a normal hiccup does not cut the brace short;
            // the point is only that it cannot outlive the world it was armed in.
            braceDeadlineNanos = System.nanoTime() + (sprintHoldTicks + 2) * 50_000_000L
            markWorked()
        }
        return PlatformCommand.Pass(ctx.originalMotion)
    }

    /**
     * One tick of the brace. The key resolution is Core's (it depends on the player's *current*
     * yaw, which is why it is a per-tick call rather than part of the packet handling); this method
     * only moves the result onto EventBridge.
     */
    private fun sprintTick(player: PlayerState) {
        // No local player (world change, disconnect, title screen): the yaw and the knockback
        // source belong to the old world. Release instead of resolving keys against yaw 0.
        if (player === PlayerState.EMPTY) {
            releaseBraceKeys()
            return
        }

        val expired = System.nanoTime() > braceDeadlineNanos
        val keys = if (expired) {
            strategyState.braceTicksLeft = 0
            null
        } else {
            sprintStrategy.braceKeys(
                cachedConfig { buildConfig() }, strategyState, player.rotation.yaw
            )
        }
        if (keys == null) {
            releaseBraceKeys()
            return
        }

        // ADDITIVE claims, not direct writes: WTap/STap OWN W/S while a tap is live and beat us
        // on a shared tick (a trade), after which we resume. Writing the fields directly was
        // what let the tap and the brace silently cancel each other.
        EventBridge.claimForward(name, keys.forward)
        EventBridge.claimBack(name, keys.back)
        EventBridge.claimRight(name, keys.right)
        EventBridge.claimLeft(name, keys.left)
        braceKeysHeld = true

        if (sprintSyncServerSprint) {
            // Snapshot BEFORE claiming. claimSprint goes through the platform's sprint setter,
            // which reports into the mirror, so reading serverSprintState afterwards would read
            // our own claim back and the C0B below would never be sent.
            val serverKnewSprint = EventBridge.serverSprintState
            // ADDITIVE claim, asserted only when the game has actually cleared sprint — the same
            // condition the direct call used. Released as soon as the game agrees, so the claim
            // never outlives the reason it was made.
            if (!player.isSprinting) {
                EventBridge.claimSprint(name, true)
            } else {
                EventBridge.releaseSprint(name)
            }
            if (!serverKnewSprint) {
                EventBridge.sendEntityAction("START_SPRINTING")
            }
        }
    }

    private fun releaseBraceKeys() {
        // Released before the early return: SprintSync can be on while Mode = SprintSync (no keys).
        EventBridge.releaseSprint(name)
        // Drop the window itself, not just the keys: a mode change or a stalled tick must not leave
        // a remainder that resumes (and re-presses) the next time Sprint mode is selected.
        strategyState.braceTicksLeft = 0
        braceDeadlineNanos = 0L
        if (!braceKeysHeld) return
        EventBridge.releaseForward(name)
        EventBridge.releaseBack(name)
        EventBridge.releaseLeft(name)
        EventBridge.releaseRight(name)
        braceKeysHeld = false
    }

    /** Run the strategy (conditions + scaling) and return its command. */
    private fun reduceNormally(config: VelocityConfig, ctx: VelocityContext): PlatformCommand {
        val result = currentStrategy().execute(config, strategyState, ctx)
        // Expose whether velocity was actually modified/cancelled (for the VelocityDisplay HUD).
        val modified = result is VelocityResult.Modify || result is VelocityResult.Cancel
        EventBridge.velocityModified = modified
        // Record knockback coefficient (original vs post-reduction horizontal speed) for the
        // VelocityDisplay / KnockbackDisplay / JumpTiming HUDs.
        val origSpeed = Math.sqrt(ctx.originalMotion.x * ctx.originalMotion.x + ctx.originalMotion.z * ctx.originalMotion.z)
        val modSpeed = when (result) {
            is VelocityResult.Modify -> {
                val m = result.motion
                Math.sqrt(m.x * m.x + m.z * m.z)
            }
            is VelocityResult.Cancel -> 0.0
            else -> origSpeed // Pass/NoOp → unchanged
        }
        EventBridge.recordKnockback(ctx.originalMotion, modSpeed)
        // Diagnostic: confirm the strategy actually produced Modify (HUD color check).
        if (++velDiagCount % 10 == 0) {
            io.doppel.core.logging.CoreLogger.info(
                "[Velocity] packet result=${result.javaClass.simpleName} modified=$modified " +
                "mode=$mode hMin=$horizontalMin hMax=$horizontalMax vMin=$verticalMin vMax=$verticalMax " +
                "onlyOnHit=$onlyOnHitFrame"
            )
        }
        return mapResultToCommand(result)
    }

    // ========== Result Mapping ==========

    private fun mapResultToCommand(result: VelocityResult): PlatformCommand = when (result) {
        is VelocityResult.Modify -> { markWorked(); PlatformCommand.ModifyMotion(result.motion) }
        is VelocityResult.Cancel -> PlatformCommand.CancelPacket(result.packetHandle)
        is VelocityResult.ClickBurst -> PlatformCommand.ClickBurst(result.targetEntityId, result.times)
        is VelocityResult.Pass -> PlatformCommand.Pass(result.originalMotion)
        is VelocityResult.NoOp -> PlatformCommand.NoOp
    }

    // ========== Lifecycle ==========

    /**
     * Named reference, not an inline lambda.
     *
     * `unregisterTickListener(this::onTick)` cannot remove an anonymous `{ ... }` registered
     * earlier — a Kotlin function reference is a new object every time, and the listener list
     * matches by identity. The previous code therefore leaked one `onTick` per enable/disable
     * cycle: `pumpDelayed` ran N times per tick after N toggles.
     */
    private val tickListenerRef: (Int) -> Unit = { currentTick ->
        if (enabled) onTick(currentTick)
    }

    override fun onEnable() {
        // The gate's chain state and the Sprint brace window both live in strategyState and are
        // cleared by strategyState.reset() in onDisable.
        braceKeysHeld = false
        braceDeadlineNanos = 0L
        EventBridge.registerTickListener(tickListenerRef)
        EventBridge.registerTickListener(sprintTickListenerRef)
        EventBridge.registerVelocityListener(velocityListenerRef, EventBridge.VELOCITY_PRIORITY_REDUCE)
    }

    override fun onDisable() {
        EventBridge.unregisterVelocityListener(velocityListenerRef)
        EventBridge.unregisterTickListener(tickListenerRef)
        EventBridge.unregisterTickListener(sprintTickListenerRef)
        strategyState.reset()
        releaseBraceKeys()
    }
    /**
     * Called on every tick to drain any delayed velocity packets from the
     * strategy's delay queue (kept for the strategy interface; DELAY mode moved
     * to the standalone KnockbackDelay module), and — in Sprint mode — to run
     * one tick of the brace.
     */
    fun onTick(currentTick: Int) {
        val config = cachedConfig { buildConfig() }
        val strategy = currentStrategy()
        val results = strategy.pumpDelayed(config, strategyState, currentTick)
        for (result in results) {
            val command = mapResultToCommand(result)
            if (command is PlatformCommand.ModifyMotion) {
                EventBridge.applyMotion(command.motion)
            }
        }
    }

    /**
     * Separate tick listener for Sprint mode: it needs the [PlayerState] (for the yaw
     * the brace direction is resolved against), which the `(Int) -> Unit` overload of
     * `registerTickListener` does not carry.
     *
     * The else branch is the anti-stuck-key path. `sprintTick` is the only place that consumes the
     * window and releases the keys, and it stops being called the moment the Mode option leaves
     * "Sprint" — an option change does not disable the module, so `onDisable` never runs. Without
     * this branch, switching modes mid-window (four ticks, but a real window: the GUI is usually
     * opened right after a hit) left W/A/S/D claimed until the module was toggled off.
     */
    private val sprintTickListenerRef: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled && mode == "Sprint") {
            sprintTick(p)
        } else if (braceKeysHeld || strategyState.braceTicksLeft > 0 || braceDeadlineNanos != 0L) {
            releaseBraceKeys()
        }
    }
}
