package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1ResultParity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoostV1ResultParityTest {
    private fun sample() = BoostV1ResultParity.Output(
        algorithm = "BOOST",
        tempRequested = true,
        rate = 0.8,
        durationMinutes = 30,
        smbUnits = 0.0,
        targetBg = 100.0
    )

    @Test fun identicalOutputsAreEquivalent() {
        val result = BoostV1ResultParity.compare(sample(), sample().copy())
        assertTrue(result.comparable)
        assertTrue(result.equivalent)
        assertFalse(result.canDeliverInsulin)
    }

    @Test fun differentInsulinAndTempRequestsMustNotPass() {
        val result = BoostV1ResultParity.compare(
            sample(), sample().copy(rate = 0.9, smbUnits = 0.1, durationMinutes = 25)
        )
        assertEquals(
            setOf(
                BoostV1ResultParity.Field.TEMP_RATE,
                BoostV1ResultParity.Field.TEMP_DURATION,
                BoostV1ResultParity.Field.SMB_UNITS
            ),
            result.mismatches
        )
        assertFalse(result.equivalent)
    }

    @Test fun invalidAndIncompleteOutputsAreNotComparable() {
        assertFalse(BoostV1ResultParity.compare(sample(), sample().copy(rate = Double.NaN)).comparable)
        assertFalse(BoostV1ResultParity.compare(sample(), sample().copy(durationMinutes = null)).comparable)
        assertFalse(BoostV1ResultParity.compare(sample(), sample().copy(smbUnits = -0.01)).comparable)
    }

    @Test fun noTempRequestDoesNotCompareDormantRates() {
        val left = sample().copy(tempRequested = false, rate = null, durationMinutes = null)
        val right = left.copy(rate = 0.0, durationMinutes = 0)
        val result = BoostV1ResultParity.compare(left, right)
        assertTrue(result.equivalent)
    }

    @Test fun algorithmMismatchNeverPasses() {
        val result = BoostV1ResultParity.compare(sample(), sample().copy(algorithm = "SMB"))
        assertEquals(setOf(BoostV1ResultParity.Field.ALGORITHM), result.mismatches)
    }
}
