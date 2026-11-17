package org.framegen.universal.engine
data class FramePresentationSchedule(val midpointDelayMs: Long, val holdDurationMs: Long)
object FramePresentationScheduler {
    fun forPair(sourceIntervalNs: Long, displayIntervalNs: Long): FramePresentationSchedule? {
        if (sourceIntervalNs <= 0L || displayIntervalNs <= 0L) return null
        if (sourceIntervalNs.toDouble() < displayIntervalNs.toDouble() * 1.75) return null
        if (sourceIntervalNs.toDouble() > displayIntervalNs.toDouble() * 3.0) return null
        val halfNs = (sourceIntervalNs / 2L).coerceAtLeast(1L)
        val halfMs = ((halfNs + 999_999L) / 1_000_000L).coerceAtLeast(1L)
        return FramePresentationSchedule(halfMs, halfMs)
    }
}
