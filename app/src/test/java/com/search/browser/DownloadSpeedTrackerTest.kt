package com.search.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadSpeedTrackerTest {
    @Test fun calculatesBytesPerSecondFromReceivedDeltaAndElapsedTime() {
        val tracker = DownloadSpeedTracker()
        assertEquals(0L, tracker.update(0L, 1_000L))
        assertEquals(1_048_576L, tracker.update(2_097_152L, 3_000L))
    }

    @Test fun trailingWindowReducesAHighInitialBurstToTheObservedAverage() {
        val tracker = DownloadSpeedTracker(windowMillis = 4_000L)
        tracker.update(0L, 0L)
        assertEquals(4_194_304L, tracker.update(4_194_304L, 1_000L))
        assertEquals(262_144L, tracker.update(5_242_880L, 5_000L))
    }

    @Test fun resetsWhenReceivedBytesMoveBackwardsAfterResume() {
        val tracker = DownloadSpeedTracker()
        tracker.update(2_000L, 1_000L)
        assertEquals(0L, tracker.update(500L, 2_000L))
        assertEquals(1_000L, tracker.update(1_500L, 3_000L))
    }

    @Test fun ignoresNonMonotonicTimestampsAndNegativeValues() {
        val tracker = DownloadSpeedTracker()
        tracker.update(1_000L, 1_000L)
        assertEquals(0L, tracker.update(2_000L, 900L))
        assertEquals(0L, tracker.update(-1L, 2_000L))
    }

    @Test fun reportsZeroForAStalledOrTooShortSampleWindow() {
        val tracker = DownloadSpeedTracker(minimumSampleMillis = 1_000L)
        tracker.update(10_000L, 1_000L)
        assertEquals(0L, tracker.update(10_000L, 1_500L))
        assertEquals(0L, tracker.update(10_000L, 2_000L))
    }
}
