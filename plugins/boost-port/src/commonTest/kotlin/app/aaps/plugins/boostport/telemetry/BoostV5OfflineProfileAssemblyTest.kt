package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BoostV5OfflineProfileAssemblyTest {
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

    @Test fun acceptsBothValidOfflineProjections() {
        val result = BoostV5OfflineProfileAssembly.inspect(base, extra)
        assertEquals(BoostV5OfflineProfileAssembly.Finding.VALID, result.finding)
        assertNotNull(result.snapshot)
    }

    @Test fun invalidBaseRejectsCombinedSnapshot() {
        val result = BoostV5OfflineProfileAssembly.inspect(base.copy(steps5Min = -1), extra)
        assertEquals(BoostV5OfflineProfileAssembly.Finding.BASE_INPUT_INVALID, result.finding)
        assertNull(result.snapshot)
    }

    @Test fun invalidExtraRejectsCombinedSnapshot() {
        val result = BoostV5OfflineProfileAssembly.inspect(base, extra.copy(insulinPeakMinutes = 0))
        assertEquals(BoostV5OfflineProfileAssembly.Finding.ADDITIONAL_INPUT_INVALID, result.finding)
        assertNull(result.snapshot)
    }

    @Test fun failuresInBothPartsRemainVisible() {
        val result = BoostV5OfflineProfileAssembly.inspect(
            base.copy(targetBgMgdl = 130.0),
            extra.copy(variableSensitivity = Double.NaN),
        )
        assertEquals(BoostV5ProfileInputBoundary.Finding.INCONSISTENT_TARGET, result.baseFinding)
        assertEquals(BoostV5ProfileAdditionalInputs.Finding.NON_FINITE, result.additionalFinding)
        assertNull(result.snapshot)
    }
}
