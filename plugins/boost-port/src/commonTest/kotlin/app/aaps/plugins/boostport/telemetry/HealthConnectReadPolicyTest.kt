package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthConnectReadPolicyTest {
    private val policy = HealthConnectReadPolicy

    @Test fun missingSdkDoesNotClaimReadAccess() {
        assertEquals(HealthConnectReadPolicy.Access.SDK_UNAVAILABLE, policy.access(false, true))
    }

    @Test fun missingReadPermissionIsExplicit() {
        assertEquals(HealthConnectReadPolicy.Access.PERMISSION_REQUIRED, policy.access(true, false))
        assertEquals(HealthConnectReadPolicy.Access.READABLE, policy.access(true, true))
    }

    @Test fun absentHeartRateDataRemainsAbsent() {
        assertTrue(policy.heartRateBackfillCandidates(emptyList(), emptySet(), 100_000L, 60_000L).isEmpty())
    }

    @Test fun existingLiveHeartRateMinuteWinsOverHealthConnect() {
        val result = policy.heartRateBackfillCandidates(
            listOf(HealthConnectReadPolicy.HeartRateSample("HealthConnect", 120_500L, 75.0)),
            setOf(2L), 130_000L, 60_000L,
        )
        assertTrue(result.isEmpty())
    }

    @Test fun rejectsFutureOutOfWindowAndInvalidHeartRateSamples() {
        val result = policy.heartRateBackfillCandidates(
            listOf(
                HealthConnectReadPolicy.HeartRateSample("HC", 199_000L, 81.0),
                HealthConnectReadPolicy.HeartRateSample("HC", 201_000L, 81.0),
                HealthConnectReadPolicy.HeartRateSample("HC", 50_000L, 81.0),
                HealthConnectReadPolicy.HeartRateSample("HC", 180_000L, Double.NaN),
                HealthConnectReadPolicy.HeartRateSample("HC", 181_000L, Double.POSITIVE_INFINITY),
                HealthConnectReadPolicy.HeartRateSample("", 182_000L, 73.0),
                HealthConnectReadPolicy.HeartRateSample("HC", 183_000L, -1.0),
            ), emptySet(), 200_000L, 100_000L,
        )
        assertEquals(listOf(199_000L), result.map { it.timestampMs })
    }

    @Test fun healthConnectDuplicatesFromMultipleSourcesAreNotDoubleCounted() {
        val result = policy.heartRateBackfillCandidates(
            listOf(
                HealthConnectReadPolicy.HeartRateSample("Writer B", 122_000L, 73.0),
                HealthConnectReadPolicy.HeartRateSample("Writer A", 121_000L, 72.0),
            ), emptySet(), 140_000L, 60_000L,
        )
        assertEquals(1, result.size)
        assertEquals("Writer A", result.single().device)
    }

    @Test fun stepRecordsPreserveDataOriginAndDoNotFabricateMissingDays() {
        assertTrue(policy.stepIntervals(emptyList(), 120_000L, 60_000L).isEmpty())
        val result = policy.stepIntervals(
            listOf(
                HealthConnectReadPolicy.StepInterval("Garmin", 70_000L, 80_000L, 120L),
                HealthConnectReadPolicy.StepInterval("Phone", 70_000L, 80_000L, 35L),
            ), 120_000L, 60_000L,
        )
        assertEquals(2, result.size)
        assertEquals(setOf("Garmin", "Phone"), result.map { it.dataOrigin }.toSet())
    }

    @Test fun stepRecordsRejectInvalidRangesNegativeAndFutureCounts() {
        val result = policy.stepIntervals(
            listOf(
                HealthConnectReadPolicy.StepInterval("HC", 100_000L, 110_000L, 10L),
                HealthConnectReadPolicy.StepInterval("HC", 100_000L, 110_000L, 10L),
                HealthConnectReadPolicy.StepInterval("HC", 130_000L, 140_000L, 30L),
                HealthConnectReadPolicy.StepInterval("HC", 140_000L, 130_000L, 10L),
                HealthConnectReadPolicy.StepInterval("HC", 105_000L, 110_000L, -5L),
                HealthConnectReadPolicy.StepInterval("", 105_000L, 110_000L, 7L),
            ), 120_000L, 40_000L,
        )
        assertEquals(1, result.size)
        assertEquals(10L, result.single().stepCount)
    }

    @Test fun invalidClockOrLookbackYieldsNoCandidates() {
        val records = listOf(HealthConnectReadPolicy.HeartRateSample("HC", 10L, 60.0))
        assertTrue(policy.heartRateBackfillCandidates(records, emptySet(), -1L, 100L).isEmpty())
        assertTrue(policy.heartRateBackfillCandidates(records, emptySet(), 100L, 0L).isEmpty())
        assertTrue(policy.stepIntervals(emptyList(), -1L, 100L).isEmpty())
    }
}
