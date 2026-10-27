package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoostV5DisabledCycleEntryTest {
    private val input = BoostV5OrchestratorInputEnvelope.Cycle(
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
        nowMs = 2_000L,
    )
    private val legacy = mapOf("mealHypothesis" to "IDLE", "mealHypothesisAge" to "0")

    @Test fun inspectableInputDoesNotAuthorizeDosing() {
        val report = BoostV5DisabledCycleEntry.inspect(input, legacy)
        assertEquals(BoostV5DisabledCycleEntry.Finding.OFFLINE_INPUTS_READY, report.finding)
        assertFalse(report.canInvokeDecide)
        assertFalse(report.canDeliverInsulin)
    }

    @Test fun unknownStateSchemaCannotSilentlyClearACommitLock() {
        val report = BoostV5DisabledCycleEntry.inspect(
            input, legacy + ("futureCommitState" to "true")
        )
        assertEquals(BoostV5DisabledCycleEntry.Finding.INVALID_PERSISTED_SCHEMA, report.finding)
        assertFalse(report.canDeliverInsulin)
    }

    @Test fun futureStateClockRejectsThisCycle() {
        val report = BoostV5DisabledCycleEntry.inspect(input,
            legacy + ("primerIobUpdatedMs" to "3000"))
        assertEquals(BoostV5DisabledCycleEntry.Finding.FUTURE_STATE_CLOCK, report.finding)
        assertTrue("primerIobUpdatedMs" in report.futureStateFields)
    }

    @Test fun missingCycleClockCannotClaimOfflineReadiness() {
        val report = BoostV5DisabledCycleEntry.inspect(input.copy(nowMs = 0L), legacy)
        assertEquals(BoostV5DisabledCycleEntry.Finding.INCONSISTENT_CYCLE_CLOCK, report.finding)
    }

    @Test fun corruptInputFailsBeforeStateInspection() {
        val report = BoostV5DisabledCycleEntry.inspect(input.copy(bg = Double.NaN), legacy)
        assertEquals(BoostV5DisabledCycleEntry.Finding.INVALID_INPUTS, report.finding)
        assertEquals(null, report.stateShapeFinding)
    }
}
