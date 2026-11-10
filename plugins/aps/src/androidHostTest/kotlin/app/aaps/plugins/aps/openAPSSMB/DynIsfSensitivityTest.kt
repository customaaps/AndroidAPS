package app.aaps.plugins.aps.openAPSSMB

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.ln

/**
 * Pins the ported DynamicISF sensitivity formula against the AAPS 3.x / upstream 4.0 expression
 * `1800 / (TDD * ln(bg / insulinDivisor + 1))`. This value decides an insulin dose; if it drifts,
 * the port is wrong.
 */
class DynIsfSensitivityTest {

    private val tdd = 52.4209738095238
    private val divisor = 55
    private val base = DynIsfSensitivity.baseFromTdd(tdd, divisor)

    /** The expression this class replaced, verbatim. */
    private fun old(bg: Double): Double = 1800.0 / (tdd * ln((bg / divisor) + 1))

    @Test
    fun `base at the normal target is the old expression without rounding`() {
        assertThat(base).isWithin(1e-9).of(1800.0 / (tdd * ln((100.0 / divisor) + 1)))
    }

    @Test
    fun `atBg reproduces the 3_x formula at every bg`() {
        for (bg in listOf(39.0, 49.0, 70.0, 91.0, 100.0, 150.0, 250.0, 400.0)) {
            assertThat(DynIsfSensitivity.atBg(bg, base, divisor)).isWithin(1e-9).of(old(bg))
        }
    }

    @Test
    fun `atBg keeps the old curve shape for every divisor`() {
        for (d in listOf(55, 65, 75)) {
            val b = DynIsfSensitivity.baseFromTdd(tdd, d)
            for (bg in listOf(60.0, 100.0, 180.0, 300.0)) {
                assertThat(DynIsfSensitivity.atBg(bg, b, d)).isWithin(1e-9).of(1800.0 / (tdd * ln((bg / d) + 1)))
            }
        }
    }
}
