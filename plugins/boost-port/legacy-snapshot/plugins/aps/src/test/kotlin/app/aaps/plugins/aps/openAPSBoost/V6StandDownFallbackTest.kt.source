package app.aaps.plugins.aps.openAPSBoost

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Adapted from Boost/dev V6StandDownTest (2026-10-08).
 * Target exposes baseOrefSmb and v6UnavailableSmb as package-level helpers.
 * Night gate checks are deferred because boostGateOpen is not in the target.
 */
class V6StandDownFallbackTest {

    // ── Base oref SMB ──

    @Test fun `field case - 1_06 U required, 0_6 U per h basal, 20 min cap - 0_2 U`() {
        val smb = baseOrefSmb(insulinReq = 1.06, iob = 0.278, currentBasal = 0.6, maxUamSmbBasalMinutes = 20,
            maxSmbBasalMinutes = 20, boostInsulinReqPct = 85.0, bolusIncrement = 0.05)
        assertThat(smb).isWithin(1e-9).of(0.2)
    }

    @Test fun `below the cap the dose is insulinReq times the percentage, floored to the increment`() {
        // 0.5 x 0.85 = 0.425 -> 0.40 at 0.05; cap 1.5 x 30 / 60 = 0.75
        val smb = baseOrefSmb(0.5, 0.0, 1.5, 30, 30, 85.0, 0.05)
        assertThat(smb).isWithin(1e-9).of(0.4)
    }

    @Test fun `negative IOB uses the SMB minutes, not the UAM minutes`() {
        // UAM minutes would allow 1.0 U; SMB minutes cap it at 0.3 U
        val smb = baseOrefSmb(2.0, -0.5, 1.0, 60, 18, 100.0, 0.05)
        assertThat(smb).isWithin(1e-9).of(0.3)
    }

    @Test fun `no insulin required - no SMB`() {
        assertThat(baseOrefSmb(0.0, 0.0, 1.0, 30, 30, 85.0, 0.05)).isEqualTo(0.0)
        assertThat(baseOrefSmb(-0.4, 0.0, 1.0, 30, 30, 85.0, 0.05)).isEqualTo(0.0)
    }

    // ── V6 error fallback (audit #9) ──

    @Test fun `V6 unavailable - a tiered V1 dose is capped at base oref`() {
        // The field case's numbers: Tier 5 sized 1.95 U where base oref was 0.2 U.
        assertThat(v6UnavailableSmb(1.95, 0.2)).isWithin(1e-9).of(0.2)
    }

    @Test fun `V6 unavailable - a V1 dose at or below base oref is left alone`() {
        assertThat(v6UnavailableSmb(0.15, 0.2)).isWithin(1e-9).of(0.15)
        assertThat(v6UnavailableSmb(null, 0.2)).isNull()
    }
}
