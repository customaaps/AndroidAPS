package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.HrFeedDarkTracker
import app.aaps.plugins.aps.openAPSBoost.HrSourceResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HrSourceResolverPortTest {
    private val now = 1_800_000_000_000L

    @Test fun categorizesSourcesWithoutReadingDatabase() {
        assertEquals("garmin", HrSourceResolver.canonical("Garmin"))
        assertEquals("hc", HrSourceResolver.canonical("HealthConnect"))
        assertEquals("hc", HrSourceResolver.canonical(" "))
        assertEquals("worn:OPPO OWWE261", HrSourceResolver.canonical("OPPO OWWE261"))
    }

    @Test fun prefersFreshRealtimeToHealthConnect() {
        val r = HrSourceResolver.resolve(
            listOf(
                HrSourceResolver.Reading("HealthConnect", now - 60_000L),
                HrSourceResolver.Reading("OPPO OWWE261", now - 2 * 60_000L)
            ), now
        )
        assertTrue(r.anyFresh)
        assertEquals("worn:OPPO OWWE261", r.active)
    }

    @Test fun staleSourcesAreNotSelected() {
        val r = HrSourceResolver.resolve(
            listOf(HrSourceResolver.Reading("Garmin", now - 13 * 60_000L)), now
        )
        assertFalse(r.anyFresh)
        assertNull(r.active)
    }

    @Test fun initialDarkStateDoesNotReportTransition() {
        val tracker = HrFeedDarkTracker(notifyAfterMin = 60)
        val dark = HrSourceResolver.resolve(emptyList(), now)
        assertNull(tracker.onCycle(dark, now, 12).wentDarkNote)
    }

    @Test fun transitionAlertsOnceDuringDayAndResetsOnRecovery() {
        val tracker = HrFeedDarkTracker(notifyAfterMin = 60)
        val fresh = HrSourceResolver.resolve(listOf(HrSourceResolver.Reading("Garmin", now)), now)
        val dark = HrSourceResolver.resolve(listOf(HrSourceResolver.Reading("Garmin", now - 15 * 60_000L)), now)
        tracker.onCycle(fresh, now, 12)
        val edge = tracker.onCycle(dark, now + 15 * 60_000L, 12)
        assertNotNull(edge.wentDarkNote)
        assertFalse(edge.raiseNotification)
        assertTrue(tracker.onCycle(dark, now + 76 * 60_000L, 12).raiseNotification)
        assertFalse(tracker.onCycle(dark, now + 77 * 60_000L, 12).raiseNotification)
        tracker.onCycle(HrSourceResolver.resolve(listOf(HrSourceResolver.Reading("Garmin", now + 78 * 60_000L)), now + 78 * 60_000L), now + 78 * 60_000L, 12)
        assertNotNull(tracker.onCycle(dark, now + 79 * 60_000L, 12).wentDarkNote)
    }

    @Test fun exactFreshnessBoundaryIsFresh() {
        val atBoundary = HrSourceResolver.resolve(
            listOf(HrSourceResolver.Reading("Garmin", now - HrSourceResolver.FRESH_MS)), now
        )
        assertTrue(atBoundary.anyFresh)
        val justOutside = HrSourceResolver.resolve(
            listOf(HrSourceResolver.Reading("Garmin", now - HrSourceResolver.FRESH_MS - 1L)), now
        )
        assertFalse(justOutside.anyFresh)
    }

    @Test fun darkFeedNotificationDoesNotFireDuringNight() {
        val tracker = HrFeedDarkTracker(notifyAfterMin = 60)
        val alive = HrSourceResolver.resolve(listOf(HrSourceResolver.Reading("Garmin", now)), now)
        val stale = HrSourceResolver.resolve(listOf(HrSourceResolver.Reading("Garmin", now - 20 * 60_000L)), now)
        tracker.onCycle(alive, now, 23)
        tracker.onCycle(stale, now + 20 * 60_000L, 23)
        assertFalse(tracker.onCycle(stale, now + 100 * 60_000L, 2).raiseNotification)
        assertTrue(tracker.onCycle(stale, now + 101 * 60_000L, 9).raiseNotification)
        assertFalse(tracker.onCycle(stale, now + 102 * 60_000L, 9).raiseNotification)
    }

    @Test fun futureDatedReadingIsNotMistakenForLiveFeed() {
        val future = HrSourceResolver.resolve(
            listOf(HrSourceResolver.Reading("Garmin", now + 60_000L)), now
        )
        assertFalse(future.anyFresh)
        assertNull(future.active)
    }

    @Test fun futureDatedHigherTrustSourceDoesNotOverrideValidFreshSource() {
        val result = HrSourceResolver.resolve(
            listOf(
                HrSourceResolver.Reading("Garmin", now + 60_000L),
                HrSourceResolver.Reading("HealthConnect", now - 60_000L)
            ), now
        )
        assertTrue(result.anyFresh)
        assertEquals("hc", result.active)
    }

    @Test fun clockRollbackRestartsDarkDurationWithoutFalseAlert() {
        val tracker = HrFeedDarkTracker(notifyAfterMin = 60)
        val fresh = HrSourceResolver.resolve(listOf(HrSourceResolver.Reading("Garmin", now)), now)
        val stale = HrSourceResolver.resolve(emptyList(), now)
        tracker.onCycle(fresh, now, 12)
        tracker.onCycle(stale, now + 10 * 60_000L, 12)
        val rollback = tracker.onCycle(stale, now + 5 * 60_000L, 12)
        assertEquals(0L, rollback.darkMinutes)
        assertFalse(rollback.raiseNotification)
        val later = tracker.onCycle(stale, now + 65 * 60_000L, 12)
        assertEquals(60L, later.darkMinutes)
        assertTrue(later.raiseNotification)
    }

    @Test fun epochZeroIsValidDarkStartRatherThanUninitializedSentinel() {
        val tracker = HrFeedDarkTracker(notifyAfterMin = 1)
        val alive = HrSourceResolver.resolve(listOf(HrSourceResolver.Reading("Garmin", 0L)), 0L)
        val absent = HrSourceResolver.resolve(emptyList(), 0L)
        tracker.onCycle(alive, 0L, 12)
        tracker.onCycle(absent, 0L, 12)
        val afterMinute = tracker.onCycle(absent, 60_000L, 12)
        assertEquals(1L, afterMinute.darkMinutes)
        assertTrue(afterMinute.raiseNotification)
    }
}
