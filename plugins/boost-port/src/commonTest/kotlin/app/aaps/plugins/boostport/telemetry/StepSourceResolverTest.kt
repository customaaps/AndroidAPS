package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StepSourceResolverTest {
    private fun state(source: String, fresh: Boolean, days: Int = 0, steps: Int = 0) =
        StepSourceResolver.SourceState(source, fresh, days, steps)

    @Test fun emptyCandidatesReportNoSource() {
        val result = StepSourceResolver.resolve(emptyList())
        assertEquals(null, result.active)
        assertEquals(0, result.stepsToday)
        assertFalse(result.activeFresh)
    }

    @Test fun freshWearTakesPriorityOverFreshGarminAndPhone() {
        val result = StepSourceResolver.resolve(
            listOf(state("phone", true, steps = 1200), state("garmin", true, steps = 950),
                state("wear", true, steps = 1100))
        )
        assertEquals("wear", result.active)
        assertEquals(1100, result.stepsToday)
        assertTrue(result.activeFresh)
    }

    @Test fun freshPhoneBeatsStaleWear() {
        val result = StepSourceResolver.resolve(
            listOf(state("wear", false, days = 4, steps = 320), state("phone", true, steps = 700))
        )
        assertEquals("phone", result.active)
        assertTrue(result.activeFresh)
    }

    @Test fun staleHistoryIsReportedButNotMarkedFresh() {
        val result = StepSourceResolver.resolve(listOf(state("wear", false, days = 5, steps = 300)))
        assertEquals("wear", result.active)
        assertFalse(result.activeFresh)
    }

    @Test fun absentHistoryAndUnfreshSourcesDoNotBecomeActive() {
        val result = StepSourceResolver.resolve(listOf(state("wear", false), state("phone", false)))
        assertEquals(null, result.active)
        assertFalse(result.activeFresh)
    }

    @Test fun garminHealthConnectPackageIsCanonicalized() {
        val result = StepSourceResolver.resolve(
            listOf(state("com.garmin.android.apps.connectmobile", true, steps = 456))
        )
        assertEquals("garmin", result.active)
        assertEquals(456, result.stepsToday)
    }
    @Test fun freshNegativeWearCountCannotBeatHealthyGarminFeed() {
        val result = StepSourceResolver.resolve(
            listOf(state("wear", true, days = 7, steps = -10),
                state("garmin", true, days = 7, steps = 850))
        )
        assertEquals("garmin", result.active)
        assertEquals(850, result.stepsToday)
    }

    @Test fun invalidNegativeCoverageIsNotEvidenceOfFreshSource() {
        val result = StepSourceResolver.resolve(
            listOf(state("wear", true, days = -1, steps = 900),
                state("phone", true, days = 3, steps = 300))
        )
        assertEquals("phone", result.active)
        assertEquals(300, result.stepsToday)
    }

    @Test fun allInvalidSourcesYieldNoActiveFeed() {
        val result = StepSourceResolver.resolve(
            listOf(state("wear", true, days = -1),
                state("garmin", true, steps = -1))
        )
        assertEquals(null, result.active)
        assertEquals(0, result.stepsToday)
        assertFalse(result.activeFresh)
    }

    @Test fun invalidHighTrustSourcesAreOmittedFromDiagnosticNote() {
        val result = StepSourceResolver.resolve(
            listOf(state("wear", true, steps = -5),
                state("phone", true, days = 1, steps = 40))
        )
        assertEquals("phone", result.active)
        assertFalse(result.note.contains("wear"))
        assertTrue(result.note.contains("phone"))
    }

}
