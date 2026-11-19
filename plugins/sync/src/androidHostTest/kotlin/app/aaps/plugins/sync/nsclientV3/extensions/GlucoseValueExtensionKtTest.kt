package app.aaps.plugins.sync.nsclientV3.extensions

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.IDs
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.nssdk.mapper.convertToRemoteAndBack
import app.aaps.plugins.sync.extensions.contentEqualsTo
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class GlucoseValueExtensionKtTest : TestBaseWithProfile() {

    @Test
    fun calibratedUploadDisplaysCorrectionAndRestoresOriginalOnImport() {
        val reading = GV(timestamp = 10000, value = 99.0, raw = 101.0, noise = null, trendArrow = TrendArrow.NONE, sourceSensor = SourceSensor.DEXCOM_G6)
        val remote = checkNotNull(reading.toNSSvgV3(calibratedSgv = 110.5).convertToRemoteAndBack())

        assertThat(remote.sgv).isEqualTo(110.5)
        val imported = remote.toGV()
        assertThat(imported.value).isEqualTo(99.0)
        assertThat(imported.raw).isEqualTo(101.0)
        assertThat(imported.sourceSensor).isEqualTo(SourceSensor.DEXCOM_G6)
    }

    @Test
    fun foreignUnfilteredValueDoesNotReplaceSgv() {
        val reading = GV(timestamp = 10000, value = 99.0, raw = null, noise = null, trendArrow = TrendArrow.NONE, sourceSensor = SourceSensor.DEXCOM_G6)
        val remote = reading.toNSSvgV3().copy(unfiltered = 123.0)
        assertThat(remote.toGV().value).isEqualTo(99.0)
        assertThat(reading.toNSSvgV3(calibratedSgv = Double.NaN).sgv).isEqualTo(99.0)
    }

    @Test
    fun toGlucoseValue() {
        val glucoseValue = GV(
            timestamp = 10000,
            isValid = true,
            raw = 101.0,
            value = 99.0,
            trendArrow = TrendArrow.DOUBLE_UP,
            noise = 1.0,
            sourceSensor = SourceSensor.DEXCOM_G6,
            ids = IDs(
                nightscoutId = "nightscoutId"
            )
        )

        val glucoseValue2 = glucoseValue.toNSSvgV3().convertToRemoteAndBack()?.toGV()
        assertThat(glucoseValue.contentEqualsTo(glucoseValue2!!)).isTrue()
        assertThat(glucoseValue.ids.contentEqualsTo(glucoseValue2.ids)).isTrue()
    }
}
