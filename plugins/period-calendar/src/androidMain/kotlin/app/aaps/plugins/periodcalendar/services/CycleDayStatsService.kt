package app.aaps.plugins.periodcalendar.services

import app.aaps.core.data.model.TDD
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
class CycleDayStatsService
    @Inject
    constructor(
        private val tddCalculator: TddCalculator,
        private val profileFunction: ProfileFunction,
        private val cycleCounterService: CycleCounterService,
        private val dateUtil: DateUtil,
        private val aapsLogger: AAPSLogger,
    ) {
        data class CycleDayStats(
            val cycleDay: Int,
            val tddTotal: Double?,
            val tddBasal: Double?,
            val tddBolus: Double?,
            val tddCarbs: Double?,
            val isfMgdl: Double?,
            val ic: Double?,
            val sampleCount: Int,
        )

        data class AggregatedCycleStats(
            val statsByDay: List<CycleDayStats>,
            val maxCycleDay: Int,
        )

        suspend fun getAggregatedStats(daysOfHistory: Int = 90): AggregatedCycleStats {
            val historicalDays = cycleCounterService.getHistoricalCycleDays()
            if (historicalDays.isEmpty()) {
                return AggregatedCycleStats(emptyList(), 0)
            }

            val now = dateUtil.now()
            val cutoffTime = now - daysOfHistory * 24L * 60 * 60 * 1000

            val tddMap = mutableMapOf<Int, MutableList<TDD>>()
            val isfMap = mutableMapOf<Int, MutableList<Double>>()
            val icMap = mutableMapOf<Int, MutableList<Double>>()

            val tddData = tddCalculator.calculate(daysOfHistory.toLong(), allowMissingDays = true)

            if (tddData != null) {
                for (i in 0 until tddData.size()) {
                    val tdd = tddData.valueAt(i)
                    if (tdd.timestamp < cutoffTime) continue

                    val cycleDay = getCycleDayForTimestamp(tdd.timestamp, historicalDays)
                    if (cycleDay > 0) {
                        tddMap.getOrPut(cycleDay) { mutableListOf() }.add(tdd)
                    }
                }
            }

            val profile = profileFunction.getProfile()
            if (profile != null) {
                for (entry in historicalDays) {
                    val timestamp = entry.key
                    val cycleDay = entry.value
                    if (timestamp < cutoffTime) continue

                    val isf = profile.getIsfMgdl("CycleDayStatsService")
                    val ic = profile.getIc(timestamp)

                    isfMap.getOrPut(cycleDay) { mutableListOf() }.add(isf)
                    icMap.getOrPut(cycleDay) { mutableListOf() }.add(ic)
                }
            }

            val allDays = (tddMap.keys + isfMap.keys + icMap.keys).sorted()
            val maxDay = allDays.maxOrNull() ?: 0

            val statsByDay =
                allDays.map { day ->
                    val tdds = tddMap[day]
                    val isfs = isfMap[day]
                    val ics = icMap[day]

                    CycleDayStats(
                        cycleDay = day,
                        tddTotal = tdds?.let { list -> list.map { it.totalAmount }.average() },
                        tddBasal = tdds?.let { list -> list.map { it.basalAmount }.average() },
                        tddBolus = tdds?.let { list -> list.map { it.bolusAmount }.average() },
                        tddCarbs = tdds?.let { list -> list.map { it.carbs }.average() },
                        isfMgdl = isfs?.average(),
                        ic = ics?.average(),
                        sampleCount =
                            maxOf(
                                tdds?.size ?: 0,
                                isfs?.size ?: 0,
                                ics?.size ?: 0,
                            ),
                    )
                }

            return AggregatedCycleStats(statsByDay, maxDay)
        }

        private fun getCycleDayForTimestamp(
            timestamp: Long,
            historicalDays: Map<Long, Int>,
        ): Int {
            val dayStart = dateUtil.beginOfDay(timestamp)
            return historicalDays[dayStart] ?: 0
        }
    }
