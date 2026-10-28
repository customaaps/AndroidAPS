package app.aaps.plugins.boostport

import app.aaps.core.data.model.*
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.plugins.boostport.aps.BoostTrancheHistoryReader
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class BoostTrancheHistoryReaderTest {
    private val day = 86_400_000L
    private val now = 20_000L * day
    private inline fun <reified T> proxy(noinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T
    private fun cycle(time: Long, state: String, active: Boolean = true) = proxy<APSResult> { name, _ ->
        when (name) {
            "getDate" -> time
            "rawData" -> RT(runningDynamicIsf = false, boostV5_active = active, boostV5_state = state)
            else -> error(name)
        }
    }
    private fun bg(time: Long, valid: Boolean = true) = GV(timestamp = time, value = 120.0, raw = 0.0,
        trendArrow = TrendArrow.NONE, noise = null, sourceSensor = SourceSensor.UNKNOWN, isValid = valid)

    @Test fun nativeOnsetsSurviveDayBoundariesAndFiveMinuteBuckets(): Unit = runBlocking {
        val onset = now - day
        val rows = listOf(cycle(onset - 60_000L, "OBSERVING"), cycle(onset, "CONFIRMED"),
            cycle(onset + 60_000L, "COMMITTED"), cycle(onset + 120_000L, "CONFIRMED"),
            cycle(onset + 180_000L, "CONFIRMED"), cycle(onset + 240_000L, "OBSERVING", false))
        val ranges = mutableListOf<Pair<Long, Long>>()
        val db = proxy<PersistenceLayer> { name, args -> when (name) {
            "getApsResults" -> {
                val from = args[0] as Long; val end = args[1] as Long
                ranges += from to end
                // Deliberately return all rows to exercise the reader's range guard as well.
                rows.reversed()
            }
            "getBgReadingsDataFromTimeToTime" -> (-10..250).map { bg(onset + it * 60_000L) }
            else -> error(name)
        } }
        val result = BoostTrancheHistoryReader(db).read(now)
        assertEquals(listOf(onset, onset + 120_000L), result.episodes.map { it.confirmMs })
        assertEquals(45, ranges.size)
        assertEquals(now - 45 * day, ranges.first().first)
        assertEquals(now - 1, ranges.last().second)
        assertTrue(ranges.zipWithNext().all { (a, b) -> a.second + 1 == b.first })
        assertNull(result.threshold) // two episodes cannot authorize a threshold change
    }

    @Test fun cancellationStopsHistoryReads(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var reads = 0
        val unsupported = proxy<PersistenceLayer> { name, _ -> error(name) }
        val db = object : PersistenceLayer by unsupported {
            override suspend fun getApsResults(start: Long, end: Long): List<APSResult> {
                reads++; entered.complete(Unit); awaitCancellation()
            }
        }
        val job = launch { BoostTrancheHistoryReader(db).read(now) }
        entered.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, reads)
    }

    @Test fun stateFieldsRoundTripAndOldResultsRemainReadable() {
        val rt = RT(runningDynamicIsf = false, boostV5_active = true, boostV5_state = "CONFIRMED")
        val restored = Json.decodeFromString<RT>(Json.encodeToString(rt))
        assertEquals(true, restored.boostV5_active)
        assertEquals("CONFIRMED", restored.boostV5_state)
        val old = Json.decodeFromString<RT>("{\"runningDynamicIsf\":false}")
        assertNull(old.boostV5_active); assertNull(old.boostV5_state)
    }
}
