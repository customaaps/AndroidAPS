package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepWakeEvidenceDiagnosticsTest {
    private val diagnostic = SleepWakeEvidenceDiagnostics
    private val start = 1_700_000_000_000L
    private val minute = 60_000L

    private fun record(
        prior: SleepWakeEvidenceDiagnostics.History,
        minutes: Long,
        steps: Int?,
    ) = diagnostic.record(prior, start + minutes * minute, steps)!!

    @Test fun smallNocturnalMovementDoesNotClearWakeThreshold() {
        val history = record(record(SleepWakeEvidenceDiagnostics.History(), 0L, 100),
            10L, 130)
        val evidence = diagnostic.evidence(history, start + 10 * minute)!!
        assertEquals(30, evidence.growthSteps)
        assertFalse(evidence.clearsHrAssistedStepThreshold)
        assertFalse(evidence.clearsStrongStepThreshold)
    }

    @Test fun lumpedWearUploadCountsAsPositiveStepGrowth() {
        val baseline = record(SleepWakeEvidenceDiagnostics.History(), 0L, 0)
        val jump = record(baseline, 30L, 1326)
        val evidence = diagnostic.evidence(jump, start + 30 * minute)!!
        assertEquals(1326, evidence.growthSteps)
        assertTrue(evidence.clearsHrAssistedStepThreshold)
        assertTrue(evidence.clearsStrongStepThreshold)
    }

    @Test fun clockResetAndSensorSourceSwitchDoNotProduceFalseStepSpikes() {
        var history = record(SleepWakeEvidenceDiagnostics.History(), 0L, 6000)
        history = record(history, 10L, 6050)
        history = record(history, 20L, 20)
        history = record(history, 30L, 90)
        assertEquals(120, diagnostic.evidence(history, start + 30 * minute)!!.growthSteps)
    }

    @Test fun retainsOneOlderAnchorForFirstInWindowIncrement() {
        var history = record(SleepWakeEvidenceDiagnostics.History(), 0L, 100)
        history = record(history, 45L, 140)
        history = record(history, 70L, 240)
        val e = diagnostic.evidence(history, start + 70 * minute)!!
        assertEquals(140, e.growthSteps)
        assertEquals(3, history.samples.size)
        history = record(history, 110L, 300)
        assertEquals(3, history.samples.size)
        assertEquals(160, diagnostic.evidence(history, start + 110 * minute)!!.growthSteps)
    }

    @Test fun observationsBeforeCutoffDoNotCountAsNewMovement() {
        var history = record(SleepWakeEvidenceDiagnostics.History(), 0L, 100)
        history = record(history, 30L, 300)
        history = record(history, 90L, 350)
        assertEquals(50, diagnostic.evidence(history, start + 90 * minute)!!.growthSteps)
    }

    @Test fun missingStepFeedDoesNotCreateArtificialZeroSample() {
        val history = record(SleepWakeEvidenceDiagnostics.History(), 0L, 400)
        assertEquals(history, record(history, 1L, null))
        assertEquals(0, diagnostic.evidence(history, start + minute)!!.growthSteps)
    }

    @Test fun duplicateTimestampUpdatesLatestSnapshotInsteadOfInflatingCount() {
        var history = record(SleepWakeEvidenceDiagnostics.History(), 0L, 100)
        history = record(history, 10L, 150)
        val same = record(history, 10L, 155)
        assertEquals(2, same.samples.size)
        assertEquals(55, diagnostic.evidence(same, start + 10 * minute)!!.growthSteps)
    }

    @Test fun strongAndHrAssistedThresholdsAreIndependent() {
        var history = record(SleepWakeEvidenceDiagnostics.History(), 0L, 0)
        history = record(history, 10L, 100)
        val weak = diagnostic.evidence(history, start + 10 * minute)!!
        assertTrue(weak.clearsHrAssistedStepThreshold)
        assertFalse(weak.clearsStrongStepThreshold)
        history = record(history, 20L, 250)
        assertTrue(diagnostic.evidence(history, start + 20 * minute)!!
            .clearsStrongStepThreshold)
    }

    @Test fun tenMinuteHrSustainScalesWithObservedCycleCadence() {
        assertEquals(2, diagnostic.hrSustainCycles(5.0))
        assertEquals(10, diagnostic.hrSustainCycles(1.0))
        assertEquals(20, diagnostic.hrSustainCycles(0.5))
        assertEquals(2, diagnostic.hrSustainCycles(null))
    }

    @Test fun invalidClockSpacingAndHrSampleCountsFailClosed() {
        assertNull(diagnostic.hrSustainCycles(Double.NaN))
        assertNull(diagnostic.hrSustainCycles(0.0))
        assertNull(diagnostic.hrSustainCycles(Double.POSITIVE_INFINITY))
        assertNull(diagnostic.reliableFreshHr(-1))
        assertFalse(diagnostic.reliableFreshHr(2)!!)
        assertTrue(diagnostic.reliableFreshHr(3)!!)
    }

    @Test fun reversedTimestampOrCorruptCountsCannotBeRecorded() {
        val history = record(SleepWakeEvidenceDiagnostics.History(), 10L, 200)
        assertNull(diagnostic.record(history, start + 9 * minute, 210))
        assertNull(diagnostic.record(history, start + 11 * minute, -1))
        assertNull(diagnostic.evidence(history, start + 9 * minute))
    }

    @Test fun sumOfLargePositiveDeltasSaturatesRatherThanOverflowing() {
        var history = record(SleepWakeEvidenceDiagnostics.History(), 0L, 0)
        history = record(history, 10L, Int.MAX_VALUE)
        history = record(history, 20L, 0)
        history = record(history, 30L, Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE,
            diagnostic.evidence(history, start + 30 * minute)!!.growthSteps)
    }

    @Test fun emptyHistoryIsNeutralNotProofOfWakefulness() {
        val result = diagnostic.evidence(SleepWakeEvidenceDiagnostics.History(), start)!!
        assertEquals(0, result.growthSteps)
        assertFalse(result.clearsStrongStepThreshold)
        assertFalse(result.clearsHrAssistedStepThreshold)
    }
}
