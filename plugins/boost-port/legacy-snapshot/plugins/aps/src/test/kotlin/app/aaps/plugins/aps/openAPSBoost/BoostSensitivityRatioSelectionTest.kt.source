package app.aaps.plugins.aps.openAPSBoost

import app.aaps.plugins.aps.openAPSBoost.OpenAPSBoostPlugin.Companion.selectSensitivityRatio
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Characterization tests of the existing ratio-selection behavior before porting Boost/dev
 * 3d9d471dbb and f393d02e55 (TDD sensitivity-settings transitions).
 *
 * These deliberately test the current, not the proposed, settings reconciliation. They must
 * be reviewed together with the transition tests when that source behavior is adapted.
 */
class BoostSensitivityRatioSelectionTest {

    @Test
    fun `TDD enabled uses the TDD ratio even when autosens flag is enabled`() {
        assertThat(
            selectSensitivityRatio(
                useTdd = true,
                autosensWhenNoTdd = true,
                isfResultRatio = 1.25,
                orefAutosensRatio = 0.85
            )
        ).isEqualTo(1.25)
    }

    @Test
    fun `TDD disabled and traditional autosens enabled uses oref ratio`() {
        assertThat(
            selectSensitivityRatio(
                useTdd = false,
                autosensWhenNoTdd = true,
                isfResultRatio = 1.25,
                orefAutosensRatio = 0.85
            )
        ).isEqualTo(0.85)
    }

    @Test
    fun `TDD disabled and traditional autosens disabled uses existing ISF result ratio`() {
        assertThat(
            selectSensitivityRatio(
                useTdd = false,
                autosensWhenNoTdd = false,
                isfResultRatio = 1.25,
                orefAutosensRatio = 0.85
            )
        ).isEqualTo(1.25)
    }
}
