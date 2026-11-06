package app.aaps.ui.compose.main

import app.aaps.core.interfaces.overview.graph.TbrDisplayData
import app.aaps.core.interfaces.overview.graph.TbrState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TbrDisplayValueTest {

    private fun running(rate: Double?, basal: Double?) = TbrDisplayData(
        state = TbrState.NONE, timestamp = 0L, duration = 0L,
        isRunning = true, absoluteRate = rate, profileRate = basal
    )

    @Test
    fun `absolute rate remains absolute unless percent display is enabled`() {
        assertThat(running(0.8, 0.4).displayValue(false)).isEqualTo(TbrDisplayValue(0.8, false))
        assertThat(running(0.8, 0.4).displayValue(true)).isEqualTo(TbrDisplayValue(200.0, true))
    }

    @Test
    fun `zero temporary basal is a known rate in both display modes`() {
        assertThat(running(0.0, 0.4).displayValue(false)).isEqualTo(TbrDisplayValue(0.0, false))
        assertThat(running(0.0, 0.4).displayValue(true)).isEqualTo(TbrDisplayValue(0.0, true))
    }

    @Test
    fun `unknown or zero profile basal falls back to confirmed absolute rate`() {
        listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { basal ->
            assertThat(running(0.8, basal).displayValue(true)).isEqualTo(TbrDisplayValue(0.8, false))
        }
    }

    @Test
    fun `unconfirmed or invalid rate never fabricates a temporary basal`() {
        listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { rate ->
            assertThat(running(rate, 0.4).displayValue(true)).isNull()
        }
        assertThat(running(0.8, 0.4).copy(isRunning = false).displayValue(true)).isNull()
    }

    @Test
    fun `percentage overflow falls back to known absolute rate`() {
        assertThat(running(Double.MAX_VALUE, Double.MIN_VALUE).displayValue(true))
            .isEqualTo(TbrDisplayValue(Double.MAX_VALUE, false))
    }
}
