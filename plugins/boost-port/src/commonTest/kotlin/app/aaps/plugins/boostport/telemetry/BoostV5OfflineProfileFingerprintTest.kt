package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals

class BoostV5OfflineProfileFingerprintTest {
    private val base = BoostV5ProfileInputBoundary.Source(
        minBgMgdl = 90.0, targetBgMgdl = 100.0, maxBgMgdl = 110.0,
        currentBasalUnitsPerHour = 0.8, recentLowBgMgdl = 82.0,
        steps5Min = 12, steps15Min = 35, steps30Min = 80, steps60Min = 125,
        exerciseActive = false, inPostExerciseWindow = false,
    )
    private val extra = BoostV5ProfileAdditionalInputs.Source(
        variableSensitivity = 42.0, totalDailyDoseUnits = 25.0,
        insulinDivisor = 55, dynamicIsfVelocity = 0.5, insulinPeakMinutes = 50,
        boostActive = false, dynamicIsfMode = true, exerciseSubclass = "",
    )
    private fun frame(
        baseInput: BoostV5ProfileInputBoundary.Source = base,
        additionalInput: BoostV5ProfileAdditionalInputs.Source = extra,
    ) = BoostV5OfflineProfileAssembly.inspect(baseInput, additionalInput)

    @Test fun identicalProfilesRemainUnchanged() {
        assertEquals(BoostV5OfflineProfileFingerprint.Finding.UNCHANGED,
            BoostV5OfflineProfileFingerprint.compare(frame(), frame()).finding)
    }

    @Test fun individualPartsCanBeTrackedSeparately() {
        val baseChange = BoostV5OfflineProfileFingerprint.compare(
            frame(), frame(base.copy(steps60Min = 126)))
        assertEquals(true, baseChange.changedBase)
        assertEquals(false, baseChange.changedAdditional)
        val extraChange = BoostV5OfflineProfileFingerprint.compare(
            frame(), frame(additionalInput = extra.copy(exerciseSubclass = "recovery")))
        assertEquals(false, extraChange.changedBase)
        assertEquals(true, extraChange.changedAdditional)
    }

    @Test fun invalidFrameCannotBeComparedAsAValidSnapshot() {
        val comparison = BoostV5OfflineProfileFingerprint.compare(
            frame(), frame(base.copy(steps5Min = -1)))
        assertEquals(BoostV5OfflineProfileFingerprint.Finding.UNAVAILABLE, comparison.finding)
    }
}
