package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1IsfShadowCalculation
import app.aaps.plugins.aps.openAPSBoost.BoostV1SmbEligibilityShadow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class BoostV1SourceMathAdapterTest {
    @Test fun isfUsesOriginalLogarithmicFormulaWithoutApplyingIt() {
        val input = BoostV1IsfShadowCalculation.Input(
            bgMgDl = 110.0, capEnabled = false, bgCapMgDl = 180.0,
            insulinDivisor = 55.0, normalTargetMgDl = 110.0,
            sensNormalTarget = 50.0, velocity = 1.0
        )
        val result = BoostV1IsfShadowCalculation.inspect(input)
        assertEquals(BoostV1IsfShadowCalculation.Finding.READY, result.finding)
        assertEquals(50.0, result.shadowIsf!!, 1e-9)
        assertNull(BoostV1IsfShadowCalculation.inspect(input.copy(bgMgDl = Double.NaN)).shadowIsf)
    }

    @Test fun smbConditionOrderCannotOverrideMasterPermission() {
        val input = BoostV1SmbEligibilityShadow.Input(
            microBolusAllowed = false, allowHighTempTarget = false,
            tempTargetSet = false, targetMgDl = 110.0,
            enableAlways = true, enableWithCob = true,
            enableAfterCarbs = true, enableWithTempTarget = true,
            cobGrams = 20.0, carbsGrams = 20.0
        )
        val blocked = BoostV1SmbEligibilityShadow.inspect(input)
        assertEquals(BoostV1SmbEligibilityShadow.Reason.MASTER_DISABLED, blocked.reason)
        assertEquals(BoostV1SmbEligibilityShadow.Finding.BLOCKED, blocked.finding)
        assertFalse(blocked.canDeliverInsulin)
        val matched = BoostV1SmbEligibilityShadow.inspect(input.copy(microBolusAllowed = true))
        assertEquals(BoostV1SmbEligibilityShadow.Reason.ALWAYS, matched.reason)
        assertFalse(matched.canDeliverInsulin)
    }

    @Test fun highTempTargetBlocksBeforeAlwaysFlag() {
        val input = BoostV1SmbEligibilityShadow.Input(
            microBolusAllowed = true, allowHighTempTarget = false,
            tempTargetSet = true, targetMgDl = 120.0, enableAlways = true,
            enableWithCob = false, enableAfterCarbs = false,
            enableWithTempTarget = false, cobGrams = 0.0, carbsGrams = 0.0
        )
        assertEquals(BoostV1SmbEligibilityShadow.Reason.HIGH_TEMP_TARGET,
            BoostV1SmbEligibilityShadow.inspect(input).reason)
    }
}
