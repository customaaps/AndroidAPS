package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostRiskFeatureProjectionAuditTest {
    private val audit = BoostRiskFeatureProjectionAudit

    private val names = listOf(
        "iob_iob",
        "iob_iob_lag0",
        "iob_iob_lag1",
        "iob_bolusiob",
        "recent_smb_units_60m",
        "recent_smb_units_60m_lag0",
        "recent_smb_units_60m_lag1",
        "time_since_last_smb_min",
        "cgm_mgdl",
    )
    private val values = listOf(1.5, 1.5, 1.2, 0.8, 0.4, 0.4, 0.2, 14.0, 130.0)

    private fun inspect(projectedIob: Double = 2.5) =
        audit.inspect(names, values, projectedIob)

    private fun value(
        result: BoostRiskFeatureProjectionAudit.Projection,
        name: String,
    ) = result.values[result.featureNames.indexOf(name)]

    @Test fun currentIobAndLagZeroAreProjectedTogether() {
        val r = inspect()!!
        assertEquals(2.5, value(r, "iob_iob"))
        assertEquals(2.5, value(r, "iob_iob_lag0"))
        assertEquals(1.0, r.hypotheticalIobDifference)
    }

    @Test fun bothRecentSmbFieldsAndBolusIobMoveBySameDifference() {
        val r = inspect()!!
        assertEquals(1.8, value(r, "iob_bolusiob"), 1e-9)
        assertEquals(1.4, value(r, "recent_smb_units_60m"), 1e-9)
        assertEquals(1.4, value(r, "recent_smb_units_60m_lag0"), 1e-9)
    }

    @Test fun historicalLagOneAndGlucoseAreNeverRewritten() {
        val r = inspect()!!
        assertEquals(1.2, value(r, "iob_iob_lag1"))
        assertEquals(0.2, value(r, "recent_smb_units_60m_lag1"))
        assertEquals(130.0, value(r, "cgm_mgdl"))
        assertFalse("cgm_mgdl" in r.changedFeatureNames)
    }

    @Test fun hypotheticalRecentSmbClockIsResetToZero() {
        val r = inspect()!!
        assertEquals(0.0, value(r, "time_since_last_smb_min"))
        assertTrue("time_since_last_smb_min" in r.changedFeatureNames)
    }

    @Test fun negativeIobDeltaClampsFutureRecentBolusFieldsToZero() {
        val r = inspect(projectedIob = -0.5)!!
        assertEquals(-2.0, r.hypotheticalIobDifference)
        assertEquals(0.0, value(r, "iob_bolusiob"))
        assertEquals(0.0, value(r, "recent_smb_units_60m"))
        assertEquals(0.0, value(r, "recent_smb_units_60m_lag0"))
        assertEquals(1.2, value(r, "iob_iob_lag1"))
    }

    @Test fun originalFeatureVectorIsImmutableAcrossInspections() {
        val initial = values.toList()
        inspect()!!
        inspect(1.0)!!
        assertEquals(initial, values)
    }

    @Test fun reducedFeatureSchemaDoesNotMakeUpAbsentLabels() {
        val r = audit.inspect(listOf("iob_iob", "cgm_mgdl"),
            listOf(1.5, 130.0), 2.5)!!
        assertEquals(listOf(2.5, 130.0), r.values)
        assertEquals(listOf("iob_iob"), r.changedFeatureNames)
    }

    @Test fun absentCurrentIobFeatureMustAbstain() {
        assertNull(audit.inspect(listOf("cgm_mgdl"), listOf(130.0), 2.5))
        assertNull(audit.inspect(emptyList(), emptyList(), 2.5))
    }

    @Test fun duplicateOrMisalignedSchemaIsRejected() {
        assertNull(audit.inspect(listOf("iob_iob", "iob_iob"),
            listOf(1.0, 1.0), 2.0))
        assertNull(audit.inspect(listOf("iob_iob", "cgm_mgdl"),
            listOf(1.0), 2.0))
        assertNull(audit.inspect(listOf(""), listOf(1.0), 2.0))
    }

    @Test fun nonFiniteValuesAndHypotheticalInputCannotBecomeModelFeatures() {
        assertNull(audit.inspect(listOf("iob_iob"), listOf(Double.NaN), 1.0))
        assertNull(audit.inspect(listOf("iob_iob"),
            listOf(Double.POSITIVE_INFINITY), 1.0))
        assertNull(audit.inspect(listOf("iob_iob"),
            listOf(1.0), Double.NaN))
        assertNull(audit.inspect(listOf("iob_iob"),
            listOf(1.0), Double.NEGATIVE_INFINITY))
    }

    @Test fun overflowingIobDifferenceFailsClosed() {
        assertNull(audit.inspect(listOf("iob_iob"),
            listOf(-Double.MAX_VALUE), Double.MAX_VALUE))
    }

    @Test fun noProjectionChangeLeavesOnlyClockFieldChanged() {
        val r = inspect(projectedIob = 1.5)!!
        assertEquals(0.0, r.hypotheticalIobDifference)
        assertEquals(listOf("time_since_last_smb_min"), r.changedFeatureNames)
    }
}
