package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals

class BoostV5HistoricalDefaultInspectionTest {
    private val audit = BoostV5HistoricalDefaultInspection
    private fun finding(key: String, values: Map<String, String>) =
        audit.inspect(values).single { it.storageKey == key }.finding

    @Test fun oldFactoryCapIsNotUserTuning() {
        assertEquals(BoostV5HistoricalDefaultInspection.Finding.FACTORY_HISTORICAL,
            finding("boost_v5_committed_cap_u", mapOf("boost_v5_committed_cap_u" to "0.25")))
    }

    @Test fun currentFactoryValueIsClassifiedSeparately() {
        assertEquals(BoostV5HistoricalDefaultInspection.Finding.FACTORY_CURRENT,
            finding("boost_v5_confirmed_cap_u", mapOf("boost_v5_confirmed_cap_u" to "2.5")))
    }

    @Test fun customValueIsNotConfusedWithOldDefault() {
        assertEquals(BoostV5HistoricalDefaultInspection.Finding.USER_TUNED,
            finding("boost_v5_confirmed_cap_u", mapOf("boost_v5_confirmed_cap_u" to "3.0")))
    }

    @Test fun previousCumulativeFactoryErasRemainRecognized() {
        assertEquals(BoostV5HistoricalDefaultInspection.Finding.FACTORY_HISTORICAL,
            finding("boost_cumulative_smb_cap_60min",
                mapOf("boost_cumulative_smb_cap_60min" to "1.5")))
        assertEquals(BoostV5HistoricalDefaultInspection.Finding.FACTORY_HISTORICAL,
            finding("boost_cumulative_smb_cap_60min",
                mapOf("boost_cumulative_smb_cap_60min" to "6.0")))
    }

    @Test fun genuineOutOfRangeCustomValueIsInvalid() {
        assertEquals(BoostV5HistoricalDefaultInspection.Finding.INVALID,
            finding("boost_v5_aggression", mapOf("boost_v5_aggression" to "3.0")))
    }

    @Test fun invalidAndMissingValuesDoNotCountAsOverrides() {
        assertEquals(BoostV5HistoricalDefaultInspection.Finding.INVALID,
            finding("boost_v5_aggression", mapOf("boost_v5_aggression" to "NaN")))
        assertEquals(BoostV5HistoricalDefaultInspection.Finding.ABSENT,
            finding("boost_v5_aggression", emptyMap()))
    }
}
