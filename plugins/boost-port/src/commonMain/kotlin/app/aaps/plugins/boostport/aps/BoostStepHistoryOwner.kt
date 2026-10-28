package app.aaps.plugins.boostport.aps

import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.utils.Round
import app.aaps.plugins.aps.openAPSBoost.StepSourceResolver
import app.aaps.plugins.aps.openAPSBoost.activity.DailyStepHistoryTracker as Tracker
import app.aaps.plugins.aps.openAPSBoost.activity.WearStepSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** End-of-cycle shadow owner: completed-day history and bank commit in the same edit.
 * Source factors are telemetry only and never modify the calculation/profile.
 */
internal class BoostStepHistoryOwner(private val store: KeyValueStore, private val persistence: PersistenceLayer) {
    private var lastWearRead: Long? = null

    suspend fun completeCycle(result: RT, nowMs: Long, offsetMinutes: Int,
        phone: BoostApsActivitySource.Snapshot, hc: BoostStepHistorySnapshot?) {
        require(nowMs > 0 && offsetMinutes in -1080..1080)
        val offset = offsetMinutes * 60_000L
        val today = Tracker.dayIndex(nowMs, offset)
        val dayStart = today * 86_400_000L - offset
        val historyKey = BoostApsSettingKeys.ApsBoostDailyStepHistory.key
        val bankKey = BoostApsSettingKeys.ApsBoostIntradayStepBank.key
        val rawHistory = store.getString(historyKey, "")
        val rawBank = store.getString(bankKey, "")
        validateHistory(rawHistory)
        if (rawBank.isNotBlank()) {
            require(rawBank.length <= 1_048_576)
            val root = Json.parseToJsonElement(rawBank).jsonObject
            require((root["d"]?.jsonPrimitive?.longOrNull ?: -2L) >= -1L && root["max"] is JsonObject)
            require(root.getValue("max").jsonObject.size <= 64)
            require(root.getValue("max").jsonObject.values.all { (it.jsonPrimitive.intOrNull ?: -1) >= 0 })
        }
        var multi = Tracker.MultiSourceHistory.deserialize(rawHistory)
        val previousBank = Tracker.IntradayStepBank.deserialize(rawBank)
        val snapshot = hc?.takeIf { it.offsetMinutes == offsetMinutes && it.capturedAtMs <= nowMs &&
            nowMs - it.capturedAtMs <= 2 * 3_600_000L }
        fun count(v: Long) = v.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        snapshot?.perSourceDay?.forEach { (source, days) ->
            multi = Tracker.mergeSource(multi, source, days.map { (day, steps) ->
                Tracker.DailyTotal(day, count(steps), source) }, today)
        }
        snapshot?.phoneDeviceDay?.let { days ->
            multi = Tracker.mergeSource(multi, StepSourceResolver.PHONE, days.map { (day, steps) ->
                Tracker.DailyTotal(day, count(steps), StepSourceResolver.PHONE) }, today)
        }
        // Completed Wear history is expensive; current-day rows are needed each cycle.
        val wearDue = lastWearRead?.let { nowMs >= it && nowMs - it < 3_600_000L } != true
        if (wearDue) {
            val rows = persistence.getStepsCountFromTimeToTime((today - Tracker.WINDOW_DAYS) * 86_400_000L - offset, nowMs)
                .filter { it.isValid && it.timestamp <= nowMs && it.steps5min >= 0 }
            val daily = WearStepSource.dailyTotals(rows, today, offset)
            require(daily.all { it.steps >= 0 }) { "Invalid Wear daily total" }
            multi = Tracker.mergeSource(multi, StepSourceResolver.WEAR, daily, today)
        }
        val todayRows = persistence.getStepsCountFromTimeToTime(dayStart, nowMs)
            .filter { it.isValid && it.timestamp in dayStart..nowMs && it.steps5min >= 0 }
        val wearToday = WearStepSource.stepsToday(todayRows, dayStart, nowMs)
        require(wearToday >= 0 && phone.stepsToday >= 0)
        val counts = linkedMapOf(StepSourceResolver.WEAR to wearToday, StepSourceResolver.PHONE to phone.stepsToday)
        if (snapshot?.todayIndex == today) snapshot.perSourceDay.forEach { (raw, days) ->
            val source = StepSourceResolver.canonical(raw)
            if (source != StepSourceResolver.WEAR && source != StepSourceResolver.PHONE)
                counts[source] = maxOf(counts[source] ?: 0, count(days[today] ?: 0L))
        }
        val banked = Tracker.bankCycle(previousBank, today, counts)
        banked.closedDayTotals.forEach { multi = Tracker.mergeSource(multi, it.source, listOf(it), today) }
        // Trim all persisted sources even if an adapter stopped producing records.
        multi.sources.keys.toList().forEach { multi = Tracker.mergeSource(multi, it, emptyList(), today) }
        val states = mutableListOf(
            StepSourceResolver.SourceState(StepSourceResolver.WEAR, WearStepSource.isRecentlyFresh(todayRows, nowMs),
                multi.sources[StepSourceResolver.WEAR]?.days?.size ?: 0, wearToday),
            StepSourceResolver.SourceState(StepSourceResolver.PHONE, phone.phoneFeedLive,
                multi.sources[StepSourceResolver.PHONE]?.days?.size ?: 0, phone.stepsToday))
        counts.filterKeys { it != StepSourceResolver.WEAR && it != StepSourceResolver.PHONE }.forEach { (source, steps) ->
            states += StepSourceResolver.SourceState(source, false, multi.sources[source]?.days?.size ?: 0, steps)
        }
        val resolved = StepSourceResolver.resolve(states)
        val bridge = Tracker.phoneAnchoredWindow(multi, today)
        val factors = Tracker.shadowFactors(bridge.history, today)
        val hour = ((nowMs / 60_000L + offsetMinutes) % 1440 / 60).toInt()
        val intraday = Tracker.intradayFactor(resolved.stepsToday, factors.baselineSteps, hour)
        val nextHistory = multi.serialize()
        val nextBank = banked.bank.serialize()
        currentCoroutineContext().ensureActive()
        check(store.getString(historyKey, "") == rawHistory && store.getString(bankKey, "") == rawBank) {
            "Step history changed during read"
        }
        if (nextHistory != rawHistory || nextBank != rawBank) store.edit(commit = true) {
            putString(historyKey, nextHistory); putString(bankKey, nextBank)
        }
        if (wearDue) lastWearRead = nowMs
        result.boostActivityLoad_baselineSteps = factors.baselineSteps
        result.boostActivityLoad_lastDaySteps = factors.lastDaySteps
        result.boostActivityLoad_ratio = factors.ratio?.let { Round.roundTo(it, 0.01) }
        result.boostActivityLoad_wouldDeltaIsfPct = Round.roundTo(factors.wouldDeltaIsfPct, 0.1)
        result.boostActivityLoad_source = resolved.active
        result.boostActivitySource_resolved = resolved.active
        result.boostActivitySource_states = resolved.note
        result.boostActivitySource_bridge = if (bridge.donorsUsed.isEmpty()) "phone" else
            "phone<-" + bridge.donorsUsed.joinToString("+") + if (bridge.calibrated) "" else "(raw)"
        result.boostActivityLoad_stepsToday = resolved.stepsToday
        result.boostActivityLoad_stepsSource = resolved.active
        result.boostActivityLoad_intradayRatio = intraday.ratio?.let { Round.roundTo(it, 0.01) }
        result.boostActivityLoad_intradayDeltaIsfPct = Round.roundTo(intraday.wouldDeltaIsfPct, 0.1)
        if (banked.closedDayTotals.isNotEmpty()) result.reason.append("stepHistory: day-close banked " +
            banked.closedDayTotals.joinToString(", ") { "${it.source} ${it.steps}" } + "; ")
        bridge.heldNote?.let { result.reason.append("stepHistory: $it; ") }
        result.reason.append("activityLoad: ${resolved.active ?: "none"} base ${factors.baselineSteps ?: "unknown"} " +
            "last ${factors.lastDaySteps ?: "unknown"} wouldDeltaISF ${result.boostActivityLoad_wouldDeltaIsfPct}%; ")
    }

    private fun validateHistory(raw: String) {
        if (raw.isBlank()) return
        require(raw.length <= 1_048_576)
        val root = Json.parseToJsonElement(raw).jsonObject
        val arrays = if (root["sources"] is JsonObject) root.getValue("sources").jsonObject.values.toList()
            else listOf(root.getValue("days")) // original single-source import
        require(arrays.size <= 64)
        arrays.forEach { array ->
            require(array.jsonArray.size <= 366)
            array.jsonArray.forEach {
                val day = it.jsonObject
                require((day["d"]?.jsonPrimitive?.longOrNull ?: -1L) >= 0L &&
                    (day["s"]?.jsonPrimitive?.intOrNull ?: -1) >= 0)
            }
        }
    }
}
