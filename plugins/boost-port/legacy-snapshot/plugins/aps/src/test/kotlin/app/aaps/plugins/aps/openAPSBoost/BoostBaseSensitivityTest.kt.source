package app.aaps.plugins.aps.openAPSBoost

import app.aaps.core.interfaces.aps.OapsProfileBoost
import app.aaps.core.interfaces.profile.ProfileUtil
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Characterize the staged Boost/dev 9b674cee5c ART-safe helper independently.
 * This does not authorize connecting it to Dmitry's already autosens-adjusted dosing path.
 */
class BoostBaseSensitivityTest {
    private val engine = DetermineBasalBoost(mock<ProfileUtil>())

    private fun profile(dynamic: Boolean): OapsProfileBoost = mock<OapsProfileBoost>().also {
        whenever(it.dynIsfMode).thenReturn(dynamic)
        whenever(it.sens).thenReturn(60.0)
    }

    @Test fun `static ISF uses autosens ratio`() {
        assertThat(engine.baseSensitivity(profile(false), 60.0, 1.2)).isEqualTo(50.0)
    }

    @Test fun `dynamic ISF never divides by autosens`() {
        assertThat(engine.baseSensitivity(profile(true), 47.0, 1.2)).isEqualTo(47.0)
    }

    @Test fun `static ISF ignores nonpositive ratio`() {
        assertThat(engine.baseSensitivity(profile(false), 60.0, 0.0)).isEqualTo(60.0)
        assertThat(engine.baseSensitivity(profile(false), 60.0, -1.0)).isEqualTo(60.0)
    }

    @Test fun `neutral autosens keeps static profile ISF`() {
        assertThat(engine.baseSensitivity(profile(false), 60.0, 1.0)).isEqualTo(60.0)
    }
}
