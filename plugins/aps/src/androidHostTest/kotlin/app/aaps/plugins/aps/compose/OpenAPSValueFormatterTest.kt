package app.aaps.plugins.aps.compose

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.shared.tests.generatedTextResolver
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale

internal class OpenAPSValueFormatterTest {
    private lateinit var previousLocale: Locale

    @BeforeEach fun setLocale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @AfterEach fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    private fun formatter(units: GlucoseUnit) = OpenAPSValueFormatter(generatedTextResolver()) { units }

    @Test fun mmolTargetsAndSensitivityUseExplicitMgdlConversion() {
        val sut = formatter(GlucoseUnit.MMOL)
        assertThat(sut.format("target_bg", 100.88730399999999)).isEqualTo("5.6 mmol/L")
        assertThat(sut.format("min_bg", 100.9)).isEqualTo("5.6 mmol/L")
        assertThat(sut.format("sens", 180.1559)).isEqualTo("10 mmol/L/U")
        // A small or negative mg/dL value must never be mistaken for an already converted mmol value.
        assertThat(sut.format("delta", -1.8)).isEqualTo("-0.1 mmol/L")
        assertThat(sut.format("lgsThreshold", 72)).isEqualTo("4 mmol/L")
        assertThat(sut.format("eventualBG", 126)).isEqualTo("7 mmol/L")
        assertThat(sut.format("variable_sens", 90)).isEqualTo("5 mmol/L/U")
    }

    @Test fun mgdlModeKeepsMgdlAndRemovesFloatingPointNoise() {
        val sut = formatter(GlucoseUnit.MGDL)
        assertThat(sut.format("target_bg", 100.88730399999999)).isEqualTo("101 mg/dL")
        assertThat(sut.format("sens", 180.1559)).isEqualTo("180.16 mg/dL/U")
        assertThat(sut.format("carb_ratio", 9.639)).isEqualTo("9.64 g/U")
        assertThat(sut.format("ratio", 1.00000000000002)).isEqualTo("1")
    }

    @Test fun dosingPrecisionAndNegativeNetInsulinArePreservedForDisplay() {
        val sut = formatter(GlucoseUnit.MMOL)
        assertThat(sut.format("bolus_increment", 0.025)).isEqualTo("0.025 U")
        assertThat(sut.format("current_basal", 1.525)).isEqualTo("1.525 U/h")
        assertThat(sut.format("netInsulin", -1.394)).isEqualTo("-1.394 U")
        assertThat(sut.format("activity", 0.000001)).isEqualTo("0.000001")
        assertThat(sut.format("SMBInterval", 3)).isEqualTo("3 min")
        assertThat(sut.format("ratio", -0.0000001)).isEqualTo("0")
    }

    @Test fun localizedNumbersAndUnavailableValuesStayReadable() {
        Locale.setDefault(Locale.GERMANY)
        val sut = formatter(GlucoseUnit.MGDL)
        assertThat(sut.format("carb_ratio", 9.639)).isEqualTo("9,64 g/U")
        assertThat(sut.format("iob", Double.NaN)).isEqualTo("Not available")
        assertThat(sut.format("target_bg", Double.POSITIVE_INFINITY)).isEqualTo("Not available")
    }

    @Test fun unitChangesAreReadWhenFormattingRatherThanFrozenAtConstruction() {
        var units = GlucoseUnit.MGDL
        val sut = OpenAPSValueFormatter(generatedTextResolver()) { units }
        assertThat(sut.format("target_bg", 100.9)).isEqualTo("101 mg/dL")
        units = GlucoseUnit.MMOL
        assertThat(sut.format("target_bg", 100.9)).isEqualTo("5.6 mmol/L")
    }
}
