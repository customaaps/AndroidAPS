package app.aaps.plugins.boostport

import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.plugins.boostport.aps.*
import java.lang.reflect.Proxy
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class BoostStepCountPublisherTest {
    private val day = 20_000L * 86_400_000L
    private class Db {
        var rows = emptyList<SC>()
        var writes = 0
        private var nextId = 101L
        val api = Proxy.newProxyInstance(PersistenceLayer::class.java.classLoader,
            arrayOf(PersistenceLayer::class.java)) { _, method, args -> when (method.name) {
                "getStepsCountFromTimeToTime" -> rows.filter { it.timestamp in (args[0] as Long)..(args[1] as Long) }
                "insertOrUpdateStepsCounts" -> {
                    @Suppress("UNCHECKED_CAST") val batch = args[0] as List<SC>
                    rows = rows.filterNot { old -> batch.any { it.id != 0L && it.id == old.id } } +
                        batch.map { if (it.id == 0L) it.copy(id = nextId++) else it }
                    writes++
                    PersistenceLayer.TransactionResult<SC>()
                }
                else -> error("Unexpected ${method.name}")
            } } as PersistenceLayer
    }
    private fun row(at: Long, steps: Int, device: String = BoostStepCountPublisher.DEVICE) =
        SC(duration = 300_000L, timestamp = at, steps5min = steps, steps10min = steps,
            steps15min = steps, steps30min = steps, steps60min = steps, steps180min = steps,
            device = device, utcOffset = 0)

    @Test fun phoneSamplesReachTheSameScDatabaseReadByTheGraph() = runBlocking {
        val tracker = BoostPhoneStepTracker()
        assertTrue(tracker.completedRows(day, 0).isEmpty())
        tracker.accept(1000, day + 1)
        assertTrue(tracker.completedRows(day + 1, 0).isEmpty())
        tracker.accept(1042, day + 60_000)
        val samples = tracker.completedRows(day + 5 * 60_000, 0)
        assertEquals(42, samples.single().steps5min)
        val db = Db()
        assertEquals(1, BoostStepCountPublisher(db.api).publish(samples, day + 5 * 60_000))
        assertEquals(42, db.rows.single().steps5min)
    }

    @Test fun hcBucketsKeepMissingIntervalsMissingAndNeverPublishFutureOrInvalidData() {
        val rows = BoostStepCountPublisher.healthConnectRows(mapOf(
            day + 300_000 to 20L, day + 900_000 to 30L,
            day + 1_200_000 to -1L, day + 1_500_000 to 99999L,
            day + 1_800_000 to 10L), day + 1_500_000, 0)
        assertEquals(listOf(day + 300_000, day + 900_000), rows.map { it.timestamp })
        assertEquals(30, rows.last().steps5min)
        assertEquals(50, rows.last().steps15min)
        assertTrue(BoostStepCountPublisher.healthConnectRows(emptyMap(), day, 0).isEmpty())
    }

    @Test fun phoneAndHealthConnectAreNotAddedAndRestartDoesNotDuplicateRows() = runBlocking {
        val db = Db()
        val time = day + 300_000
        val owner = BoostStepCountPublisher(db.api)
        assertEquals(1, owner.publish(listOf(row(time, 40), row(time, 50)), time))
        assertEquals(50, db.rows.single().steps5min)
        assertEquals(0, BoostStepCountPublisher(db.api).publish(listOf(row(time, 40)), time))
        assertEquals(1, owner.publish(listOf(row(time, 60)), time))
        assertEquals(1, db.rows.size)
        assertEquals(60, db.rows.single().steps5min)
    }

    @Test fun existingWearRowsAreNeverOverwrittenAndUnknownFeedCreatesNothing() = runBlocking {
        val db = Db()
        val time = day + 300_000
        db.rows = listOf(row(time, 70, "Wear").copy(id = 100))
        val owner = BoostStepCountPublisher(db.api)
        assertEquals(0, owner.publish(emptyList(), time))
        assertEquals(1, owner.publish(listOf(row(time, 40)), time))
        assertEquals(70, db.rows.single { it.device == "Wear" }.steps5min)
        assertEquals(2, db.rows.size)
    }
}
