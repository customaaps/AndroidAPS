package app.aaps.plugins.aps.openAPSBoost

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Audit #10: a V6 primer cannot replace a protective low or zero temp basal. */
class PrimerTbrActionSafetyTest {

    @Test fun `running zero temp wins when base engine returns no new rate`() {
        val existing = effectiveBaseTempRate(null, 0.0, 20)
        assertThat(primerTbrAction(existing, currentBasal = 0.8, primerRate = 2.0))
            .isEqualTo(PrimerTbrAction.SKIP_PROTECTIVE)
    }

    @Test fun `no running temp allows primer when there is no proposed rate`() {
        val existing = effectiveBaseTempRate(null, 0.0, 0)
        assertThat(primerTbrAction(existing, currentBasal = 0.8, primerRate = 2.0))
            .isEqualTo(PrimerTbrAction.APPLY)
    }

    @Test fun `high running temp at or above primer is not replaced or extended`() {
        val existing = effectiveBaseTempRate(null, 2.4, 15)
        assertThat(primerTbrAction(existing, currentBasal = 0.8, primerRate = 2.0))
            .isEqualTo(PrimerTbrAction.SUBSUMED)
    }

    @Test fun `explicit engine rates take precedence over running temp`() {
        assertThat(primerTbrAction(effectiveBaseTempRate(0.3, 2.5, 20), 0.8, 2.0))
            .isEqualTo(PrimerTbrAction.SKIP_PROTECTIVE)
        assertThat(primerTbrAction(effectiveBaseTempRate(2.5, 0.0, 20), 0.8, 2.0))
            .isEqualTo(PrimerTbrAction.SUBSUMED)
        assertThat(primerTbrAction(effectiveBaseTempRate(1.0, 0.0, 20), 0.8, 2.0))
            .isEqualTo(PrimerTbrAction.APPLY)
    }
}
