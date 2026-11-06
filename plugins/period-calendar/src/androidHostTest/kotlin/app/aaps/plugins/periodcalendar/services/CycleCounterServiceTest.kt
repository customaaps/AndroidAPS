package app.aaps.plugins.periodcalendar.services

import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.plugins.periodcalendar.model.CycleStartRecord
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import java.util.Calendar

class CycleCounterServiceTest : TestBase() {

    @Mock lateinit var sp: SP

    private lateinit var sut: CycleCounterService
    private val longs = mutableMapOf<String, Long>()
    private val strings = mutableMapOf<String, String>()

    @BeforeEach
    fun setUp() {
        whenever(sp.getLong(any<String>(), any<Long>())).thenAnswer { invocation ->
            longs[invocation.getArgument(0)] ?: invocation.getArgument(1)
        }
        whenever(sp.getString(any<String>(), any<String>())).thenAnswer { invocation ->
            strings[invocation.getArgument(0)] ?: invocation.getArgument(1)
        }
        doAnswer { invocation ->
            longs[invocation.getArgument(0)] = invocation.getArgument(1)
            null
        }.whenever(sp).putLong(any<String>(), any<Long>())
        doAnswer { invocation ->
            strings[invocation.getArgument(0)] = invocation.getArgument(1)
            null
        }.whenever(sp).putString(any<String>(), anyOrNull<String>())

        sut = CycleCounterService(sp, aapsLogger)
    }

    @Test
    fun cycleStartTimestampSetter_normalizesToMidnight() {
        val midday = midnightOf(System.currentTimeMillis()) + 12 * 60 * 60 * 1000

        sut.cycleStartTimestamp = midday

        assertThat(sut.cycleStartTimestamp).isEqualTo(midnightOf(midday))
    }

    @Test
    fun resetCycle_sameDayLegacyStartDoesNotDuplicateHistory() {
        val todayMidnight = midnightOf(System.currentTimeMillis())
        longs["period_calendar_cycle_start"] = todayMidnight + 8 * 60 * 60 * 1000

        sut.resetCycle()

        assertThat(sut.cycleHistory).isEmpty()
        assertThat(sut.cycleStartTimestamp).isEqualTo(todayMidnight)
    }

    @Test
    fun averageCycleLength_ignoresDuplicateSameDayStarts() {
        val first = midnightOf(System.currentTimeMillis() - 28L * 24 * 60 * 60 * 1000)
        val duplicateSameDay = first + 6 * 60 * 60 * 1000
        val second = first + 28L * 24 * 60 * 60 * 1000

        sut.cycleHistory = listOf(CycleStartRecord(first), CycleStartRecord(duplicateSameDay))
        sut.cycleStartTimestamp = second

        assertThat(sut.getAverageCycleLength()).isEqualTo(28.0)
    }

    @Test
    fun getCycleIntervals_emptyWhenNoData() {
        assertThat(sut.getCycleIntervals()).isEmpty()
    }

    @Test
    fun getCycleIntervals_singleOngoingCycle() {
        val start = midnightOf(System.currentTimeMillis()) - 9 * 24 * 60 * 60 * 1000

        sut.cycleStartTimestamp = start

        val intervals = sut.getCycleIntervals()

        assertThat(intervals).hasSize(1)
        assertThat(intervals[0].ongoing).isTrue()
        assertThat(intervals[0].lengthDays).isEqualTo(10)
    }

    @Test
    fun getCycleIntervals_newestFirstWithLengthsBetweenStarts() {
        val dayMs = 24L * 60 * 60 * 1000
        val today = midnightOf(System.currentTimeMillis())
        val first = today - 57 * dayMs
        val second = first + 28 * dayMs
        val current = second + 26 * dayMs

        sut.cycleHistory = listOf(CycleStartRecord(first), CycleStartRecord(second))
        sut.cycleStartTimestamp = current

        val intervals = sut.getCycleIntervals()

        assertThat(intervals).hasSize(3)
        // Newest first: ongoing cycle, then completed ones.
        assertThat(intervals[0].startTimestamp).isEqualTo(midnightOf(current))
        assertThat(intervals[0].ongoing).isTrue()
        assertThat(intervals[1].lengthDays).isEqualTo(26)
        assertThat(intervals[1].ongoing).isFalse()
        assertThat(intervals[2].lengthDays).isEqualTo(28)
    }

    @Test
    fun getCycleIntervals_deduplicatesSameDayStarts() {
        val dayMs = 24L * 60 * 60 * 1000
        val today = midnightOf(System.currentTimeMillis())
        val first = today - 28 * dayMs
        val duplicateSameDay = first + 6 * 60 * 60 * 1000

        sut.cycleHistory = listOf(CycleStartRecord(first), CycleStartRecord(duplicateSameDay))
        sut.cycleStartTimestamp = today

        val intervals = sut.getCycleIntervals()

        assertThat(intervals).hasSize(2)
        assertThat(intervals[1].lengthDays).isEqualTo(28)
    }

    @Test
    fun getCycleIntervals_startedTodayIsDayOne() {
        sut.cycleStartTimestamp = System.currentTimeMillis()

        val intervals = sut.getCycleIntervals()

        assertThat(intervals).hasSize(1)
        assertThat(intervals[0].lengthDays).isEqualTo(1)
    }

    private fun midnightOf(timestamp: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }
}
