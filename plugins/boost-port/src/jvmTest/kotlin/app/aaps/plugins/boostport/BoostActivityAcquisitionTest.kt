package app.aaps.plugins.boostport

import app.aaps.core.data.model.HR
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.plugins.boostport.aps.*
import java.lang.reflect.Proxy
import kotlin.test.*
import kotlinx.coroutines.*

class BoostActivityAcquisitionTest {
    private val day = 20_000L * 86_400_000L

    @Test fun phoneBucketsKeepSourceCompletedWindowRulesAndUnknownIsNotInactivity() {
        val tracker = BoostPhoneStepTracker()
        assertFalse(tracker.snapshot(day, 0).phoneFeedLive)
        tracker.accept(1000, day + 1)
        assertTrue(tracker.snapshot(day + 2, 0).phoneFeedLive)
        tracker.accept(1100, day + 60_000L)
        assertEquals(0, tracker.snapshot(day + 60_001L, 0).steps5)
        assertEquals(100, tracker.snapshot(day + 5 * 60_000L, 0).steps5)
        assertEquals(100, tracker.snapshot(day + 5 * 60_000L, 0).stepsToday)
        tracker.accept(10, day + 6 * 60_000L) // sensor reset: no negative bucket; today adds post-reset raw
        assertEquals(110, tracker.snapshot(day + 6 * 60_000L, 0).stepsToday)
        assertEquals(0, tracker.snapshot(day + 86_400_000L, 0).stepsToday)
    }

    @Test fun invalidGlitchAndClockReversalDoNotManufactureActivity() {
        val tracker = BoostPhoneStepTracker()
        tracker.accept(-1, day)
        assertFalse(tracker.snapshot(day, 0).phoneFeedLive)
        tracker.accept(100, day)
        tracker.accept(5000, day + 60_000L)
        assertEquals(0, tracker.snapshot(day + 5 * 60_000L, 0).steps5)
        assertFalse(tracker.snapshot(day - 1, 0).phoneFeedLive)
    }

    private class Fixture {
        var existing = emptyList<HR>()
        var samples = emptyList<BoostHeartRateReader.Sample>()
        var reads = 0
        var failWrite = false
        var unavailable = false
        val batches = mutableListOf<List<HR>>()
        val api = Proxy.newProxyInstance(PersistenceLayer::class.java.classLoader,
            arrayOf(PersistenceLayer::class.java)) { _, method, args -> when (method.name) {
                "getHeartRatesFromTimeToTime" -> existing
                "insertOrUpdateHeartRates" -> {
                    if (failWrite) error("write failed")
                    @Suppress("UNCHECKED_CAST") val batch = args[0] as List<HR>
                    batches.add(batch); existing = existing + batch
                    PersistenceLayer.TransactionResult<HR>()
                }
                else -> error("Unexpected ${method.name}")
            } } as PersistenceLayer
        val reader = object : BoostHeartRateReader {
            override suspend fun read(nowMs: Long, coveredMinutes: Set<Long>): BoostHeartRateReader.Result {
                reads++
                return if (unavailable) BoostHeartRateReader.Result.Unavailable("permission-required")
                else BoostHeartRateReader.Result.Ready(samples)
            }
        }
        fun owner() = BoostHealthConnectIngest(api, reader)
    }

    @Test fun delayedBackfillCovers18hAndLiveMinutesWinWithoutDoubleWeighting() = runBlocking {
        val f = Fixture(); val now = day + 20 * 3_600_000L
        f.existing = listOf(HR(duration = 60_000L, timestamp = now - 60_000L, beatsPerMinute = 80.0, device = "Garmin"))
        f.samples = listOf(
            BoostHeartRateReader.Sample(now - 60_000L + 1, 60.0, "HealthConnect"),
            BoostHeartRateReader.Sample(now - 12 * 3_600_000L, 55.0, "HealthConnect"),
            BoostHeartRateReader.Sample(now - 12 * 3_600_000L, 56.0, "OtherWriter"),
            BoostHeartRateReader.Sample(now + 86_400_000L, 90.0, "Future"),
            BoostHeartRateReader.Sample(now - 19 * 3_600_000L, 50.0, "TooOld"),
            BoostHeartRateReader.Sample(now - 3 * 60_000L, Double.NaN, "Invalid"))
        val owner = f.owner()
        assertEquals(1, owner.syncIfDue(now, 5))
        assertEquals(55.0, f.batches.single().single().beatsPerMinute)
        assertEquals(60_000L, f.batches.single().single().duration)
        assertEquals(0, owner.syncIfDue(now + 60_000L, 5))
        assertEquals(1, f.reads)
        // A late batch arrives with an old sample timestamp. Re-sweep, not a high-water mark.
        f.samples = f.samples + BoostHeartRateReader.Sample(now - 10 * 3_600_000L, 58.0, "HealthConnect")
        assertEquals(1, owner.syncIfDue(now + 5 * 60_000L, 5))
        assertEquals(2, f.batches.sumOf { it.size })
    }

    @Test fun distinctSourcePointsWithinOneMinuteKeepOriginalSampleCadenceAndWeights() = runBlocking {
        val f = Fixture(); val now = day + 20 * 3_600_000L
        f.samples = listOf(BoostHeartRateReader.Sample(now - 10_000L, 60.0, "HC"),
            BoostHeartRateReader.Sample(now - 20_000L, 80.0, "HC"),
            BoostHeartRateReader.Sample(now - 10_000L, 90.0, "DuplicateWriter"))
        val owner = f.owner()
        assertEquals(2, owner.syncIfDue(now, 5))
        assertEquals(listOf(60.0, 80.0), f.batches.single().map { it.beatsPerMinute })
        assertTrue(f.batches.single().all { it.duration == 60_000L })
        assertEquals(0, owner.syncIfDue(now + 5 * 60_000L, 5))
    }

    @Test fun failedPersistRemainsRetryableAndRestartDedupUsesDatabaseHistory() = runBlocking {
        val f = Fixture(); val now = day + 20 * 3_600_000L
        f.samples = listOf(BoostHeartRateReader.Sample(now - 10 * 3_600_000L, 55.0, "HC"))
        val owner = f.owner(); f.failWrite = true
        assertFailsWith<IllegalStateException> { owner.syncIfDue(now, 5) }
        f.failWrite = false
        assertEquals(1, owner.syncIfDue(now + 5 * 60_000L, 5))
        assertEquals(0, f.owner().syncIfDue(now + 10 * 60_000L, 5))
        assertEquals(1, f.batches.size)
    }

    @Test fun missingPermissionProducesNoSyntheticSamplesOrPersistence() = runBlocking {
        val f = Fixture().apply { unavailable = true }
        assertEquals(0, f.owner().syncIfDue(day, 5))
        assertTrue(f.batches.isEmpty())
    }

    @Test fun concurrentPollsCoalesceAndCancellationCannotPersistAnIncompleteRead() = runBlocking {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val reader = object : BoostHeartRateReader {
            override suspend fun read(nowMs: Long, coveredMinutes: Set<Long>): BoostHeartRateReader.Result {
                entered.complete(Unit); release.await()
                return BoostHeartRateReader.Result.Ready(listOf(BoostHeartRateReader.Sample(day - 1, 60.0, "HC")))
            }
        }
        val owner = BoostHealthConnectIngest(f.api, reader)
        val poll = launch { owner.syncIfDue(day, 5) }
        entered.await()
        assertEquals(0, owner.syncIfDue(day, 5))
        poll.cancelAndJoin(); release.complete(Unit)
        assertTrue(f.batches.isEmpty())
    }
}
