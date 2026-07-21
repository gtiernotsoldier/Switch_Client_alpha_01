package io.switchlite.core.option

/**
 * Timing configuration for delay-based execution.
 */
data class TimingOptions(
    val delayTicksMin: Int = 0,
    val delayTicksMax: Int = 0,
    val delayMsMin: Long = 0L,
    val delayMsMax: Long = 0L
) {
    init {
        require(delayTicksMin <= delayTicksMax) { "delayTicksMin must be <= delayTicksMax" }
        require(delayMsMin <= delayMsMax) { "delayMsMin must be <= delayMsMax" }
        require(delayTicksMin >= 0) { "delayTicks must be non-negative" }
        require(delayMsMin >= 0L) { "delayMs must be non-negative" }
    }
    
    fun sampleDelayTicks(): Int {
        return if (delayTicksMin == delayTicksMax) {
            delayTicksMin
        } else {
            kotlin.random.Random.nextInt(delayTicksMin, delayTicksMax + 1)
        }
    }
    
    fun sampleDelayMs(): Long {
        return if (delayMsMin == delayMsMax) {
            delayMsMin
        } else {
            delayMsMin + kotlin.random.Random.nextLong(delayMsMax - delayMsMin + 1)
        }
    }
}
