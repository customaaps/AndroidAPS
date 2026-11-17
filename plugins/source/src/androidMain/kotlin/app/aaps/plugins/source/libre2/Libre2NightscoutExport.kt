package app.aaps.plugins.source.libre2

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.interfaces.source.NSClientSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Observes committed DB rows; optional export never participates in Nightscout ingestion. */
internal class Libre2NightscoutExport(
    private val persistenceLayer: PersistenceLayer,
    private val source: NSClientSource,
    private val logger: AAPSLogger,
    private val enabled: () -> Boolean,
    private val export: (GV, Long) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    fun start(scope: CoroutineScope) {
        // Subscribe before loading recent rows so an arrival during startup cannot be missed.
        persistenceLayer.observeChanges(GV::class)
            .collectResilient(scope, logger, LTag.BGSOURCE, start = CoroutineStart.UNDISPATCHED) { onStored(it) }
        persistenceLayer.observeChanges(TE::class)
            .collectResilient(scope, logger, LTag.BGSOURCE, start = CoroutineStart.UNDISPATCHED) { events ->
                // Treatments may arrive after the glucose rows. Retry the recent window when identity becomes available.
                if (events.any { it.type == TE.Type.SENSOR_CHANGE }) backfill()
            }
        flowOf(Unit).collectResilient(scope, logger, LTag.BGSOURCE) { backfill() }
    }

    internal suspend fun backfill() {
        if (!source.isEnabled() || !enabled()) return
        val end = now()
        onStored(persistenceLayer.getBgReadingsDataFromTimeToTime(end - RECENT_WINDOW_MS, end, true))
    }

    internal suspend fun onStored(values: List<GV>) = mutex.withLock {
        if (!source.isEnabled() || !enabled()) return@withLock
        val end = now()
        val eligible = values.filter {
            it.isValid && it.referenceId == null && it.sourceSensor == SourceSensor.LIBRE_2 &&
                it.timestamp <= end && end - it.timestamp <= Libre2UploadQueue.RETENTION_MS
        }.distinctBy { it.timestamp }.sortedBy { it.timestamp }
        if (eligible.isEmpty()) return@withLock
        val changes = persistenceLayer.getTherapyEventDataFromTime(
            eligible.first().timestamp - SENSOR_LIFETIME_MS, TE.Type.SENSOR_CHANGE, true
        ).filter { it.isValid && it.type == TE.Type.SENSOR_CHANGE }.sortedBy { it.timestamp }
        for (value in eligible) {
            currentCoroutineContext().ensureActive()
            if (!source.isEnabled() || !enabled()) return@withLock
            // Match the insertion in effect at THIS reading, not the phone's newest sensor.
            // A missing insertion passes 0 and is rejected by the existing NFC identity check.
            val start = changes.lastOrNull { it.timestamp <= value.timestamp }?.timestamp ?: 0L
            export(value, start)
        }
    }

    companion object {
        private const val RECENT_WINDOW_MS = 30L * 60_000
        private const val SENSOR_LIFETIME_MS = 14L * 24 * 60 * 60_000
    }
}
