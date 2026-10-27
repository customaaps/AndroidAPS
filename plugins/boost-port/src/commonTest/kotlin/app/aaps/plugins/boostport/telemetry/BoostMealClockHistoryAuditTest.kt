package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoostMealClockHistoryAuditTest {
    private val day = 86_400_000L
    private val anchor = 1_700_000_000_000L
    private val minute = 60_000L

    private fun inspect(events: List<Long>, now: Long = anchor + 20 * day) =
        BoostMealClockHistoryAudit.inspect(events, now, 0L, 0, 0)

    @Test fun noEvidenceYieldsNoHabitualModes() {
        assertTrue(inspect(emptyList()).isEmpty())
        assertTrue(inspect(listOf(anchor)).isEmpty())
    }

    @Test fun sixDaysWithSameMealClockCanFormOneMode() {
        val meals = (0 until 6).map { anchor + it * day }
        val modes = inspect(meals)
        assertEquals(1, modes.size)
        assertEquals(6, modes.single().eventCount)
        assertEquals(6, modes.single().distinctDays)
    }

    @Test fun duplicatesFailClosedWithoutCreatingAdditionalSessions() {
        val meals = (0 until 6).map { anchor + it * day }
        assertTrue(inspect(meals + meals.first()).isEmpty())
    }

    @Test fun oversizedHistoryFailsClosedBeforeQuadraticClustering() {
        val events = (0..1000).map { anchor + it * minute }
        assertTrue(inspect(events).isEmpty())
    }

    @Test fun invalidNightMinutesFailClosed() {
        val meals = (0 until 6).map { anchor + it * day }
        assertTrue(
            BoostMealClockHistoryAudit.inspect(meals, anchor + 20 * day,
                0L, -1, 100).isEmpty()
        )
    }
}
