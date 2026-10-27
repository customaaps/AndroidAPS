package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class BoostV5ProfileAdditionalInputsTest {
    private val audit = BoostV5ProfileAdditionalInputs
    private val sample = BoostV5ProfileAdditionalInputs.Source(
        variableSensitivity = 42.0, totalDailyDoseUnits = 25.0,
        insulinDivisor = 55, dynamicIsfVelocity = 0.5, insulinPeakMinutes = 50,
        boostActive = false, dynamicIsfMode = true, exerciseSubclass = "baseline",
    )

    @Test fun validArchivedFieldsRemainInspectableWithoutDosing() {
        val result = audit.inspect(sample)
        assertEquals(BoostV5ProfileAdditionalInputs.Finding.VALID, result.finding)
        assertNotNull(result.snapshot)
        assertEquals(50, result.snapshot.insulinPeakMinutes)
    }

    @Test fun nonFiniteSensitivityCannotFormSnapshot() {
        val result = audit.inspect(sample.copy(variableSensitivity = Double.NaN))
        assertEquals(BoostV5ProfileAdditionalInputs.Finding.NON_FINITE, result.finding)
        assertNull(result.snapshot)
    }

    @Test fun invalidVelocityCannotFormSnapshot() {
        val result = audit.inspect(sample.copy(dynamicIsfVelocity = 1.5))
        assertEquals(BoostV5ProfileAdditionalInputs.Finding.INVALID_RANGE, result.finding)
        assertNull(result.snapshot)
    }

    @Test fun insulinPeakMustFitArchivedDocumentation() {
        val result = audit.inspect(sample.copy(insulinPeakMinutes = 76))
        assertEquals(BoostV5ProfileAdditionalInputs.Finding.INVALID_INSULIN_PEAK, result.finding)
        assertNull(result.snapshot)
    }
}
