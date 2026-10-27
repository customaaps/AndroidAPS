package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostMlLegacyVectorAuditTest {
    private val audit = BoostMlLegacyVectorAudit
    private val eight = BoostMlAssetSchemaAudit.BASE_FEATURES

    private fun input(
        cgmMgdl: Double = 125.0,
        iobTotal: Double = 1.4,
        iobBasal: Double = -0.2,
        bgAboveTarget: Double = 20.0,
        directionNum: Double = -1.0,
        localHour: Int = 8,
        iobActivity: Double = 0.12,
        priorInsulinRequirement: Double = 0.5,
    ) = BoostMlLegacyVectorAudit.Input(
        cgmMgdl, iobTotal, iobBasal, bgAboveTarget,
        directionNum, localHour, iobActivity, priorInsulinRequirement,
    )

    @Test fun originalEightPositionalFieldsArePreservedExactly() {
        val r = audit.inspect(input(), eight)!!
        assertEquals(eight, r.featureNames)
        assertEquals(listOf(125.0, 1.4, -0.2, 20.0, -1.0, 8.0, 0.12, 0.5),
            r.featureValues)
        assertEquals("legacy-eight-inputs", r.format)
    }

    @Test fun sourceMealSchemaAcceptsOriginalEightFields() {
        val modelFields = BoostMlAssetSchemaAudit.requiredFeatureNames(
            BoostMlAssetSchemaAudit.Kind.MEAL_LIKELIHOOD)
        assertEquals(8, audit.inspect(input(), modelFields)!!.featureValues.size)
    }

    @Test fun v12HypoRiskSchemaMustNotSilentlyUseEightFeatureFallback() {
        val v12 = BoostMlAssetSchemaAudit.requiredFeatureNames(
            BoostMlAssetSchemaAudit.Kind.HYPO_RISK_V12)
        assertEquals(53, v12.size)
        assertNull(audit.inspect(input(), v12))
    }

    @Test fun reorderedOrPartialModelFeatureSchemaAbstains() {
        assertNull(audit.inspect(input(), eight.reversed()))
        assertNull(audit.inspect(input(), eight.dropLast(1)))
        assertNull(audit.inspect(input(), eight + "unknown"))
    }

    @Test fun basalIobIsSignedAndMustRemainSignedInLegacyVector() {
        val r = audit.inspect(input(iobBasal = -0.9), eight)!!
        assertEquals(-0.9, r.featureValues[2])
    }

    @Test fun hourZeroAndHourTwentyThreeAreValidButTwentyFourIsNot() {
        assertEquals(0.0, audit.inspect(input(localHour = 0), eight)!!.featureValues[5])
        assertEquals(23.0, audit.inspect(input(localHour = 23), eight)!!.featureValues[5])
        assertNull(audit.inspect(input(localHour = 24), eight))
        assertNull(audit.inspect(input(localHour = -1), eight))
    }

    @Test fun directionOutsideKnownNumericEncodingAbstains() {
        assertEquals(-2.0,
            audit.inspect(input(directionNum = -2.0), eight)!!.featureValues[4])
        assertEquals(2.0,
            audit.inspect(input(directionNum = 2.0), eight)!!.featureValues[4])
        assertNull(audit.inspect(input(directionNum = 3.0), eight))
    }

    @Test fun nonFiniteCgmIobAndHistoricalInsulinRequirementAreRejected() {
        assertNull(audit.inspect(input(cgmMgdl = Double.NaN), eight))
        assertNull(audit.inspect(input(iobTotal = Double.POSITIVE_INFINITY), eight))
        assertNull(audit.inspect(input(iobActivity = Double.NEGATIVE_INFINITY), eight))
        assertNull(audit.inspect(input(priorInsulinRequirement = Double.NaN), eight))
        assertNull(audit.inspect(input(directionNum = Double.NaN), eight))
    }

    @Test fun zeroOrNegativeCgmIsNotValidModelEvidence() {
        assertNull(audit.inspect(input(cgmMgdl = 0.0), eight))
        assertNull(audit.inspect(input(cgmMgdl = -1.0), eight))
    }

    @Test fun previousRequirementIsRecordedOnlyAndDoesNotChangeOtherFeatures() {
        val low = audit.inspect(input(priorInsulinRequirement = 0.0), eight)!!
        val high = audit.inspect(input(priorInsulinRequirement = 2.0), eight)!!
        assertEquals(low.featureValues.dropLast(1), high.featureValues.dropLast(1))
        assertEquals(2.0, high.featureValues.last())
        assertTrue(low.featureValues.last() < high.featureValues.last())
    }
}
