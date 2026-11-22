package io.doppel.core.strategy.player

import java.util.ArrayDeque

/**
 * Izanagi's Mark & Recall machine — the **oracle**.
 *
 * The live path is `strategy::izanagi` in `core-rs`; `IzanagiParityTest`
 * replays the golden fixture through this object to prove the two agree.
 * Nothing in the shipped agent refers to it — `:core` is not a dependency of
 * any runtime module.
 *
 * The JVM keeps only judgements and platform I/O: "can the server see this
 * position" (false while Kamui holds the connection), the corridor check, the
 * key edges, and the position write. Everything stateful lives here: the trail
 * ring, the anchor pin with its eviction fuse, the walk list, the stall counter
 * and the cooldown.
 *
 * Ported branch for branch from the Rust machine, including the branch ORDER
 * that is part of the behaviour: the trail is fed before the machine reads the
 * ring (a mark on the same tick pins the frame that tick produced), the recall
 * tick starts but does not advance, the safety overrides on a live replay run
 * before the corridor check, and the walk list is built newest-first with the
 * anchor frame as the last step. There is no randomness — no RNG stream.
 */
object IzanagiLogic {

    const val CAPACITY = 120

    const val STATE_IDLE = 0
    const val STATE_MARKED = 1
    const val STATE_REPLAY = 2
    const val STATE_COOLDOWN = 3

    const val FLAG_ADVANCE = 1
    const val FLAG_STARTED = 2
    const val FLAG_ABORTED = 4
    const val FLAG_DONE = 8

    data class Frame(
        val tick: Int,
        val x: Double,
        val y: Double,
        val z: Double,
        val onGround: Boolean
    )

    data class State(
        val frames: ArrayDeque<Frame> = ArrayDeque(CAPACITY + 1),
        val anchorTick: Int? = null,
        val state: Int = STATE_IDLE,
        /** The ticks to walk back to, in order (newest-first, anchor last). */
        val walk: ArrayDeque<Int> = ArrayDeque(),
        val stall: Int = 0,
        val cooldown: Int = 0,
        val recalls: Int = 0
    )

    data class Input(
        val feed: Boolean,
        val tickClock: Int,
        val x: Double,
        val y: Double,
        val z: Double,
        val onGround: Boolean,
        val markEdge: Boolean,
        val recallEdge: Boolean,
        val corridorOk: Boolean,
        val guiOpen: Boolean,
        val dead: Boolean,
        val kamuiBusy: Boolean,
        val maxDepth: Int,
        val stallCap: Int,
        val cooldownTicks: Int
    )

    data class Answer(
        val state: Int,
        val flags: Int,
        val remaining: Int,
        val x: Double,
        val y: Double,
        val z: Double
    )

    data class Decision(val answer: Answer, val state: State)

    private fun pushFrame(s: State, frame: Frame): State {
        var out = s
        if (out.frames.size >= CAPACITY) out.frames.removeFirst()
        out.frames.addLast(frame)
        val front = out.frames.peekFirst()
        val pinned = out.anchorTick
        if (front != null && pinned != null && front.tick > pinned) {
            out = out.copy(anchorTick = null)
            if (out.state == STATE_MARKED) out = out.copy(state = STATE_IDLE)
        }
        return out
    }

    private fun abortReplay(s: State): State = s.copy(
        walk = ArrayDeque(),
        anchorTick = null,
        stall = 0,
        state = STATE_COOLDOWN
    )

    fun tick(state: State, input: Input): Decision {
        var s = state

        // ---- The trail: feed only what the server can actually see ----
        if (input.feed) {
            s = pushFrame(
                s,
                Frame(input.tickClock, input.x, input.y, input.z, input.onGround)
            )
        }

        // ---- A mark pins the newest frame (IDLE or MARKED: overwrite) ----
        if (input.markEdge && s.state != STATE_REPLAY && s.state != STATE_COOLDOWN && !input.kamuiBusy) {
            val newest = s.frames.peekLast()
            if (newest != null) {
                s = s.copy(anchorTick = newest.tick, state = STATE_MARKED)
            }
        }

        // ---- A recall starts the replay (starts, does not advance) ----
        var flags = 0
        if (input.recallEdge && s.state == STATE_MARKED && !input.kamuiBusy &&
            !input.guiOpen && !input.dead
        ) {
            val newest = s.frames.peekLast()
            val pinned = s.anchorTick
            var refused = true
            if (newest != null && pinned != null) {
                val age = newest.tick - pinned
                var anchorIdx = -1
                var i = 0
                for (f in s.frames) {
                    if (f.tick == pinned) anchorIdx = i
                    i++
                }
                if (anchorIdx >= 0 && anchorIdx != s.frames.size - 1 && age <= input.maxDepth) {
                    // The walk: anchor..newest, drop the newest, reversed.
                    val ticks = ArrayList<Int>()
                    var j = 0
                    for (f in s.frames) {
                        if (j >= anchorIdx) ticks.add(f.tick)
                        j++
                    }
                    ticks.removeAt(ticks.size - 1)
                    val walk = ArrayDeque<Int>()
                    for (k in ticks.indices.reversed()) walk.addLast(ticks[k])
                    s = s.copy(walk = walk, stall = 0, recalls = s.recalls + 1, state = STATE_REPLAY)
                    refused = false
                }
            }
            if (!refused) {
                return Decision(
                    Answer(STATE_REPLAY, FLAG_STARTED, s.walk.size, 0.0, 0.0, 0.0),
                    s
                )
            }
        }

        // ---- The replay: one stored frame per tick, corridor-checked ----
        if (s.state == STATE_REPLAY) {
            when {
                input.dead || input.guiOpen -> {
                    // Stop where the walk is: no snap, no synthetic jump.
                    s = abortReplay(s)
                    flags = flags or FLAG_ABORTED
                }
                !input.corridorOk -> {
                    s = s.copy(stall = s.stall + 1)
                    if (s.stall >= input.stallCap) {
                        s = abortReplay(s)
                        flags = flags or FLAG_ABORTED
                    }
                    // else: wait for the corridor to clear.
                }
                else -> {
                    s = s.copy(stall = 0)
                    val t = s.walk.pollFirst()
                    var hit: Frame? = null
                    for (f in s.frames) if (f.tick == t) hit = f
                    if (t != null && hit != null) {
                        flags = flags or FLAG_ADVANCE
                        s = if (s.walk.isEmpty()) {
                            s.copy(
                                state = STATE_COOLDOWN,
                                anchorTick = null,
                                cooldown = 0
                            )
                        } else s
                        if (s.walk.isEmpty()) flags = flags or FLAG_DONE
                        return Decision(
                            Answer(s.state, flags, s.walk.size, hit.x, hit.y, hit.z),
                            s
                        )
                    } else {
                        // The walk target vanished — fail closed.
                        s = abortReplay(s)
                        flags = flags or FLAG_ABORTED
                    }
                }
            }
        }

        // ---- Cooldown ----
        if (s.state == STATE_COOLDOWN) {
            s = s.copy(cooldown = s.cooldown + 1)
            if (s.cooldown >= input.cooldownTicks) {
                s = s.copy(state = STATE_IDLE, cooldown = 0)
            }
        }

        return Decision(Answer(s.state, flags, s.walk.size, 0.0, 0.0, 0.0), s)
    }
}
