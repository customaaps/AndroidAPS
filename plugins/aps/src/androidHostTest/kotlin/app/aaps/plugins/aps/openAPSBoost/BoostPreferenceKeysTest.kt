package app.aaps.plugins.aps.openAPSBoost

import app.aaps.core.keys.interfaces.*
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BoostPreferenceKeysTest {
    @Test fun sourcePreferenceAliasesPreserveImportExportIdentityDefaultsAndRawMode() {
        val raw = BoostApsSettingKeys.all.associateBy { it.key }
        val editable = BoostApsPreferenceKeys.editable
        assertEquals(65, editable.size)
        assertEquals(editable.size, editable.map { it.key }.distinct().size)
        assertEquals(BoostApsSettingKeys.all.map { it.key }, BoostApsPreferenceKeys.owned(BoostApsSettingKeys.all).map { it.key })
        editable.forEach { key ->
            val original = raw.getValue(key.key)
            assertFalse(key.defaultedBySM)
            assertEquals(original.exportable, key.exportable)
            when (key) {
                is BooleanPreferenceKey -> assertEquals((original as BooleanNonPreferenceKey).defaultValue, key.defaultValue)
                is IntPreferenceKey -> {
                    assertEquals((original as IntNonPreferenceKey).defaultValue, key.defaultValue)
                    assertTrue(key.defaultValue in key.min..key.max)
                }
                is DoublePreferenceKey -> {
                    assertEquals((original as DoubleNonPreferenceKey).defaultValue, key.defaultValue)
                    assertTrue(key.defaultValue in key.min..key.max)
                }
                is UnitDoublePreferenceKey -> {
                    assertEquals((original as DoubleNonPreferenceKey).defaultValue, key.defaultValue)
                    assertTrue(key.defaultValue in key.minMgdl.toDouble()..key.maxMgdl.toDouble())
                }
                is StringPreferenceKey -> assertEquals((original as StringNonPreferenceKey).defaultValue, key.defaultValue)
                else -> error("Unexpected editable type")
            }
        }
    }
    @Test fun clocksRejectMalformedValuesAndOffsetEditorCannotGuessMmolForSmallMgdlOffsets() {
        val clocks = BoostApsPreferenceKeys.editable.filterIsInstance<StringPreferenceKey>()
        assertEquals(2, clocks.size)
        clocks.forEach { key ->
            listOf("7:00", "07:00", "24:00", "00:00").forEach { assertTrue(key.validator.validate(it).isValid, it) }
            listOf("", "25:00", "24:01", "7:1", " 07:00").forEach { assertFalse(key.validator.validate(it).isValid, it) }
        }
        val offset = BoostApsPreferenceKeys.editable.single { it.key == BoostApsSettingKeys.ApsBoostNightModeBgOffset.key }
        assertTrue(offset is DoublePreferenceKey)
        assertFalse(offset is UnitDoublePreferenceKey)
        assertEquals(0.0, (offset as DoublePreferenceKey).min)
        assertEquals(90.0, offset.max)
    }
    @Test fun sourceManagedStateAndAutomaticRoutingAreNotExposedAsUserSettings() {
        val names = BoostApsPreferenceKeys.editable.map { it.key }
        listOf(BoostApsSettingKeys.Session, BoostApsSettingKeys.ApsBoostSleepState,
            BoostApsSettingKeys.ApsBoostMealTimeHistory, BoostApsSettingKeys.ApsBoostTrialSeed).forEach {
            assertFalse(it.key in names)
        }
        assertFalse(BoostApsSettingKeys.ApsBoostV5PrimerTbrFallback.key in names)
        assertFalse(BoostApsSettingKeys.ApsBoostStartTime.key in names)
        assertFalse(BoostApsSettingKeys.ApsBoostEndTime.key in names)
    }
}
