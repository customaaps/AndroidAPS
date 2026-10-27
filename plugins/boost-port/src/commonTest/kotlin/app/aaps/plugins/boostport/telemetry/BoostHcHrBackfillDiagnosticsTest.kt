package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostHcHrBackfillDiagnosticsTest {
    private val audit = BoostHcHrBackfillDiagnostics
    private val minute = 60_000L
    private val hour = 60L * minute
    private val now = 200L * hour

    private fun sample(
        time: Long = now - 10L * hour,
        bpm: Double = 62.0,
        device: String = "Garmin Connect",
    ) = BoostHcHrBackfillDiagnostics.Sample(time, bpm, device)

    private fun preview(
        records: List<BoostHcHrBackfillDiagnostics.Sample>,
        liveMinutes: Set<Long> = emptySet(),
        ingested: Set<Long> = emptySet(),
        at: Long = now,
        lookback: Long = audit.DEFAULT_LOOKBACK_MS,
    ) = audit.preview(records, liveMinutes, ingested, at, lookback)

    @Test fun backdatedGarminMorningBatchFallsInsideEighteenHourResweep() {
        val record = sample(now - 16L * hour)
        val result = preview(listOf(record))!!
        assertEquals(listOf(record), result.candidates)
        assertEquals(setOf(record.timestampMs), result.candidateTimestampsMs)
    }

    @Test fun lastPollTimeDoesNotExcludeOlderBackdatedSample() {
        val old = sample(now - 12L * hour)
        val recentlyIngested = now - 5L * minute
        val result = preview(listOf(old), ingested = setOf(recentlyIngested))!!
        assertEquals(listOf(old), result.candidates)
        assertFalse(recentlyIngested in result.candidateTimestampsMs)
    }

    @Test fun liveFeedWinsItsMinuteOverHealthConnectGapFill() {
        val timestamp = now - 8L * hour
        val result = preview(
            listOf(sample(timestamp)),
            liveMinutes = setOf(timestamp / minute),
        )!!
        assertTrue(result.candidates.isEmpty())
        assertEquals(1, result.existingLiveMinutesSkipped)
        assertEquals(0, result.sameMinuteDuplicatesSkipped)
    }

    @Test fun emptyExistingFeedAllowsLegitimateBackfill() {
        val record = sample(now - 8L * hour)
        assertEquals(listOf(record), preview(listOf(record))!!.candidates)
    }

    @Test fun twoDifferentWritersWithinOneMinuteYieldOneDeterministicSample() {
        val timestamp = now - 8L * hour
        val first = sample(timestamp + 30_000L, bpm = 90.0, device = "Z")
        val second = sample(timestamp, bpm = 72.0, device = "A")
        val result = preview(listOf(first, second))!!
        assertEquals(listOf(second), result.candidates)
        assertEquals(1, result.sameMinuteDuplicatesSkipped)
    }

    @Test fun duplicateIdenticalTimestampCannotAppearTwice() {
        val original = sample()
        val result = preview(listOf(original, original))!!
        assertEquals(1, result.candidates.size)
        assertEquals(1, result.alreadyIngestedTimestampsSkipped)
    }

    @Test fun confirmedPreviouslyIngestedSampleIsNotProposedAgain() {
        val original = sample()
        val next = sample(original.timestampMs + minute, bpm = 65.0)
        val result = preview(listOf(original, next),
            ingested = setOf(original.timestampMs))!!
        assertEquals(listOf(next), result.candidates)
        assertEquals(1, result.alreadyIngestedTimestampsSkipped)
    }

    @Test fun invalidBpmAndBlankDeviceAreRejectedAsSamples() {
        val base = now - 10L * hour
        val records = listOf(
            sample(base, bpm = Double.NaN),
            sample(base + minute, bpm = Double.POSITIVE_INFINITY),
            sample(base + 2 * minute, bpm = 0.0),
            sample(base + 3 * minute, device = "  "),
            sample(base + 4 * minute, bpm = 70.0),
        )
        val result = preview(records)!!
        assertEquals(4, result.invalidSamplesSkipped)
        assertEquals(listOf(records.last()), result.candidates)
    }

    @Test fun windowStartIsInclusiveButOlderAndFutureSamplesAreRejected() {
        val cutoff = now - audit.DEFAULT_LOOKBACK_MS
        val inWindow = sample(cutoff)
        val out = preview(listOf(
            sample(cutoff - 1L), inWindow, sample(now + 1L),
        ))!!
        assertEquals(listOf(inWindow), out.candidates)
        assertEquals(2, out.outsideWindowSkipped)
    }

    @Test fun samplesAfterNowDoNotBecomeFreshEvidence() {
        val result = preview(listOf(sample(now + hour)))!!
        assertTrue(result.candidates.isEmpty())
        assertEquals(1, result.outsideWindowSkipped)
    }

    @Test fun previewDoesNotMutatePriorSuccessfulIngestionOrLiveMinuteSets() {
        val existing = setOf(now - 4L * minute)
        val liveMinutes = setOf((now - 2L * minute) / minute)
        val candidate = sample(now - 3L * minute)
        val result = preview(listOf(candidate), liveMinutes, existing)!!
        assertEquals(listOf(candidate), result.candidates)
        assertEquals(setOf(now - 4L * minute), existing)
        assertEquals(setOf((now - 2L * minute) / minute), liveMinutes)
    }

    @Test fun emptyHealthConnectResponseIsEmptyNotZeroHeartRate() {
        val result = preview(emptyList())!!
        assertTrue(result.candidates.isEmpty())
        assertTrue(result.candidateTimestampsMs.isEmpty())
    }

    @Test fun malformedClockAndTooLongLookbackFailClosed() {
        assertNull(preview(emptyList(), at = -1L))
        assertNull(preview(emptyList(), at = 0L))
        assertNull(preview(emptyList(), lookback = 0L))
        assertNull(preview(emptyList(), lookback = 49L * hour))
        assertNull(preview(emptyList(), liveMinutes = setOf(-1L)))
        assertNull(preview(emptyList(), ingested = setOf(now + 1L)))
    }

    @Test fun excessiveUntrustedBatchIsNotProcessed() {
        val record = sample()
        assertNull(preview(List(50_001) { record }))
    }
}
