package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HealthConnectPageGuardTest {
    @Test fun emptyFirstPageIsCompleteRatherThanMissingData() {
        val guard = HealthConnectPageGuard()
        assertEquals(HealthConnectPageGuard.Next.COMPLETE, guard.accept(null))
    }

    @Test fun emptyStringTokenTerminatesPageSequence() {
        val guard = HealthConnectPageGuard()
        assertEquals(HealthConnectPageGuard.Next.COMPLETE, guard.accept(""))
    }

    @Test fun whitespaceTokenTerminatesPageSequence() {
        val guard = HealthConnectPageGuard()
        assertEquals(HealthConnectPageGuard.Next.COMPLETE, guard.accept("  "))
    }

    @Test fun multipleDistinctPagesAreAllowedUntilExplicitEnd() {
        val guard = HealthConnectPageGuard(maxPages = 3)
        assertEquals(HealthConnectPageGuard.Next.CONTINUE, guard.accept("page-2"))
        assertEquals(HealthConnectPageGuard.Next.CONTINUE, guard.accept("page-3"))
        assertEquals(HealthConnectPageGuard.Next.COMPLETE, guard.accept(null))
    }

    @Test fun repeatedTokenFailsClosedBeforePageLimit() {
        val guard = HealthConnectPageGuard(maxPages = 5)
        assertEquals(HealthConnectPageGuard.Next.CONTINUE, guard.accept("repeat"))
        assertEquals(HealthConnectPageGuard.Next.REPEATED_TOKEN, guard.accept("repeat"))
    }

    @Test fun twoTokenCycleIsRejected() {
        val guard = HealthConnectPageGuard(maxPages = 5)
        assertEquals(HealthConnectPageGuard.Next.CONTINUE, guard.accept("a"))
        assertEquals(HealthConnectPageGuard.Next.CONTINUE, guard.accept("b"))
        assertEquals(HealthConnectPageGuard.Next.REPEATED_TOKEN, guard.accept("a"))
    }

    @Test fun paginationCapFailsInsteadOfReturningPartialData() {
        val guard = HealthConnectPageGuard(maxPages = 2)
        assertEquals(HealthConnectPageGuard.Next.CONTINUE, guard.accept("p2"))
        assertEquals(HealthConnectPageGuard.Next.PAGE_LIMIT, guard.accept("p3"))
    }

    @Test fun finalPageAtCapIsValidOnlyWhenNoContinuationIsReturned() {
        val guard = HealthConnectPageGuard(maxPages = 1)
        assertEquals(HealthConnectPageGuard.Next.COMPLETE, guard.accept(null))
    }

    @Test fun nonPositivePageBudgetIsRejected() {
        assertFailsWith<IllegalArgumentException> { HealthConnectPageGuard(0) }
        assertFailsWith<IllegalArgumentException> { HealthConnectPageGuard(-1) }
    }
}
