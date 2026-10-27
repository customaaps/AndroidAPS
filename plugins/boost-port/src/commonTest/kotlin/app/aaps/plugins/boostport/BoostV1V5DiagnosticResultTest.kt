package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostV1V5DiagnosticResult
import app.aaps.plugins.boostport.telemetry.BoostV1V5GuardedSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1V5DiagnosticResultTest {
    @Test fun rejectedReplayCannotBecomeResult() {
        val result = BoostV1V5DiagnosticResult.inspect(
            BoostV1V5GuardedSession.Report(BoostV1V5GuardedSession.Finding.INVALID_V1)
        )
        assertEquals(BoostV1V5DiagnosticResult.Finding.INVALID_SESSION, result.finding)
        assertFalse(result.canDeliverInsulin)
    }
    @Test fun finiteChronologicalOfflineObservationsBecomeDiagnostics() {
        val session = BoostV1V5GuardedSession.Report(
            BoostV1V5GuardedSession.Finding.READY,
            listOf(
                BoostV1V5GuardedSession.Observation(100L, 0.1, "IDLE"),
                BoostV1V5GuardedSession.Observation(200L, 0.2, "IDLE")
            )
        )
        val result = BoostV1V5DiagnosticResult.inspect(session)
        assertEquals(BoostV1V5DiagnosticResult.Finding.DIAGNOSTIC_ONLY, result.finding)
        assertEquals(2, result.entries.size)
        assertFalse(result.canDeliverInsulin)
    }
    @Test fun nanScoreAndEmptyMealStateCannotEscapeDiagnostics() {
        for (observation in listOf(
            BoostV1V5GuardedSession.Observation(100L, Double.NaN, "IDLE"),
            BoostV1V5GuardedSession.Observation(100L, 0.2, "")
        )) {
            val session = BoostV1V5GuardedSession.Report(
                BoostV1V5GuardedSession.Finding.READY, listOf(observation)
            )
            val report = BoostV1V5DiagnosticResult.inspect(session)
            assertEquals(BoostV1V5DiagnosticResult.Finding.INVALID_OBSERVATION, report.finding)
            assertFalse(report.canDeliverInsulin)
        }
    }

    @Test fun corruptSequenceFailsClosed() {
        val session = BoostV1V5GuardedSession.Report(
            BoostV1V5GuardedSession.Finding.READY,
            listOf(
                BoostV1V5GuardedSession.Observation(200L, 0.1, "IDLE"),
                BoostV1V5GuardedSession.Observation(100L, 0.2, "IDLE")
            )
        )
        assertEquals(BoostV1V5DiagnosticResult.Finding.INVALID_OBSERVATION,
            BoostV1V5DiagnosticResult.inspect(session).finding)
    }
}
