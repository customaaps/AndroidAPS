package app.aaps.plugins.periodcalendar.services

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.plugins.periodcalendar.model.CycleInterval
import app.aaps.plugins.periodcalendar.model.CyclePhase
import app.aaps.plugins.periodcalendar.model.CycleStartRecord
import kotlinx.serialization.json.Json
import java.util.Calendar
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.math.roundToInt

@SingleIn(AppScope::class)
class CycleCounterService
    @Inject
    constructor(
        private val sp: SP,
        private val aapsLogger: AAPSLogger,
    ) {
        companion object {
            private const val DAY_MS = 24L * 60 * 60 * 1000
            private const val MAX_HISTORY_ENTRIES = 24
            private const val MAX_CYCLE_DAY = 99
        }

        private val spKeyCycleStart = "period_calendar_cycle_start"
        private val spKeyCycleHistory = "period_calendar_cycle_history"
        private val spKeyPhases = "period_calendar_phases"

        private val json = Json { ignoreUnknownKeys = true }

        var cycleStartTimestamp: Long
            get() {
                val value = sp.getLong(spKeyCycleStart, 0L)
                aapsLogger.debug(LTag.CORE, "Reading cycleStartTimestamp: $value")
                return value
            }
            set(value) {
                val normalizedValue = normalizeStoredTimestamp(value)
                try {
                    sp.putLong(spKeyCycleStart, normalizedValue)
                    aapsLogger.debug(LTag.CORE, "Saved cycleStartTimestamp: $normalizedValue")
                } catch (e: Exception) {
                    aapsLogger.error(LTag.CORE, "Failed to save cycleStartTimestamp", e)
                }
            }

        var cycleHistory: List<CycleStartRecord>
            get() {
                val jsonStr = sp.getString(spKeyCycleHistory, "")
                return if (jsonStr.isEmpty()) {
                    emptyList()
                } else {
                    try {
                        json.decodeFromString<List<CycleStartRecord>>(jsonStr)
                    } catch (e: Exception) {
                        aapsLogger.error(LTag.CORE, "Failed to parse cycle history", e)
                        emptyList()
                    }
                }
            }
            set(value) {
                try {
                    val jsonStr = json.encodeToString(value)
                    sp.putString(spKeyCycleHistory, jsonStr)
                    aapsLogger.debug(LTag.CORE, "Cycle history saved: ${value.size} entries")
                } catch (e: Exception) {
                    aapsLogger.error(LTag.CORE, "Failed to save cycle history", e)
                }
            }

        var phases: List<CyclePhase>
            get() {
                val jsonStr = sp.getString(spKeyPhases, "")
                return if (jsonStr.isEmpty()) {
                    CyclePhase.defaultPhases()
                } else {
                    try {
                        json.decodeFromString<List<CyclePhase>>(jsonStr)
                    } catch (e: Exception) {
                        aapsLogger.error(LTag.CORE, "Failed to parse cycle phases", e)
                        CyclePhase.defaultPhases()
                    }
                }
            }
            set(value) {
                aapsLogger.debug(LTag.CORE, "phases setter called with ${value.size} phases: $value")
                try {
                    val jsonStr = json.encodeToString(value)
                    aapsLogger.debug(LTag.CORE, "JSON encoded phases (length=${jsonStr.length}): ${jsonStr.take(100)}")
                    sp.putString(spKeyPhases, jsonStr)
                    aapsLogger.debug(LTag.CORE, "Cycle phases saved: ${value.size} phases")
                } catch (e: Exception) {
                    aapsLogger.error(LTag.CORE, "Failed to save cycle phases", e)
                }
            }

        fun getCurrentCycleDay(): Int {
            val start = cycleStartTimestamp
            if (start == 0L) return 0
            return getCycleDayForDate(start, System.currentTimeMillis())
        }

        fun getCurrentPhase(): CyclePhase? {
            val day = getCurrentCycleDay()
            if (day == 0) return null
            return phases.firstOrNull { it.containsDay(day) }
        }

        fun getFormattedDisplay(): String {
            val day = getCurrentCycleDay()
            if (day == 0) return "--"
            val phase = getCurrentPhase()
            return if (phase != null) {
                String.format("%02d%s", day, phase.label)
            } else {
                String.format("%02d", day)
            }
        }

        fun resetCycle() {
            val newStart = midnightOf(System.currentTimeMillis())
            val currentStart = cycleStartTimestamp
            aapsLogger.debug(LTag.CORE, "resetCycle() called: currentStart=$currentStart, newStart=$newStart")
            if (shouldArchiveCurrentStart(currentStart, newStart)) {
                appendHistoryEntry(currentStart)
            } else {
                aapsLogger.debug(LTag.CORE, "Skipping history save because currentStart was 0L or same day as new start")
            }
            cycleStartTimestamp = newStart
            val savedHistory = cycleHistory
            aapsLogger.debug(LTag.CORE, "Cycle reset to day 1, history size: ${savedHistory.size}, cycleStartTimestamp now=$cycleStartTimestamp")
        }

        fun setCycleDay(day: Int) {
            val clampedDay = day.coerceIn(1, MAX_CYCLE_DAY)
            val todayMidnight = midnightOf(System.currentTimeMillis())
            val newStart = todayMidnight - (clampedDay - 1) * DAY_MS
            val currentStart = cycleStartTimestamp
            aapsLogger.debug(LTag.CORE, "setCycleDay: day=$clampedDay, todayMidnight=$todayMidnight, newStart=$newStart, currentStart=$currentStart")
            if (shouldArchiveCurrentStart(currentStart, newStart)) {
                appendHistoryEntry(currentStart)
                aapsLogger.debug(LTag.CORE, "Cycle history updated, size=${cycleHistory.size}")
            }
            cycleStartTimestamp = newStart
            aapsLogger.debug(LTag.CORE, "Cycle set to day $clampedDay, saved timestamp=$newStart, verify read=${cycleStartTimestamp}")
        }

        fun isCycleStarted(): Boolean = cycleStartTimestamp > 0L

        fun clearHistory() {
            cycleHistory = emptyList()
            aapsLogger.debug(LTag.CORE, "Cycle history cleared")
        }

        fun getAverageCycleLength(): Double {
            // Include gap from last history entry to current cycle start so one real cycle
            // length is measured rather than defaulting to 28 days.
            val allStarts = allKnownCycleStarts()
            if (allStarts.size < 2) return 28.0
            var totalDays = 0L
            for (i in 1 until allStarts.size) {
                totalDays += (allStarts[i] - allStarts[i - 1]) / DAY_MS
            }
            return totalDays.toDouble() / (allStarts.size - 1)
        }

        fun getPredictedNextStart(): Long? {
            // Base prediction on current cycle start, not the previous one stored in history.
            val currentStart = cycleStartTimestamp
            if (currentStart == 0L) return null
            val avgLength = getAverageCycleLength()
            return currentStart + (avgLength * 1000L * 60 * 60 * 24).toLong()
        }

        fun getPredictedCycleDayForDate(dateTimestamp: Long): Int {
            val start = cycleStartTimestamp
            if (start == 0L) return 0
            val diffDays = getCycleDayForDate(start, dateTimestamp)
            if (diffDays == 0) return 0
            val avgLength = getAverageCycleLength()
            if (avgLength <= 0) return 1
            val dayInCycle = ((diffDays - 1) % avgLength.toInt().coerceAtLeast(1)) + 1
            return dayInCycle.coerceIn(1, MAX_CYCLE_DAY)
        }

        /**
         * Cycle intervals, newest first: each start with the number of calendar days
         * until the next start. The entry matching the active cycle is marked ongoing
         * and carries its length so far (= current cycle day).
         */
        fun getCycleIntervals(): List<CycleInterval> {
            val starts = allKnownCycleStarts()
            if (starts.isEmpty()) return emptyList()
            val currentStart = normalizeStoredTimestamp(cycleStartTimestamp)
            return starts
                .mapIndexed { i, start ->
                    val next = starts.getOrNull(i + 1)
                    if (next != null) {
                        // Round instead of truncate so a DST-shortened day still counts as a full day.
                        CycleInterval(start, ((next - start).toDouble() / DAY_MS).roundToInt(), ongoing = false)
                    } else {
                        val daysSoFar = ((midnightOf(System.currentTimeMillis()) - start).toDouble() / DAY_MS).roundToInt() + 1
                        CycleInterval(start, daysSoFar.coerceAtLeast(1), ongoing = currentStart > 0L && start == currentStart)
                    }
                }.reversed()
        }

        fun getPhaseForDay(day: Int): CyclePhase? {
            if (day == 0) return null
            return phases.firstOrNull { it.containsDay(day) }
        }

        fun getPhaseForDate(dateTimestamp: Long): CyclePhase? {
            val day = getPredictedCycleDayForDate(dateTimestamp)
            return getPhaseForDay(day)
        }

        fun getHistoricalCycleDays(): Map<Long, Int> {
            val result = mutableMapOf<Long, Int>()
            val history = allKnownHistoricalStarts()
            val currentStart = normalizeStoredTimestamp(cycleStartTimestamp)
            if (history.isEmpty() && currentStart == 0L) return result

            val avgLength = getAverageCycleLength()
            val maxCyclesToShow = 3

            val recentHistory = if (history.size > maxCyclesToShow) {
                history.takeLast(maxCyclesToShow)
            } else {
                history
            }

            for (i in recentHistory.indices) {
                val cycleStart = recentHistory[i]
                val cycleLength =
                    if (i < recentHistory.size - 1) {
                        (recentHistory[i + 1] - cycleStart) / DAY_MS
                    } else if (currentStart > 0L && currentStart > cycleStart) {
                        // Use actual gap to current cycle start instead of default avg
                        (currentStart - cycleStart) / DAY_MS
                    } else {
                        avgLength.toLong()
                    }
                val startDay = cycleStart
                for (dayOffset in 0 until cycleLength) {
                    result[startDay + dayOffset * DAY_MS] = (dayOffset.toInt() + 1).coerceIn(1, MAX_CYCLE_DAY)
                }
            }

            // Include current active cycle so its TDD data appears in statistics
            if (currentStart > 0L) {
                val startDay = currentStart
                val todayStart = midnightOf(System.currentTimeMillis())
                val daysInCycle = ((todayStart - startDay) / DAY_MS).toInt() + 1
                for (dayOffset in 0 until daysInCycle) {
                    result[startDay + dayOffset * DAY_MS] = (dayOffset.toInt() + 1).coerceIn(1, MAX_CYCLE_DAY)
                }
            }

            return result
        }

        private fun getCycleDayForDate(
            cycleStart: Long,
            dateTimestamp: Long,
        ): Int {
            val startMidnight = midnightOf(cycleStart)
            val dateMidnight = midnightOf(dateTimestamp)
            val diffMs = dateMidnight - startMidnight
            if (diffMs < 0) return 0
            val diffDays = (diffMs / DAY_MS).toInt() + 1
            return diffDays.coerceIn(0, MAX_CYCLE_DAY)
        }

        private fun appendHistoryEntry(timestamp: Long) {
            val normalizedTimestamp = normalizeStoredTimestamp(timestamp)
            if (normalizedTimestamp == 0L) return
            val prevHistory = allKnownHistoricalStarts().toMutableList()
            if (prevHistory.lastOrNull() == normalizedTimestamp) return
            prevHistory.add(normalizedTimestamp)
            if (prevHistory.size > MAX_HISTORY_ENTRIES) {
                prevHistory.subList(0, prevHistory.size - MAX_HISTORY_ENTRIES).clear()
            }
            cycleHistory = prevHistory.map(::CycleStartRecord)
            aapsLogger.debug(LTag.CORE, "Adding $normalizedTimestamp to history, new size=${prevHistory.size}")
        }

        private fun shouldArchiveCurrentStart(
            currentStart: Long,
            newStart: Long,
        ): Boolean = currentStart > 0L && normalizeStoredTimestamp(currentStart) != normalizeStoredTimestamp(newStart)

        private fun allKnownHistoricalStarts(): List<Long> = cycleHistory.map { normalizeStoredTimestamp(it.timestamp) }.filter { it > 0L }.distinct().sorted()

        private fun allKnownCycleStarts(): List<Long> {
            val starts = allKnownHistoricalStarts().toMutableList()
            val currentStart = normalizeStoredTimestamp(cycleStartTimestamp)
            if (currentStart > 0L) starts.add(currentStart)
            return starts.distinct().sorted()
        }

        private fun normalizeStoredTimestamp(timestamp: Long): Long = if (timestamp > 0L) midnightOf(timestamp) else 0L

        private fun midnightOf(timestamp: Long): Long {
            val cal = Calendar.getInstance()
            cal.timeInMillis = timestamp
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
