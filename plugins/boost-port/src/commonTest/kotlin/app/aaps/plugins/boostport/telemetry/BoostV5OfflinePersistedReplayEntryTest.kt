package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals

class BoostV5OfflinePersistedReplayEntryTest {
    private val base = BoostV5ProfileInputBoundary.Source(
        minBgMgdl = 90.0, targetBgMgdl = 100.0, maxBgMgdl = 110.0,
        currentBasalUnitsPerHour = 0.8, recentLowBgMgdl = 82.0,
        steps5Min = 12, steps15Min = 35, steps30Min = 80, steps60Min = 125,
        exerciseActive = false, inPostExerciseWindow = false,
    )
    private val additional = BoostV5ProfileAdditionalInputs.Source(
        variableSensitivity = 42.0, totalDailyDoseUnits = 25.0,
        insulinDivisor = 55, dynamicIsfVelocity = 0.5, insulinPeakMinutes = 50,
        boostActive = false, dynamicIsfMode = true, exerciseSubclass = "",
    )
    private fun frame(time: Long) = BoostV5OfflineProfileReplayTimeline.Frame(
        time, BoostV5OfflineProfileAssembly.inspect(base, additional)
    )
    private val legacy = mapOf("mealHypothesis" to "IDLE", "mealHypothesisAge" to "0")

    @Test fun acceptedLegacyFieldsCanEnterOfflineReplay() {
        val result = BoostV5OfflinePersistedReplayEntry.inspect(listOf(frame(1000)), legacy)
        assertEquals(BoostV5OfflinePersistedReplayEntry.Finding.READY, result.finding)
    }

    @Test fun unknownFutureSchemaFieldFailsClosed() {
        val result = BoostV5OfflinePersistedReplayEntry.inspect(
            listOf(frame(1000)), legacy + ("futureCommitLock" to "true"))
        assertEquals(BoostV5OfflinePersistedReplayEntry.Finding.REJECTED_PERSISTED, result.finding)
    }

    @Test fun corruptStateDoesNotBecomeAnEmptySession() {
        val result = BoostV5OfflinePersistedReplayEntry.inspect(
            listOf(frame(1000)), legacy + ("committedInSession" to "notBoolean"))
        assertEquals(BoostV5OfflinePersistedReplayEntry.Finding.REJECTED_PERSISTED, result.finding)
    }

    @Test fun futureClockCannotEnterEarlierReplay() {
        val result = BoostV5OfflinePersistedReplayEntry.inspect(
            listOf(frame(1000)), legacy + ("primerIobUpdatedMs" to "1500"))
        assertEquals(BoostV5OfflinePersistedReplayEntry.Finding.INVALID_STATE_CLOCK, result.finding)
    }

    @Test fun invalidReplayRejectedBeforeStoredFields() {
        val result = BoostV5OfflinePersistedReplayEntry.inspect(
            listOf(frame(2000), frame(1000)), legacy)
        assertEquals(BoostV5OfflinePersistedReplayEntry.Finding.INVALID_REPLAY, result.finding)
    }
}
