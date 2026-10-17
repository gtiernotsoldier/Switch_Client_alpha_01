package io.doppel.core.strategy.movement

/**
 * The keys a SOCD pass wants the game to treat as released.
 *
 * "Suppress" rather than "press the other one": vanilla already reports both keys as down, and the
 * only way to make one of them win without inventing input is to take the other away.
 */
data class SocdCorrection(
    val suppressForward: Boolean = false,
    val suppressBack: Boolean = false,
    val suppressLeft: Boolean = false,
    val suppressRight: Boolean = false
) {
    val any: Boolean get() = suppressForward || suppressBack || suppressLeft || suppressRight

    companion object {
        val NONE = SocdCorrection()
    }
}

/**
 * SOCD resolution for one axis pair — the rule gaming keyboards sell as "Snap Tap" or "Null binds".
 *
 * Vanilla 1.8 (`MovementInputFromOptions.updatePlayerMoveState`) sums the keys:
 *
 *     moveForward += (forward ? 1 : 0) - (back ? 1 : 0)
 *
 * so holding A and D together yields exactly 0 and the player stops strafing. SOCD cleaning instead
 * lets the **most recently pressed** key win, which makes a direction change instantaneous — no
 * neutral tick where neither key is down.
 *
 * ── What this can and cannot know
 *
 * Key state is sampled once per tick (50 ms), so "most recent" means "the later sample". That
 * covers the case the feature exists for — holding A and then adding D — because the two presses
 * land in different samples. Two keys pressed inside the *same* sample are ambiguous: the true
 * order is unknowable at this resolution, and guessing would make the result depend on which key
 * this class happened to check first. Those share a sequence number and therefore stay tied until
 * one of them is released and pressed again — the module does nothing rather than something
 * arbitrary.
 *
 * Stateful by design — the order lives in the edges it has seen, not in the current snapshot.
 * Not thread-safe; it is driven from one tick listener.
 */
class SocdResolver {

    /** One sequence value per sample, shared by every key that rose in it. */
    private var counter = 0L

    private var prevForward = false
    private var prevBack = false
    private var prevLeft = false
    private var prevRight = false

    private var forwardSeq = 0L
    private var backSeq = 0L
    private var leftSeq = 0L
    private var rightSeq = 0L

    fun reset() {
        counter = 0L
        prevForward = false; prevBack = false; prevLeft = false; prevRight = false
        forwardSeq = 0L; backSeq = 0L; leftSeq = 0L; rightSeq = 0L
    }

    /**
     * Feed one sample of the **physical** keys and get back what should be taken away.
     *
     * Physical, not effective: the caller's own suppression is applied to the game's key state, and
     * reading that back would look like the player released the key — the correction would cancel
     * itself one tick later and oscillate.
     */
    fun resolve(forward: Boolean, back: Boolean, left: Boolean, right: Boolean): SocdCorrection {
        val wasForward = prevForward
        val wasBack = prevBack
        val wasLeft = prevLeft
        val wasRight = prevRight

        prevForward = forward
        prevBack = back
        prevLeft = left
        prevRight = right

        // One tick, one sequence value: keys that rise together stay tied, which is the only
        // honest answer when the sample cannot tell them apart.
        counter++
        if (forward && !wasForward) forwardSeq = counter
        if (back && !wasBack) backSeq = counter
        if (left && !wasLeft) leftSeq = counter
        if (right && !wasRight) rightSeq = counter

        val suppressForward = forward && back && forwardSeq < backSeq
        val suppressBack = forward && back && backSeq < forwardSeq
        val suppressLeft = left && right && leftSeq < rightSeq
        val suppressRight = left && right && rightSeq < leftSeq

        if (!suppressForward && !suppressBack && !suppressLeft && !suppressRight) return SocdCorrection.NONE
        return SocdCorrection(suppressForward, suppressBack, suppressLeft, suppressRight)
    }
}
