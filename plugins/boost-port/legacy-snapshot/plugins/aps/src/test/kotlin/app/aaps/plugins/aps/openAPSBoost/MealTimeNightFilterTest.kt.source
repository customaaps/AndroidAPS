package app.aaps.plugins.aps.openAPSBoost

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MealTimeNightFilterTest {

    @Test fun `wrapping night window includes late and early hours but excludes wake boundary`() {
        assertThat(MealTimeLearner.inNightMinutes(23 * 60 + 30, 22 * 60, 7 * 60)).isTrue()
        assertThat(MealTimeLearner.inNightMinutes(3 * 60, 22 * 60, 7 * 60)).isTrue()
        assertThat(MealTimeLearner.inNightMinutes(7 * 60, 22 * 60, 7 * 60)).isFalse()
        assertThat(MealTimeLearner.inNightMinutes(12 * 60, 22 * 60, 7 * 60)).isFalse()
    }

    @Test fun `same start and end has no exclusion`() {
        assertThat(MealTimeLearner.inNightMinutes(100, 100, 100)).isFalse()
    }

    @Test fun `night history is filtered without changing input`() {
        val day = 24L * 60 * 60 * 1000
        val hour = 60L * 60 * 1000
        val h = MealTimeLearner.History(mutableListOf(day + 3 * hour, day + 12 * hour, day + 23 * hour))
        val filtered = MealTimeLearner.withoutNightEvents(h, 22 * 60, 7 * 60, 0L)
        assertThat(filtered.events).containsExactly(day + 12 * hour)
        assertThat(h.events).hasSize(3)
    }

    @Test fun `overnight learned mode cannot trigger anticipatory premeal window`() {
        val dayMs = 24L * 60 * 60 * 1000
        fun event(day: Long, minute: Int) = day * dayMs + minute * 60_000L
        val overnight = (1L..6L).map { event(it, 4 * 60 + 30) }
        val breakfast = (1L..6L).map { event(it, 8 * 60) }
        val history = MealTimeLearner.History((overnight + breakfast).toMutableList())
        assertThat(MealTimeLearner.preMealWindow(history, 3 * 60 + 40, 0L, 60)).isNotNull()
        val filtered = MealTimeLearner.withoutNightEvents(history, 22 * 60, 7 * 60, 0L)
        assertThat(filtered.events).containsExactlyElementsIn(breakfast)
        assertThat(MealTimeLearner.preMealWindow(filtered, 3 * 60 + 40, 0L, 60)).isNull()
        assertThat(MealTimeLearner.preMealWindow(filtered, 7 * 60 + 5, 0L, 60)).isNotNull()
    }

    @Test fun `nonwrapping night window excludes only its own interval`() {
        assertThat(MealTimeLearner.inNightMinutes(3 * 60, 60, 360)).isTrue()
        assertThat(MealTimeLearner.inNightMinutes(23 * 60, 60, 360)).isFalse()
        assertThat(MealTimeLearner.inNightMinutes(360, 60, 360)).isFalse()
    }
}
