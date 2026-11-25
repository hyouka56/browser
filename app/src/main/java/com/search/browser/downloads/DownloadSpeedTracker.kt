package com.search.browser.downloads

/** Calculates a trailing average from observed byte-count changes and monotonic time. */
internal class DownloadSpeedTracker(
    private val windowMillis: Long = 4_000L,
    private val minimumSampleMillis: Long = 750L
) {
    private data class Sample(val receivedBytes: Long, val elapsedRealtimeMillis: Long)

    private val samples = mutableListOf<Sample>()

    fun update(receivedBytes: Long, elapsedRealtimeMillis: Long): Long {
        if (receivedBytes < 0L || elapsedRealtimeMillis < 0L) return 0L

        val last = samples.lastOrNull()
        if (last != null && (
                elapsedRealtimeMillis < last.elapsedRealtimeMillis ||
                    receivedBytes < last.receivedBytes
                )
        ) {
            samples.clear()
        } else if (last != null && elapsedRealtimeMillis == last.elapsedRealtimeMillis) {
            samples.removeAt(samples.lastIndex)
        }

        samples.add(Sample(receivedBytes, elapsedRealtimeMillis))
        while (samples.size > 2 &&
            elapsedRealtimeMillis - samples[1].elapsedRealtimeMillis >= windowMillis
        ) {
            samples.removeAt(0)
        }

        val first = samples.first()
        val latest = samples.last()
        val elapsed = latest.elapsedRealtimeMillis - first.elapsedRealtimeMillis
        val received = latest.receivedBytes - first.receivedBytes
        if (elapsed < minimumSampleMillis || received <= 0L) return 0L

        return (received.toDouble() * 1_000.0 / elapsed.toDouble())
            .toLong()
            .coerceAtLeast(0L)
    }

    fun reset() = samples.clear()
}
