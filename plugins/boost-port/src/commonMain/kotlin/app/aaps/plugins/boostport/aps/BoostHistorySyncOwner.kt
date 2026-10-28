package app.aaps.plugins.boostport.aps

import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.sync.NsClient
import app.aaps.plugins.aps.openAPSBoostV5.BoostHistorySync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Owned by the sequential APS session. Queue acceptance is not proof of recovered history. */
internal class BoostHistorySyncOwner(
    private val store: KeyValueStore,
    private val client: () -> NsClient?,
    private val iso: (Long) -> String
) {
    private var requestedClient: NsClient? = null
    fun close() {
        val retired = requestedClient
        requestedClient = null
        retired?.cancelHistoryBackfill()
    }

    companion object {
        val attempts = BoostApsSettingKeys.IntValue("boost_history_sync_attempts", 0)
        val lastAttempt = BoostApsSettingKeys.LongValue("boost_history_sync_last_attempt_ms", 0L)
        val firstSeen = BoostApsSettingKeys.LongValue("boost_history_sync_first_seen_ms", 0L)
        val preBg = BoostApsSettingKeys.IntValue("boost_history_sync_pre_bg", 0)
        val preTreatments = BoostApsSettingKeys.IntValue("boost_history_sync_pre_treatments", 0)
        val ownedKeys = listOf(attempts, lastAttempt, firstSeen, preBg, preTreatments)
    }

    suspend fun evaluate(now: Long, history: BoostHistorySync.History) {
        require(now > 0)
        val original = store.getAll()
        val state = BoostHistorySync.State(store.getInt(attempts.key, 0), store.getLong(lastAttempt.key, 0L),
            store.getInt(preBg.key, 0), store.getInt(preTreatments.key, 0), store.getLong(firstSeen.key, 0L))
        require(state.lastAttemptMs >= 0) { "Invalid Boost history retry clock" }
        val ns = client()
        val decision = BoostHistorySync.decide(history, ns != null && ns.address.isNotBlank(), now, iso(now), state)
        currentCoroutineContext().ensureActive()
        val current = store.getAll()
        check(ownedKeys.all { original[it.key] == current[it.key] }) { "Boost history state changed" }
        if (!decision.requestBackfill) {
            persist(decision.newState, decision.summary)
            return
        }
        // Reserve before the external side effect: a crash cannot enqueue repeatedly without
        // consuming a persisted attempt. A synchronous decline restores the source attempt count.
        val reserved = checkNotNull(decision.newState)
        persist(reserved, "queued:${BoostHistorySync.BACKFILL_DAYS}d,attempt=${reserved.attempts}@${iso(now)}")
        currentCoroutineContext().ensureActive()
        val accepted = try {
            requestedClient = ns
            val queued = ns?.requestHistoryBackfill(now - BoostHistorySync.BACKFILL_DAYS * 86_400_000L, now) == true
            currentCoroutineContext().ensureActive()
            queued
        } catch (cancelled: CancellationException) {
            ns?.cancelHistoryBackfill()
            throw cancelled
        } catch (_: Exception) {
            false
        }
        currentCoroutineContext().ensureActive()
        if (accepted) persist(null, decision.summary)
        else persist(reserved.copy(attempts = state.attempts),
            "skipped:ns-declined,${BoostHistorySync.shortfall(history)}@${iso(now)}")
    }

    private fun persist(state: BoostHistorySync.State?, summary: String?) {
        if (state == null && summary == null) return
        store.edit(commit = true) {
            state?.let {
                putInt(attempts.key, it.attempts)
                putLong(lastAttempt.key, it.lastAttemptMs)
                putLong(firstSeen.key, it.firstSeenMs)
                putInt(preBg.key, it.preBgReadings)
                putInt(preTreatments.key, it.preTreatments)
            }
            summary?.let { putString(BoostApsSettingKeys.ApsBoostHistorySyncSummary.key, it) }
        }
    }
}
