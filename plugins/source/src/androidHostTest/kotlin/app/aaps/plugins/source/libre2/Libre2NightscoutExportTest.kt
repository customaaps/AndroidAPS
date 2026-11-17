package app.aaps.plugins.source.libre2

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.source.NSClientSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Libre2NightscoutExportTest {
    @get:Rule val folder = TemporaryFolder()
    private val start = 1_800_000_000_000L
    private val now = start + 22 * 60_000
    private val persistence = mock<PersistenceLayer>()
    private val source = mock<NSClientSource>()
    private val logger = mock<AAPSLogger>()
    private val glucoseChanges = MutableSharedFlow<List<GV>>(extraBufferCapacity = 8)
    private val therapyChanges = MutableSharedFlow<List<TE>>(extraBufferCapacity = 8)
    private var selected = true
    private var enabled = true
    private var recent = emptyList<GV>()
    private var changes = emptyList<TE>()
    private var pairing: Libre2Pairing? = Libre2Pairing(
        byteArrayOf(0xF6.toByte(), 0x1D, 0x6D, 6, 0, 0xA0.toByte(), 7, 0xE0.toByte()),
        ByteArray(6), "00:11:22:33:44:55", byteArrayOf(), start / 1000, false,
        Libre2DecoderState(sensorStartSeconds = start / 1000))
    private lateinit var queue: Libre2UploadQueue

    private fun value(id: Int) = GV(timestamp = start + id * 60_000, value = 110.0, raw = null,
        noise = null, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2)
    private fun insertion(time: Long = start) = TE(timestamp = time, type = TE.Type.SENSOR_CHANGE,
        glucoseUnit = GlucoseUnit.MGDL)
    private fun exporter(export: (GV, Long) -> Unit = { value, sensorStart ->
        externalLibre2Reading(value, sensorStart, pairing, now)?.let { queue.add(it, now) }
    }) = Libre2NightscoutExport(persistence, source, logger, { enabled }, export) { now }

    @Before fun setup() = runBlocking<Unit> {
        queue = Libre2UploadQueue(File(folder.root, "queue.json"))
        changes = listOf(insertion())
        whenever(source.isEnabled()).thenAnswer { selected }
        whenever(persistence.observeChanges(GV::class)).thenReturn(glucoseChanges)
        whenever(persistence.observeChanges(TE::class)).thenReturn(therapyChanges)
        whenever(persistence.getTherapyEventDataFromTime(any(), eq(TE.Type.SENSOR_CHANGE), eq(true))).thenAnswer { changes }
        whenever(persistence.getBgReadingsDataFromTimeToTime(any(), any(), eq(true))).thenAnswer { recent }
    }

    @Test fun `committed Nightscout rows enter the queue and form history using the NFC sensor identity`() = runTest {
        val exporter = exporter()
        exporter.start(backgroundScope)
        runCurrent()
        val readings = (8..22).map(::value)
        glucoseChanges.emit(readings)
        runCurrent()
        val batch = queue.batch(now)
        assertEquals(15, batch.size)
        assertEquals(1, Libre2UploadSummary.from(batch).history)
        assertTrue(batch.all { it.reading.sensorId == "F61D6D0600A007E0" })
        assertEquals(readings.map { it.timestamp }, batch.map { it.reading.timestamp })
        glucoseChanges.emit(readings)
        runCurrent()
        assertEquals(batch, queue.batch(now))
    }

    @Test fun `startup backfill is bounded and late sensor insertion retries the recent readings`() = runTest {
        recent = (8..22).map(::value)
        changes = emptyList()
        exporter().start(backgroundScope)
        runCurrent()
        assertTrue(queue.batch(now).isEmpty())
        verify(persistence).getBgReadingsDataFromTimeToTime(now - 30 * 60_000L, now, true)
        changes = listOf(insertion())
        therapyChanges.emit(changes)
        runCurrent()
        assertEquals(15, queue.batch(now).size)
        assertEquals(1, Libre2UploadSummary.from(queue.batch(now)).history)
    }

    @Test fun `disabled upload or a different selected BG source prevents export`() = runTest {
        var exports = 0
        val exporter = exporter { _, _ -> exports++ }
        selected = false
        exporter.onStored(listOf(value(22)))
        exporter.backfill()
        selected = true
        enabled = false
        exporter.onStored(listOf(value(22)))
        exporter.backfill()
        assertEquals(0, exports)
        verify(persistence, never()).getTherapyEventDataFromTime(any(), eq(TE.Type.SENSOR_CHANGE), eq(true))
        verify(persistence, never()).getBgReadingsDataFromTimeToTime(any(), any(), any())
    }

    @Test fun `missing NFC identity or mismatched sensor insertion cannot upload another sensors glucose`() = runTest {
        val exporter = exporter()
        changes = listOf(insertion(start - 600_000))
        exporter.onStored(listOf(value(22)))
        changes = listOf(insertion().copy(isValid = false))
        exporter.onStored(listOf(value(22)))
        changes = listOf(insertion())
        pairing = null
        exporter.onStored(listOf(value(22)))
        assertTrue(queue.batch(now).isEmpty())
    }

    @Test fun `each reading is matched to the insertion in effect at its timestamp`() = runTest {
        val newStart = start + 20 * 60_000
        pairing = pairing!!.copy(state = Libre2DecoderState(sensorStartSeconds = newStart / 1000))
        changes = listOf(insertion(), insertion(newStart))
        exporter().onStored(listOf(value(19), value(22)))
        val batch = queue.batch(now)
        assertEquals(1, batch.size)
        assertEquals(value(22).timestamp, batch.single().reading.timestamp)
        assertEquals(newStart, batch.single().reading.sensorStart)
    }

    @Test fun `invalid archived future and non Libre2 rows are ignored`() = runTest {
        var exports = 0
        exporter { _, _ -> exports++ }.onStored(listOf(
            value(22).copy(isValid = false),
            value(22).copy(referenceId = 123),
            value(22).copy(sourceSensor = SourceSensor.UNKNOWN),
            value(23),
            value(22).copy(timestamp = now - Libre2UploadQueue.RETENTION_MS - 1)
        ))
        assertEquals(0, exports)
        verify(persistence, never()).getTherapyEventDataFromTime(any(), eq(TE.Type.SENSOR_CHANGE), eq(true))
    }

    @Test fun `collector cancellation stops export and one export failure does not stop later DB events`() = runTest {
        var calls = 0
        val exporter = exporter { _, _ ->
            calls++
            if (calls == 1) throw IOException("queue temporarily unavailable")
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            exporter.start(scope)
            runCurrent()
            glucoseChanges.emit(listOf(value(21)))
            runCurrent()
            glucoseChanges.emit(listOf(value(22)))
            runCurrent()
            assertEquals(2, calls)
            scope.cancel()
            glucoseChanges.emit(listOf(value(22)))
            runCurrent()
            assertEquals(2, calls)
        } finally { scope.cancel() }
    }
}
