package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Caller-provided values only; no Android Preferences or dosing engine. */
class BoostStoredPreferenceResolutionAuditTest {
    private val audit = BoostStoredPreferenceResolutionAudit

    @Test fun rawStoredNumberIsNotSilentlyReplacedByMaskedDefault() {
        val result = audit.inspect(storedValue = 8.0, factoryDefault = 1.0, maskedReadValue = 1.0)
        assertEquals(8.0, result.resolvedValue)
        assertTrue(result.usedStoredValue)
        assertTrue(result.differedFromMaskedRead)
    }

    @Test fun absentStoredValueFallsBackToFactoryDefault() {
        val result = audit.inspect(storedValue = null as Int?, factoryDefault = 4, maskedReadValue = 4)
        assertEquals(4, result.resolvedValue)
        assertFalse(result.usedStoredValue)
        assertFalse(result.differedFromMaskedRead)
    }

    @Test fun storedFalseAndEmptyStringRemainExplicitOverrides() {
        val boolValue = audit.inspect(storedValue = false, factoryDefault = true, maskedReadValue = true)
        assertFalse(boolValue.resolvedValue)
        assertTrue(boolValue.usedStoredValue)
        val stringValue = audit.inspect(storedValue = "", factoryDefault = "factory", maskedReadValue = "factory")
        assertEquals("", stringValue.resolvedValue)
        assertTrue(stringValue.usedStoredValue)
    }

    @Test fun unitDoubleFallbackMustAlreadyBeInDisplayUnits() {
        val result = audit.inspectUnitDouble(null, 1.5, 1.5)!!
        assertEquals(1.5, result.resolvedValue)
        assertFalse(result.usedStoredValue)
    }

    @Test fun unitDoubleUsesStoredDisplayValueWhenAvailable() {
        val result = audit.inspectUnitDouble(2.1, 1.5, 1.5)!!
        assertEquals(2.1, result.resolvedValue)
        assertTrue(result.differedFromMaskedRead)
    }

    @Test fun nonFiniteValuesFailClosed() {
        assertNull(audit.inspectUnitDouble(Double.NaN, 1.5, 1.5))
        assertNull(audit.inspectUnitDouble(null, Double.POSITIVE_INFINITY, 1.5))
        assertNull(audit.inspectUnitDouble(null, 1.5, Double.NEGATIVE_INFINITY))
    }
}
