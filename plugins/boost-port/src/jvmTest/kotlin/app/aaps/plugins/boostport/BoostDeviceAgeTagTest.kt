package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.DeviceAgeTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Source age-tag scenarios plus suspend/cancellation/cache boundaries. Tests are staged, not run. */
class BoostDeviceAgeTagTest {
    private val hour = 3_600_000L
    private val now = 1_790_000_000_000L

    @Test fun agesUseSourceOneDecimalFormatAndMissingRecordsUseDash() = runBlocking {
        val tag = DeviceAgeTag { site -> if (site) now - (51.25 * hour).toLong() else null }
        assertEquals("siteAge=51.3; resAge=-; ", tag.tag(now))
        assertEquals("-", DeviceAgeTag.hours(now + hour, now))
    }

    @Test fun bothTherapyEventsAreReadOnFirstCycleAndThenOnlyAfterTenMinutes() = runBlocking {
        var reads = 0
        var siteMs = now - 2 * hour
        val tag = DeviceAgeTag { site -> reads++; if (site) siteMs else now - 100 * hour }
        assertEquals("siteAge=2.0; resAge=100.0; ", tag.tag(now))
        siteMs = now + 30_000L
        assertEquals("siteAge=2.0; resAge=100.0; ", tag.tag(now + 60_000L))
        assertEquals(2, reads)
        assertTrue(tag.tag(now + DeviceAgeTag.REFRESH_MS).startsWith("siteAge=0.2; "))
        assertEquals(4, reads)
    }

    @Test fun clockReversalRefreshesInsteadOfKeepingAnUnboundedCache() = runBlocking {
        var reads = 0
        val tag = DeviceAgeTag { reads++; now - 2 * hour }
        tag.tag(now)
        tag.tag(now - 60_000L)
        assertEquals(4, reads)
    }

    @Test fun failedSecondReadDoesNotCommitAPartialRefreshOrAdvanceTheClock() = runBlocking {
        var fail = true
        var reads = 0
        val tag = DeviceAgeTag { site ->
            reads++
            if (!site && fail) error("database failure")
            now - hour
        }
        assertFailsWith<IllegalStateException> { tag.tag(now) }
        fail = false
        assertEquals("siteAge=1.0; resAge=1.0; ", tag.tag(now + 1))
        assertEquals(4, reads)
    }

    @Test fun cancellationPropagatesAndDoesNotMakeFailedReadLookCached() = runBlocking {
        var cancel = true
        var reads = 0
        val tag = DeviceAgeTag { site ->
            reads++
            if (!site && cancel) throw CancellationException("owner stopped")
            null
        }
        assertFailsWith<CancellationException> { tag.tag(now) }
        cancel = false
        assertEquals("siteAge=-; resAge=-; ", tag.tag(now + 1))
        assertEquals(4, reads)
    }

    @Test fun freshOwnerAfterImportDoesNotReusePreviousTherapyTimestamps() = runBlocking {
        assertEquals("siteAge=2.0; resAge=2.0; ", DeviceAgeTag { now - 2 * hour }.tag(now))
        assertEquals("siteAge=0.0; resAge=0.0; ", DeviceAgeTag { now }.tag(now))
    }

    @Test fun invalidAndFutureEpochsAreUnknownAndZeroAgeIsRetained() {
        assertEquals("-", DeviceAgeTag.hours(-1L, now))
        assertEquals("-", DeviceAgeTag.hours(now + 1, now))
        assertEquals("0.0", DeviceAgeTag.hours(now, now))
    }
}
