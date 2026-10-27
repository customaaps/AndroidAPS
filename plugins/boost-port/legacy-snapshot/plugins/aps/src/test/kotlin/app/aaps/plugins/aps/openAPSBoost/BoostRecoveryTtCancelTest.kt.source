package app.aaps.plugins.aps.openAPSBoost

import app.aaps.core.data.model.TT
import app.aaps.plugins.aps.openAPSBoost.OpenAPSBoostPlugin.Companion.isBoostRecoveryTt
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class BoostRecoveryTtCancelTest {
    private val boostStart = 1_790_000_000_000L

    @Test fun `own recovery target is recognised`() {
        assertThat(isBoostRecoveryTt(boostStart, TT.Reason.ACTIVITY, boostStart)).isTrue()
    }

    @Test fun `user activity target is never treated as Boost recovery`() {
        assertThat(isBoostRecoveryTt(boostStart + 60_000L, TT.Reason.ACTIVITY, boostStart)).isFalse()
        assertThat(isBoostRecoveryTt(boostStart, TT.Reason.ACTIVITY, null)).isFalse()
    }

    @Test fun `unrelated reason or missing target is not cancelled`() {
        assertThat(isBoostRecoveryTt(boostStart, TT.Reason.CUSTOM, boostStart)).isFalse()
        assertThat(isBoostRecoveryTt(boostStart, TT.Reason.HYPOGLYCEMIA, boostStart)).isFalse()
        assertThat(isBoostRecoveryTt(null, null, boostStart)).isFalse()
    }
}
