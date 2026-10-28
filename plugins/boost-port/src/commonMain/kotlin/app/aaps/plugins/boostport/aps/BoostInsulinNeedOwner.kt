package app.aaps.plugins.boostport.aps

import app.aaps.core.data.model.EPS
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.plugins.aps.openAPSBoostV5.InsulinNeedWatch as Watch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class BoostInsulinNeedNotice(val atMs: Long, val above: Boolean, val recentU: Double,
    val baselineU: Double, val baselineDays: Int, val percent: Double,
    val recentBg: Double?, val baselineBg: Double?)

/** One daily diagnostic evaluation, serialized by the APS owner; never changes a therapy setting. */
internal class BoostInsulinNeedOwner(private val store: KeyValueStore,
    private val persistence: PersistenceLayer, private val tdd: TddCalculator,
    private val beginOfDay: (Long) -> Long) {
    companion object {
        val lastAbove = BoostApsSettingKeys.LongValue("boost_need_watch_last_above_ms", 0L)
        val lastBelow = BoostApsSettingKeys.LongValue("boost_need_watch_last_below_ms", 0L)
        val summary = BoostApsSettingKeys.StringValue("boost_need_watch_summary", "")
        val ownedKeys = listOf(lastAbove, lastBelow, summary)

        /** Match ProfileSealed.EPS.percentageBasalSum: sum the 24 hourly samples of stored blocks. */
        fun dailyBasal(profile: EPS): Double? {
            if (!profile.isValid || profile.basalBlocks.any { it.duration <= 0L || !it.amount.isFinite() || it.amount < 0.0 } ||
                profile.basalBlocks.sumOf { it.duration } != 86_400_000L) return null
            var sum = 0.0
            for (hour in 0..23) {
                var end = 0L
                val block = profile.basalBlocks.first { end += it.duration; hour * 3_600_000L < end }
                sum += block.amount
            }
            return Watch.profileDailyBasal(sum, profile.originalPercentage, profile.originalDuration).takeIf { it.isFinite() }
        }
    }
    private var lastAttempt: Long? = null
    private var pending: BoostInsulinNeedNotice? = null

    suspend fun evaluate(now: Long): BoostInsulinNeedNotice? {
        require(now > 0L)
        if (lastAttempt?.let { now >= it && now - it < 86_400_000L } == true) return null
        lastAttempt = now
        pending = null
        val original = store.getAll()
        val aboveMs = store.getLong(lastAbove.key, 0L)
        val belowMs = store.getLong(lastBelow.key, 0L)
        require(aboveMs >= 0L && belowMs >= 0L)
        val today = beginOfDay(now)
        val yesterday = beginOfDay(today - 1L)
        val rows = tdd.calculate(now, (Watch.BASELINE_DAYS + Watch.WINDOW_DAYS).toLong(), true) ?: return null
        val days = mutableListOf<Watch.Day>()
        for (index in 0 until rows.size()) {
            val start = rows.keyAt(index)
            if (start < 0L || start >= today) continue
            val row = rows.valueAt(index)
            val amount = if (row.totalAmount > 0.0) row.totalAmount else row.basalAmount + row.bolusAmount
            require(amount.isFinite() && amount >= 0.0) { "Invalid insulin-need history" }
            val profile = persistence.getEffectiveProfileSwitchActiveAt(start + 43_200_000L)
            days += Watch.Day(start, amount, profile?.let(::dailyBasal))
        }
        val result = Watch.evaluate(days.sortedBy { it.startMs }, yesterday)
        val notice = if (Watch.shouldNotify(result, aboveMs, belowMs, now)) {
            require(listOf(result.recentMeanU, result.baselineMeanU, result.percent).all { it.isFinite() })
            val end = result.windowStartMs + Watch.WINDOW_DAYS * 86_400_000L
            val bgs = persistence.getBgReadingsDataFromTimeToTime(result.baselineStartMs, end, true)
                .filter { it.isValid && it.timestamp in result.baselineStartMs until end && it.value.isFinite() && it.value >= 39.0 }
            fun mean(recent: Boolean) = bgs.filter { (it.timestamp >= result.windowStartMs) == recent }
                .map { it.value }.takeIf { it.isNotEmpty() }?.average()
            BoostInsulinNeedNotice(now, result.status == Watch.Status.ABOVE, result.recentMeanU,
                result.baselineMeanU, result.baselineDays, result.percent, mean(true), mean(false))
        } else null
        currentCoroutineContext().ensureActive()
        val current = store.getAll()
        check(ownedKeys.all { original[it.key] == current[it.key] }) { "Insulin-need state changed during read" }
        store.edit(commit = true) { putString(summary.key, result.token()) }
        pending = notice
        return notice
    }

    /** Called only after current-generation result publication and successful notification posting. */
    fun acknowledge(notice: BoostInsulinNeedNotice) {
        if (pending != notice) return
        store.edit(commit = true) { putLong(if (notice.above) lastAbove.key else lastBelow.key, notice.atMs) }
        pending = null
    }
}
