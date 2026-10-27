package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MealEventHistoryAuditTest {
    private val audit = MealEventHistoryAudit
    private val day = 86_400_000L
    private val minute = 60_000L
    private val now = 100L * day + 15 * 60 * minute
    private fun at(dayIndex: Long, clockMinute: Long = 480L) =
        dayIndex * day + clockMinute * minute

    @Test fun newConfirmedMealAppendIsImmutable() {
        val empty = MealEventHistoryAudit.History()
        val added = audit.record(empty, at(100), now)!!
        assertTrue(added.appendedNewCommit)
        assertEquals(listOf(at(100)), added.history.eventsMs)
        assertTrue(empty.eventsMs.isEmpty())
    }

    @Test fun eventOlderThanSixtyDaysIsPrunedWhenNewCommitArrives() {
        val previous = MealEventHistoryAudit.History(listOf(at(39), at(90)))
        val result = audit.record(previous, at(100), now)!!
        assertEquals(listOf(at(90), at(100)), result.history.eventsMs)
        assertEquals(1, result.droppedOutOfWindow)
        assertEquals(2, previous.eventsMs.size)
    }

    @Test fun duplicateConfirmedCommitTimestampDoesNotManufactureExtraMeal() {
        val first = audit.record(MealEventHistoryAudit.History(), at(100), now)!!
        val repeated = audit.record(first.history, at(100), now)!!
        assertFalse(repeated.appendedNewCommit)
        assertEquals(1, repeated.history.eventsMs.size)
    }

    @Test fun sameDayDistinctConfirmedEventsArePreserved() {
        val original = MealEventHistoryAudit.History(listOf(at(99, 480)))
        val result = audit.record(original, at(99, 1140), now)!!
        assertEquals(2, result.history.eventsMs.size)
        assertTrue(result.appendedNewCommit)
    }

    @Test fun historicalAndFutureDataCannotBecomeNewTrainingEvents() {
        assertNull(audit.record(MealEventHistoryAudit.History(), -1L, now))
        assertNull(audit.record(MealEventHistoryAudit.History(), now + 1L, now))
        val expired = audit.record(MealEventHistoryAudit.History(), at(39), now)!!
        assertFalse(expired.appendedNewCommit)
        assertTrue(expired.history.eventsMs.isEmpty())
    }

    @Test fun clockReversalAndCorruptExistingHistoryAreRejected() {
        val previous = MealEventHistoryAudit.History(listOf(at(99), at(100)))
        assertNull(audit.record(previous, at(98), now))
        assertNull(audit.record(previous, at(100), at(99)))
        assertNull(audit.record(MealEventHistoryAudit.History(listOf(at(99), at(99))),
            at(100), now))
        assertNull(audit.record(MealEventHistoryAudit.History(listOf(0L)),
            at(100), now))
    }

    @Test fun sourceFieldsCanBeRoundTrippedWithoutWritingPreferences() {
        val initial = MealEventHistoryAudit.History(listOf(at(90), at(99)))
        val fields = audit.encode(initial)!!
        assertEquals(setOf("events"), fields.keys)
        assertEquals(listOf(at(90).toString(), at(99).toString()), fields["events"])
        assertEquals(initial, audit.decode(fields))
    }

    @Test fun corruptMissingOrDuplicateSavedRecordsFailClosed() {
        assertNull(audit.decode(emptyMap()))
        assertNull(audit.decode(mapOf("events" to listOf("not-a-number"))))
        assertNull(audit.decode(mapOf("events" to listOf("0"))))
        assertNull(audit.decode(mapOf("events" to listOf(at(90).toString(),
            at(90).toString()))))
        assertNull(audit.decode(mapOf("events" to listOf(at(100).toString(),
            at(90).toString()))))
    }

    @Test fun overnightConfirmedMealsCanBeExcludedBeforeClustering() {
        val original = MealEventHistoryAudit.History(listOf(at(90, 270),
            at(91, 480), at(92, 1390)))
        val filtered = audit.withoutNight(original, 0L, 1320, 420)!!
        assertEquals(listOf(at(91, 480)), filtered.eventsMs)
        assertEquals(3, original.eventsMs.size)
    }

    @Test fun emptyNightWindowLeavesDaytimeAndNighttimeHistoryUnchanged() {
        val original = MealEventHistoryAudit.History(listOf(at(90, 270), at(91, 480)))
        assertEquals(original, audit.withoutNight(original, 0L, 420, 420))
    }

    @Test fun corruptedNightConfigurationAndTimezoneFailClosed() {
        val original = MealEventHistoryAudit.History(listOf(at(90)))
        assertNull(audit.withoutNight(original, 19L * 3_600_000L, 1320, 420))
        assertNull(audit.withoutNight(original, 0L, 1440, 420))
        assertNull(audit.withoutNight(original, 0L, 1320, -1))
    }

    @Test fun noConfirmedEventsMeansNoHistoricalTrainingSignal() {
        val empty = MealEventHistoryAudit.History()
        assertEquals(empty, audit.decode(audit.encode(empty)!!))
        assertEquals(empty, audit.withoutNight(empty, 0L, 1320, 420))
    }
}
