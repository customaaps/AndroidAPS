package app.aaps.workflow

import kotlin.test.*

class LoopCadenceTest {
    @Test fun repeatedAndOutOfOrderReadingsNeverTrigger() {
        for (native in listOf(false, true)) {
            assertFalse(LoopCadence.shouldTrigger(1_000_000, 1_000_000, native))
            assertFalse(LoopCadence.shouldTrigger(999_999, 1_000_000, native))
        }
    }
    @Test fun firstReadingCanTriggerInEitherMode() {
        assertTrue(LoopCadence.shouldTrigger(60_000, 0, false))
        assertTrue(LoopCadence.shouldTrigger(60_000, 0, true))
    }
    @Test fun defaultCadenceAcceptsThirtySecondsOfSensorJitter() {
        assertFalse(LoopCadence.shouldTrigger(1_269_999, 1_000_000, false))
        assertTrue(LoopCadence.shouldTrigger(1_270_000, 1_000_000, false))
        assertTrue(LoopCadence.shouldTrigger(1_300_000, 1_000_000, false))
    }
    @Test fun nativeCadenceAllowsEveryNewReading() {
        assertTrue(LoopCadence.shouldTrigger(1_060_000, 1_000_000, true))
    }
    @Test fun aMinuteFeedRunsEveryFiveMinutesByDefault() {
        var last = 0L
        val triggered = (1..16).filter { minute ->
            val time = minute * 60_000L
            LoopCadence.shouldTrigger(time, last, false).also { if (it) last = time }
        }
        assertEquals(listOf(1, 6, 11, 16), triggered)
    }
}
