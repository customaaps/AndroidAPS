package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HealthConnectStepCoverageTest {
    private fun days(vararg pairs: Pair<Long, Long>): Map<Long, Long> = mapOf(*pairs)

    @Test fun moreCoveredDaysBeatHigherStepCounts() {
        val results = HealthConnectStepCoverage.resolve(
            mapOf(
                "fitness" to days(100L to 30_000L, 101L to 20_000L),
                "on-body" to days(80L to 8_000L, 81L to 9_000L, 82L to 7_000L,
                    83L to 8_000L, 84L to 9_000L, 85L to 8_000L, 86L to 7_000L),
            )
        )
        assertEquals("on-body", results.chosenSource)
        assertEquals(7, results.sources.first().coverageDays)
    }

    @Test fun moreStepsBreakTiedCoverage() {
        val result = HealthConnectStepCoverage.resolve(
            mapOf(
                "a" to days(1L to 1_000L, 2L to 1_000L),
                "b" to days(1L to 5_000L, 2L to 5_000L),
            )
        )
        assertEquals("b", result.chosenSource)
    }

    @Test fun allZeroAndNegativeSourcesAreNotSelected() {
        val result = HealthConnectStepCoverage.resolve(
            mapOf(
                "zero" to days(1L to 0L, 2L to 0L),
                "negative" to days(1L to -10L),
                "real" to days(1L to 200L),
            )
        )
        assertEquals("real", result.chosenSource)
        assertEquals(1, result.sources.size)
    }

    @Test fun noCoveredSourceIsExplicitlyAbsent() {
        assertNull(HealthConnectStepCoverage.resolve(emptyMap()).chosenSource)
        assertNull(HealthConnectStepCoverage.resolve(mapOf("empty" to days(1L to 0L))).chosenSource)
    }

    @Test fun sourceIdsDoNotGetCombinedAcrossWriters() {
        val result = HealthConnectStepCoverage.resolve(
            mapOf("wear" to days(1L to 10L), "phone" to days(1L to 20L))
        )
        assertEquals(2, result.sources.size)
        assertEquals("phone", result.chosenSource)
    }

    @Test fun lexicalTieBreakIsIndependentOfMapIterationOrder() {
        val first = HealthConnectStepCoverage.resolve(
            linkedMapOf("z" to days(1L to 100L), "a" to days(1L to 100L))
        )
        val second = HealthConnectStepCoverage.resolve(
            linkedMapOf("a" to days(1L to 100L), "z" to days(1L to 100L))
        )
        assertEquals("a", first.chosenSource)
        assertEquals(first.chosenSource, second.chosenSource)
    }

    @Test fun totalStepOverflowSaturatesInsteadOfBecomingNegative() {
        val result = HealthConnectStepCoverage.resolve(
            mapOf("high" to days(1L to Long.MAX_VALUE, 2L to 123L),
                  "low" to days(1L to 1L, 2L to 2L))
        )
        assertEquals("high", result.chosenSource)
        assertEquals(Long.MAX_VALUE, result.sources.first().totalSteps)
    }
}
