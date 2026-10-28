package app.aaps.plugins.sync.nsclientV3.workers

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.nsclient.NSClientRepository
import app.aaps.core.interfaces.nsclient.StoreDataForDb
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.sync.nsclientV3.NSClientV3Plugin
import app.aaps.plugins.sync.nsclientV3.NsIncomingDataProcessor
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Runs inside the existing serialized BG worker, before its normal load.
 * Local date cursors never replace the client's incremental synchronization cursors.
 * Acceptance override is scoped to these bounded pages, never to WS or another worker.
 */
@Inject
class LoadHistoryBackfillRunner(
    private val plugin: NSClientV3Plugin,
    private val processor: NsIncomingDataProcessor,
    private val store: StoreDataForDb,
    private val dateUtil: DateUtil,
    private val logger: AAPSLogger,
    private val repository: NSClientRepository
) {
    suspend fun run() {
        val request = plugin.takeHistoryBackfill() ?: return
        suspend fun active() {
            currentCoroutineContext().ensureActive()
            check(plugin.historyBackfillAllowed(request)) { "History backfill expired or client changed" }
        }
        // Consumed once even on failure. The persisted Boost owner owns retries and their limits.
        try {
            var cursor = request.fromMs
            var complete = false
            repeat(MAX_PAGES) {
                if (!complete) {
                    active()
                    val page = request.client.getSgvsNewerThan(cursor, NSClientV3Plugin.RECORDS_TO_LOAD).values
                    active()
                    if (page.isEmpty()) complete = true
                    else {
                        val dates = page.map { requireNotNull(it.date) { "Missing glucose date" } }
                        val next = dates.maxOrNull()!!
                        check(next > cursor) { "Glucose history cursor stalled" }
                        val bounded = page.filter { it.date!! > cursor && it.date!! <= request.toMs }.sortedBy { it.date }
                        if (bounded.isNotEmpty()) {
                            check(processor.processSgvs(bounded, doFullSync = true, advanceInitialLoadCursor = false)) {
                                "Glucose history page rejected"
                            }
                            store.storeGlucoseValuesToDb()
                        }
                        cursor = next
                        complete = next >= request.toMs
                    }
                }
            }
            check(complete) { "Glucose history page limit reached" }
            cursor = request.fromMs
            complete = false
            repeat(MAX_PAGES) {
                if (!complete) {
                    active()
                    val page = request.client.getTreatmentsNewerThan(dateUtil.toISOString(cursor), NSClientV3Plugin.RECORDS_TO_LOAD).values
                    active()
                    if (page.isEmpty()) complete = true
                    else {
                        val dates = page.map { requireNotNull(it.date) { "Missing treatment date" } }
                        val next = dates.maxOrNull()!!
                        check(next > cursor) { "Treatment history cursor stalled" }
                        val bounded = page.filter { it.date!! > cursor && it.date!! <= request.toMs }.sortedBy { it.date }
                        if (bounded.isNotEmpty()) {
                            check(processor.processTreatments(bounded, doFullSync = true, advanceInitialLoadCursor = false)) {
                                "Treatment history page rejected"
                            }
                            store.storeTreatmentsToDb(fullSync = true)
                        }
                        cursor = next
                        complete = next >= request.toMs
                    }
                }
            }
            check(complete) { "Treatment history page limit reached" }
            repository.addLog("◄ BOOST HISTORY", "Bounded history download completed")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.error("Boost history download failed", error)
            repository.addLog("◄ BOOST HISTORY", "Incomplete: ${error.message}")
            // An optional backfill failure must not stop normal synchronization.
        } finally {
            plugin.finishHistoryBackfill(request)
        }
    }

    private companion object { const val MAX_PAGES = 100 }
}
