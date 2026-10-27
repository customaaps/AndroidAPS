package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HrSourceResolverTest {
    @Test fun emptyHistoryHasNoLiveSource() {
        val result = HrSourceResolver.resolve(emptyList(), 1_000_000)
        assertEquals(null, result.active)
        assertFalse(result.anyFresh)
        assertEquals("none", result.note)
    }

    @Test fun currentWearBeatsHealthConnectBackfill() {
        val now = 2_000_000L
        val result = HrSourceResolver.resolve(
            listOf(
                HrSourceResolver.Reading("HealthConnect", now - 60_000),
                HrSourceResolver.Reading("OPPO watch", now - 120_000),
            ), now
        )
        assertEquals("worn:OPPO watch", result.active)
        assertTrue(result.anyFresh)
    }

    @Test fun staleReadingsNeverAppearLive() {
        val now = 2_000_000L
        val result = HrSourceResolver.resolve(
            listOf(HrSourceResolver.Reading("Garmin", now - 13 * 60_000L)), now
        )
        assertEquals(null, result.active)
        assertFalse(result.anyFresh)
    }

    @Test fun futureDatedSensorRecordIsNotFresh() {
        val now = 2_000_000L
        val result = HrSourceResolver.resolve(
            listOf(HrSourceResolver.Reading("Garmin", now + 60_000L)), now
        )
        assertEquals(null, result.active)
        assertFalse(result.anyFresh)
    }

    @Test fun freshHealthConnectIsUsedIfWearIsStale() {
        val now = 2_000_000L
        val result = HrSourceResolver.resolve(
            listOf(
                HrSourceResolver.Reading("OPPO watch", now - 15 * 60_000L),
                HrSourceResolver.Reading("HealthConnect", now - 3 * 60_000L),
            ), now
        )
        assertEquals("hc", result.active)
    }
}
