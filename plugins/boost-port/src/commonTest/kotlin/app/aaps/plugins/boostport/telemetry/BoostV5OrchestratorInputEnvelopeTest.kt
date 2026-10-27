package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV5OrchestratorInputEnvelopeTest {
    private val sample = BoostV5OrchestratorInputEnvelope.Cycle(
        delta = 3.0, shortAvgDelta = 2.0, deltaAccl = 1.0,
        bg = 120.0, eventualBg = 130.0, targetBg = 100.0,
        maxDelta = 3.0, minGuardBg = 100.0, minGuardThreshold = 75.0,
        deltaHistory = listOf(1.0, 2.0, 3.0),
        iob = 1.0, maxIob = 2.0, baseInsulinReq = 0.2,
        roundSmbTo = 0.05, enableSmbPreChecks = true,
        mlHypoRisk = null, mlMealLikely = null,
        recentLowBg = 90.0, cumulativeRise30min = 6.0, hour = 12,
        exerciseActive = false, inPostExerciseWindow = false,
        confirmedCapU = 2.5, committedCapU = 0.5,
    )

    @Test fun allInputsRemainDisconnectedFromDosing() {
        val result = BoostV5OrchestratorInputEnvelope.inspect(sample)
        assertEquals(BoostV5OrchestratorInputEnvelope.Finding.STRUCTURALLY_VALID, result.finding)
        assertFalse(result.liveDosingPermitted)
    }

    @Test fun nanRiskFailsStructuralInspection() {
        assertEquals(BoostV5OrchestratorInputEnvelope.Finding.INVALID_NUMERIC,
            BoostV5OrchestratorInputEnvelope.inspect(sample.copy(mlHypoRisk = Double.NaN)).finding)
    }

    @Test fun negativeClockIsRejected() {
        assertEquals(BoostV5OrchestratorInputEnvelope.Finding.INVALID_CLOCK,
            BoostV5OrchestratorInputEnvelope.inspect(sample.copy(nowMs = -1)).finding)
    }

    @Test fun outOfRangeHourIsRejected() {
        assertEquals(BoostV5OrchestratorInputEnvelope.Finding.INVALID_HOUR,
            BoostV5OrchestratorInputEnvelope.inspect(sample.copy(hour = 24)).finding)
    }

    @Test fun emptyDeltaHistoryIsRejected() {
        assertEquals(BoostV5OrchestratorInputEnvelope.Finding.EMPTY_DELTA_HISTORY,
            BoostV5OrchestratorInputEnvelope.inspect(sample.copy(deltaHistory = emptyList())).finding)
    }
}
