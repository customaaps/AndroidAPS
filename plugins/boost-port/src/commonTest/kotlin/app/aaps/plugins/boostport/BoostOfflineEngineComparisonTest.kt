package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostOfflineEngineComparison
import kotlin.test.Test
import kotlin.test.assertEquals

class BoostOfflineEngineComparisonTest {
    private fun sample(time: Long, actual: Double = 0.0, candidate: Double = actual) =
        BoostOfflineEngineComparison.Sample(time, actual, candidate, 0.0, 0.0)

    @Test fun noInputsAndMissingOutputsAreNotSuccess() {
        val engine = BoostOfflineEngineComparison
        assertEquals(BoostOfflineEngineComparison.Finding.EMPTY, engine.compare(emptyList()).finding)
        assertEquals(BoostOfflineEngineComparison.Finding.MISSING_REFERENCE,
            engine.compare(listOf(BoostOfflineEngineComparison.Sample(10, null, 0.0, 0.0, 0.0))).finding)
    }

    @Test fun mismatchIsReportedWithoutReturningDosage() {
        val report = BoostOfflineEngineComparison.compare(listOf(
            sample(1, 0.0), sample(2, 0.0, 0.1), sample(3, 0.0)
        ))
        assertEquals(BoostOfflineEngineComparison.Finding.DIVERGENT, report.finding)
        assertEquals(1, report.firstProblemIndex)
        assertEquals(1, report.divergent)
    }

    @Test fun invalidAndNonChronologicalDataCannotPass() {
        assertEquals(BoostOfflineEngineComparison.Finding.INVALID_INPUT,
            BoostOfflineEngineComparison.compare(listOf(sample(1, candidate = Double.NaN))).finding)
        assertEquals(BoostOfflineEngineComparison.Finding.NON_MONOTONIC_TIME,
            BoostOfflineEngineComparison.compare(listOf(sample(2), sample(2))).finding)
    }

    @Test fun equalNonnegativeOutputsCompare() {
        val report = BoostOfflineEngineComparison.compare(listOf(sample(1), sample(2, 0.01)))
        assertEquals(BoostOfflineEngineComparison.Finding.MATCHED, report.finding)
        assertEquals(2, report.compared)
    }
}
