package io.switchlite.core.safety

import io.switchlite.core.logging.CoreLogger
import io.switchlite.core.logging.ReplayLogger
import io.switchlite.core.model.ModuleMovementSample
import io.switchlite.core.model.SetbackEvent
import io.switchlite.core.model.SetbackVerdict
import io.switchlite.core.util.Vec3

/**
 * Core-layer setback circuit breaker.
 *
 * Responsibilities:
 * - Track total setback count.
 * - Attribute blame to the movement module that caused each setback.
 * - Produce a [SetbackVerdict] telling the caller what to do.
 * - Hard circuit-break at a configurable threshold (default 2).
 *
 * Design constraints:
 * - Pure Core layer. Zero MC/Forge/Fabric imports.
 * - Does NOT detect setbacks (adapter layer does that).
 * - Does NOT execute pauses/disables (caller does that).
 * - Only consumes [SetbackEvent] snapshots and produces [SetbackVerdict] decisions.
 *
 * Constitution compliance:
 * - §1 Safety: threshold=2 leaves margin before the 3rd-setback ban line.
 * - §2 Debuggability: every setback logged to ReplayLogger with blame attribution.
 * - §3 Strategy: threshold and lookback window are configurable constants.
 */
object SetbackTracker {

    // ========== Configuration ==========

    /**
     * Circuit-break threshold. At this many module-blamed setbacks,
     * the blamed module is hard-disabled.
     *
     * Default 2: the 3rd setback is typically a ban, so we stop at 2.
     */
    var circuitBreakThreshold: Int = 2

    /**
     * How many ticks to look back when searching for the responsible
     * movement module. Only samples within [currentTick - lookbackTicks, currentTick]
     * are considered.
     *
     * Default 10: covers typical anti-cheat reaction delay (0.5s at 20 TPS).
     */
    var lookbackTicks: Long = 10

    /**
     * Minimum movement magnitude to consider a sample as "non-zero".
     * Filters out floating-point noise from modules that output tiny deltas.
     *
     * Default 0.001: below this is effectively stationary.
     */
    var movementEpsilon: Double = 0.001

    // ========== State ==========

    /** Total setbacks recorded since last reset. */
    private var totalSetbacks: Int = 0

    /** Per-module blame counts since last reset. */
    private val blameCounts = mutableMapOf<String, Int>()

    /** Whether circuit-break is currently active. */
    private var circuitBroken: Boolean = false

    /** The module that triggered circuit-break (if any). */
    private var circuitBrokenModule: String? = null

    // ========== Public API ==========

    /**
     * Record a setback event and produce a verdict.
     *
     * @param event The setback snapshot (positions + recent movement samples).
     * @return A [SetbackVerdict] telling the caller what action to take.
     */
    fun recordSetback(event: SetbackEvent): SetbackVerdict {
        if (circuitBroken) {
            CoreLogger.warn("[SetbackTracker] Already circuit-broken (module=${circuitBrokenModule}), ignoring setback at tick ${event.tick}")
            return SetbackVerdict.CircuitBreak(
                moduleId = circuitBrokenModule ?: "unknown",
                blameCount = totalSetbacks
            )
        }

        totalSetbacks++

        // Log the setback
        ReplayLogger.log("setback", mapOf(
            "tick" to event.tick,
            "totalSetbacks" to totalSetbacks,
            "positionBefore" to vec3ToString(event.positionBefore),
            "positionAfter" to vec3ToString(event.positionAfter),
            "displacement" to event.displacementMagnitude,
            "sampleCount" to event.recentMovementSamples.size
        ))

        CoreLogger.info("[SetbackTracker] Setback #$totalSetbacks at tick ${event.tick} (displacement=${String.format("%.3f", event.displacementMagnitude)} blocks)")

        // Attribute blame
        val blamedModule = attributeBlame(event)

        if (blamedModule == null) {
            // No module to blame — external cause
            CoreLogger.debug("[SetbackTracker] No module blamed (external cause)")
            return SetbackVerdict.ExternalCause(
                tick = event.tick,
                totalSetbacks = totalSetbacks
            )
        }

        // Module blamed — increment blame count
        val moduleBlameCount = blameCounts.getOrDefault(blamedModule, 0) + 1
        blameCounts[blamedModule] = moduleBlameCount

        ReplayLogger.log("setback_blame", mapOf(
            "tick" to event.tick,
            "moduleId" to blamedModule,
            "blameCount" to moduleBlameCount,
            "threshold" to circuitBreakThreshold
        ))

        CoreLogger.info("[SetbackTracker] Blamed module '$blamedModule' (blame #$moduleBlameCount / threshold $circuitBreakThreshold)")

        // Check circuit-break threshold
        if (moduleBlameCount >= circuitBreakThreshold) {
            circuitBroken = true
            circuitBrokenModule = blamedModule

            ReplayLogger.log("circuit_break", mapOf(
                "tick" to event.tick,
                "moduleId" to blamedModule,
                "blameCount" to moduleBlameCount
            ))

            CoreLogger.error("[SetbackTracker] CIRCUIT BREAK: module '$blamedModule' blamed $moduleBlameCount times (threshold=$circuitBreakThreshold)")

            return SetbackVerdict.CircuitBreak(
                moduleId = blamedModule,
                blameCount = moduleBlameCount
            )
        }

        // Below threshold — blame but don't hard-disable
        return SetbackVerdict.BlameModule(
            moduleId = blamedModule,
            blameCount = moduleBlameCount,
            threshold = circuitBreakThreshold
        )
    }

    /**
     * Reset all state. Call on player death, respawn, world change, or manual recovery.
     */
    fun reset() {
        totalSetbacks = 0
        blameCounts.clear()
        circuitBroken = false
        circuitBrokenModule = null
        CoreLogger.info("[SetbackTracker] Reset (all counters cleared)")
    }

    /**
     * Get current total setback count.
     */
    fun getTotalSetbacks(): Int = totalSetbacks

    /**
     * Get blame count for a specific module.
     */
    fun getBlameCount(moduleId: String): Int = blameCounts.getOrDefault(moduleId, 0)

    /**
     * Check if circuit-break is currently active.
     */
    fun isCircuitBroken(): Boolean = circuitBroken

    /**
     * Get the module that triggered circuit-break (if any).
     */
    fun getCircuitBrokenModule(): String? = circuitBrokenModule

    /**
     * Get all blame counts (for debugging / HUD display).
     */
    fun getAllBlameCounts(): Map<String, Int> = blameCounts.toMap()

    // ========== Internal ==========

    /**
     * Attribute blame to a movement module based on recent movement samples.
     *
     * Strategy:
     * - Filter samples within the lookback window.
     * - Find samples with non-zero movement (magnitude > epsilon).
     * - If multiple modules have non-zero samples, pick the one with the largest
     *   total movement magnitude (most likely to have triggered the anti-cheat).
     * - If no non-zero samples, return null (external cause).
     *
     * @return The module ID to blame, or null if no module is responsible.
     */
    private fun attributeBlame(event: SetbackEvent): String? {
        val lookbackStart = event.tick - lookbackTicks

        // Filter samples within lookback window
        val relevantSamples = event.recentMovementSamples.filter { sample ->
            sample.tick >= lookbackStart && sample.tick <= event.tick
        }

        if (relevantSamples.isEmpty()) {
            return null
        }

        // Group by module and sum movement magnitudes
        val moduleMagnitudes = mutableMapOf<String, Double>()
        for (sample in relevantSamples) {
            val magnitude = sample.movement.length()
            if (magnitude > movementEpsilon) {
                moduleMagnitudes[sample.moduleId] =
                    moduleMagnitudes.getOrDefault(sample.moduleId, 0.0) + magnitude
            }
        }

        if (moduleMagnitudes.isEmpty()) {
            // All samples were zero-magnitude — no module to blame
            return null
        }

        // Pick the module with the largest total movement
        return moduleMagnitudes.maxByOrNull { it.value }?.key
    }

    /**
     * Format a Vec3 for logging (avoid pulling in platform-specific toString).
     */
    private fun vec3ToString(v: Vec3): String {
        return "(${String.format("%.3f", v.x)}, ${String.format("%.3f", v.y)}, ${String.format("%.3f", v.z)})"
    }
}
