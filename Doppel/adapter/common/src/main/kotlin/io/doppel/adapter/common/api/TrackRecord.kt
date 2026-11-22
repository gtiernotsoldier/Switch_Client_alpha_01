package io.doppel.adapter.common.api

/**
 * TrackRecord — the client's own "what the server has seen" trail.
 *
 * A ring buffer of the player's last sent positions, plus one optional **anchor** (the
 * Mark & Recall pin). Everything Izanagi-class needs is here: the fossil the server is
 * looking at, how deep the reachable past goes, and which frame a recall would target.
 *
 * ## Who feeds it, and what "sent" means
 *
 * The buffer must only ever contain positions the server could actually have seen. That
 * property is maintained by convention, not by locking: **a module must not push frames
 * while it is holding its own packets** (Kamui during a phase, Izanagi during a replay).
 * While nothing is held, each tick's reported position reaches the server one packet
 * later, so the tick cadence *is* the wire cadence — pushing from the tick listener is
 * then exact to one tick. During a hold the local position diverges from the server's
 * view, which is precisely why those frames are skipped: the buffer freezes at the
 * fossil, which is exactly what the desync readout wants to show.
 *
 * ## The anchor has a fuse
 *
 * `mark()` pins the newest frame. The pin does not survive the ring overwriting it — an
 * anchor older than [DEFAULT_CAPACITY] ticks silently expires and [anchor] returns null.
 * Storage is wide on purpose (a few hundred frames cost nothing), but **usable depth is
 * decided by the server's tolerance, not by the buffer**: recall logic must check the
 * anchor's age against the current environment, never against capacity.
 *
 * ## Threads
 *
 * Written from the tick listener (one writer), read from the tick listener and the HUD.
 * No locking — the worst a torn read can do is show a frame one tick stale.
 */
object TrackRecord {

    data class Frame(
        val tick: Int,
        val x: Double, val y: Double, val z: Double,
        val yaw: Float, val pitch: Float,
        val onGround: Boolean
    )

    /** Capacity in ticks — ~6 s at 20 Hz. Wide by design; see the class comment on usable depth. */
    const val DEFAULT_CAPACITY = 120

    private val frames = ArrayDeque<Frame>(DEFAULT_CAPACITY + 1)

    /** The tick of the pinned frame, or null when no live anchor exists. */
    @Volatile private var anchorTick: Int? = null

    /** Record one server-seen frame. Oldest frames fall out of the ring past [DEFAULT_CAPACITY]. */
    fun push(frame: Frame) {
        if (frames.size >= DEFAULT_CAPACITY) frames.removeFirst()
        frames.addLast(frame)
        // The anchor is only as alive as the frame it pins. When the ring's oldest frame has
        // moved past the pinned tick, the pinned frame was evicted — the fuse has burned down.
        val pinned = anchorTick
        if (pinned != null && frames.isNotEmpty() && frames.first().tick > pinned) {
            anchorTick = null
        }
    }

    /** Pin the newest frame as the recall anchor. Returns false when the trail is empty. */
    fun mark(): Boolean {
        val last = frames.lastOrNull() ?: return false
        anchorTick = last.tick
        return true
    }

    /** The pinned frame, or null when there is no anchor (never marked, or the fuse expired). */
    fun anchor(): Frame? {
        val pinned = anchorTick ?: return null
        return frames.lastOrNull { it.tick == pinned }
    }

    /** Ticks since the anchor was pinned; -1 when no live anchor. */
    fun anchorAge(tick: Int): Int = anchorTick?.let { pinned -> tick - pinned } ?: -1

    /** The newest server-seen frame — the fossil a receiver would hit right now. */
    fun lastSent(): Frame? = frames.lastOrNull()

    fun clearAnchor() { anchorTick = null }

    fun clear() {
        frames.clear()
        anchorTick = null
    }

    fun size(): Int = frames.size
}
