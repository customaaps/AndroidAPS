package app.aaps.plugins.boostport

import app.aaps.core.data.model.HR
import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.plugins.aps.openAPSBoost.activity.SleepHistoryTracker
import app.aaps.plugins.aps.openAPSBoost.activity.SleepStateDetector
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys
import app.aaps.plugins.boostport.aps.BoostOwnerStorage
import app.aaps.plugins.boostport.aps.BoostSleepCycleOwner
import app.aaps.plugins.boostport.aps.BoostSleepTelemetry
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.boostport.reference.activity.SleepStateDetector as Source
import app.aaps.plugins.boostport.reference.activity.SleepHistoryTracker as SourceHistory
import java.lang.reflect.Proxy
import kotlin.coroutines.*
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

class BoostSleepCycleOwnerTest {
    private val day = 20_000L * 86_400_000L
    private val stateKey = BoostApsSettingKeys.ApsBoostSleepState.key
    private val historyKey = BoostApsSettingKeys.ApsBoostSleepHistory.key
    private val settings = BoostSleepCycleOwner.Settings(1320, 420, 60, 60, 10, 5, 250, 0)

    private class Fixture {
        val stored = mutableMapOf<String, String>()
        val edits = mutableListOf<Set<String>>()
        val queries = mutableListOf<Triple<String, Long, Long>>()
        var hrs = emptyList<HR>()
        var steps = emptyList<SC>()
        var failQuery: Exception? = null
        var failWrite = false
        val storage = Proxy.newProxyInstance(KeyValueStore::class.java.classLoader,
            arrayOf(KeyValueStore::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> stored[args[0]] ?: args[1]
                "edit" -> {
                    val pending = mutableMapOf<String, String>()
                    val editor = Proxy.newProxyInstance(KeyValueStore.Editor::class.java.classLoader,
                        arrayOf(KeyValueStore.Editor::class.java)) { _, m, a ->
                        check(m.name == "putString")
                        pending[a[0] as String] = a[1] as String
                        null
                    } as KeyValueStore.Editor
                    @Suppress("UNCHECKED_CAST")
                    val block = args[1] as KeyValueStore.Editor.() -> Unit
                    editor.block()
                    if (failWrite) error("disk unavailable")
                    stored.putAll(pending)
                    edits.add(pending.keys.toSet())
                    null
                }
                else -> error("Unexpected store call ${method.name}")
            }
        } as KeyValueStore
        val db = Proxy.newProxyInstance(PersistenceLayer::class.java.classLoader,
            arrayOf(PersistenceLayer::class.java)) { _, method, args ->
            failQuery?.let { throw it }
            val start = args[0] as Long
            val end = args[1] as Long
            queries.add(Triple(method.name, start, end))
            when (method.name) {
                "getHeartRatesFromTimeToTime" -> hrs.filter { it.timestamp in start..end }
                "getStepsCountFromTimeToTime" -> steps.filter { it.timestamp in start..end }
                else -> error("Unexpected DB call ${method.name}")
            }
        } as PersistenceLayer
        fun owner(store: KeyValueStore = storage) = BoostSleepCycleOwner(db, store)
    }

    private fun <T> immediate(block: suspend () -> T): T {
        var completed: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { completed = result }
        })
        return checkNotNull(completed).getOrThrow()
    }

    @Test fun endOfCycleStateIsVisibleOnlyInNextSnapshotAndSavedAtomically() {
        val f = Fixture()
        val owner = f.owner()
        val before = owner.snapshot()
        val now = day + 21 * 3_600_000L
        val update = immediate { owner.completeCycle(now, 0, settings, 0, null) }
        assertEquals(SleepStateDetector.SleepState.AWAKE, before.state.state)
        assertEquals(SleepStateDetector.SleepState.PRE_SLEEP, update.evaluation.newState.state)
        assertEquals(SleepStateDetector.SleepState.PRE_SLEEP, owner.snapshot().state.state)
        assertEquals(listOf(setOf(stateKey)), f.edits)
        assertEquals(SleepStateDetector.SleepState.PRE_SLEEP, f.owner().snapshot().state.state)
    }

    @Test fun sleepEntryOpensHistoryAndBoundaryWakeClosesItWithoutTrainingWakeTime() {
        val f = Fixture()
        val owner = f.owner()
        val now = day + 22 * 3_600_000L
        for (n in 0..2) {
            val at = now + n * 5 * 60_000L
            f.hrs = (1..5).map { HR(duration = 60_000L, timestamp = at - it * 60_000L, beatsPerMinute = 58.0, device = "watch") }
            immediate { owner.completeCycle(at, 0, settings, 0, 0.1) }
        }
        assertEquals(SleepStateDetector.SleepState.SLEEPING, owner.snapshot().state.state)
        assertEquals(setOf(stateKey, historyKey), f.edits.last())
        val open = SleepHistoryTracker.History.deserialize(f.stored.getValue(historyKey))
        assertNotNull(open.openSleepStartMs)
        val wakeAt = day + 86_400_000L + 7 * 3_600_000L
        val wake = immediate { owner.completeCycle(wakeAt, 0, settings, 0, null) }
        assertEquals("boundary", wake.evaluation.wakeReason)
        assertEquals(wakeAt, owner.snapshot().lastBoundaryExitMs)
        val closed = SleepHistoryTracker.History.deserialize(f.stored.getValue(historyKey))
        assertNull(closed.openSleepStartMs)
        assertEquals(1, closed.sessions.size)
        val result = RT(runningDynamicIsf = true)
        BoostSleepTelemetry.write(result, wake, wakeAt)
        assertEquals("AWAKE", result.sleepState)
        assertEquals(0, result.sleepLearnedSessionCount, "source uploads the aggregate from before this wake")
        val next = immediate { owner.completeCycle(wakeAt + 5 * 60_000L, 0, settings, 0, null) }
        assertEquals(1, next.learned.sessionCount)
        assertEquals("boundary", closed.sessions.single().wakeReason)
        assertNull(SleepHistoryTracker.aggregate(closed, 0L).wakeMinAvg)
    }

    @Test fun telemetryUsesCapturedHeartRateRowsWithoutAnotherQueryOrMutableDbAliases() {
        val f = Fixture()
        val at = day + 12 * 3_600_000L
        val row = HR(duration = 60_000L, timestamp = at - 60_000L, beatsPerMinute = 65.0, device = "Garmin")
        f.hrs = listOf(row)
        val update = immediate { f.owner().completeCycle(at, 0, settings, 0, null) }
        val queries = f.queries.toList()
        row.beatsPerMinute = 99.0
        row.isValid = false
        val result = RT(runningDynamicIsf = true)
        BoostSleepTelemetry.write(result, update, at)
        assertEquals(65.0, result.hrBpmLatest)
        assertEquals(65.0, result.hrBpmAvg5m)
        assertEquals("garmin", result.hrSource_resolved)
        assertEquals(queries, f.queries)
    }

    @Test fun uninterruptedTwoDayOwnerMatchesOriginalDetectorAndHistory() {
        val f = Fixture()
        var owner = f.owner()
        var source = Source.State()
        var past = SourceHistory.History()
        repeat(576) { n ->
            val at = day + n * 5 * 60_000L
            val minute = n * 5 % 1440
            val night = minute >= 1320 || minute < 360
            f.hrs = (1..5).map { HR(duration = 60_000L, timestamp = at - it * 60_000L,
                beatsPerMinute = if (night) 58.0 else 82.0, device = "watch") }
            val steps = if (night) 0 else 200
            val previous = source.state
            val expected = Source.evaluate(source, Source.Inputs(at, minute, f.hrs,
                hrResting = 60, stepsLast15Min = steps, mlMealLikely = 0.1,
                nightStartMin = 1320, nightEndMin = 420, stepsToday = 0,
                sleepInStepsThreshold = 250, sleepInWindowMin = 0))
            source = expected.newState
            if (expected.transitioned) past = when {
                previous != Source.SleepState.SLEEPING && source.state == Source.SleepState.SLEEPING ->
                    SourceHistory.onSleepStart(past, at)
                previous == Source.SleepState.SLEEPING && source.state != Source.SleepState.SLEEPING ->
                    SourceHistory.onWake(past, at, wakeReason = expected.wakeReason)
                else -> past
            }
            val actual = immediate { owner.completeCycle(at, 0, settings, steps, 0.1) }
            assertEquals(expected.toString(), actual.evaluation.toString(), "cycle $n")
            assertEquals(past.sessions.map { it.wakeReason },
                SleepHistoryTracker.History.deserialize(f.stored[historyKey] ?: "").sessions.map { it.wakeReason })
            if (expected.transitioned) {
                // Both original and port persist only on a transition.
                owner = f.owner()
                source = Source.State.deserialize(source.serialize())
            }
        }
    }

    @Test fun learnedWindowIsCircularBoundedAndWakeCanNeverMoveEarlier() {
        assertEquals(30, BoostSleepCycleOwner.clampToConfiguredBand(90, 1380))
        assertEquals(1230, BoostSleepCycleOwner.clampToConfiguredBand(1000, 1320))
        assertEquals(420, BoostSleepCycleOwner.clampToConfiguredBand(360, 420, allowEarlier = false))
        assertEquals(510, BoostSleepCycleOwner.clampToConfiguredBand(900, 420, allowEarlier = false))
        assertEquals(420, BoostSleepCycleOwner.clampToConfiguredBand(null, 420, allowEarlier = false))
    }

    @Test fun capturedTimezoneAnchorsTheSameLocalDayForAllReads() {
        val f = Fixture()
        val now = day + 22 * 3_600_000L
        immediate { f.owner().completeCycle(now, 420, settings, 0, null, phoneStepsToday = 500) }
        assertEquals(Triple("getHeartRatesFromTimeToTime", now - 30 * 60_000L, now), f.queries.first())
        assertEquals(Triple("getStepsCountFromTimeToTime", day + 17 * 3_600_000L, now), f.queries[1])
    }

    @Test fun learnedRestingHrAndBoundedScheduleComeFromCompletedHistory() {
        val f = Fixture()
        val sessions = (1..7).map { n -> SleepHistoryTracker.Session(
            sleepStartMs = day - n * 86_400_000L + 20 * 3_600_000L,
            wakeMs = day - (n - 1) * 86_400_000L + 6 * 3_600_000L,
            sleepHrP10 = 50, daytimeHrP10 = 78, wakeReason = "hr_steps"
        ) }.sortedBy { it.sleepStartMs }.toMutableList()
        f.stored[historyKey] = SleepHistoryTracker.History(sessions).serialize()
        val now = day + 22 * 3_600_000L
        f.hrs = (1..5).map { HR(duration = 60_000L, timestamp = now - it * 60_000L,
            beatsPerMinute = 64.0, device = "watch") }
        val owner = f.owner()
        val result = immediate { owner.completeCycle(now, 0, settings, 0, 0.1) }
        assertEquals(1230, result.effectiveNightStartMin)
        assertEquals(420, result.effectiveNightEndMin)
        assertEquals(50, result.learned.restingHrBpm)
        assertEquals(78, owner.snapshot().learnedDaytimeHrBpm)
        assertNull(result.evaluation.newState.sleepCandidateSinceMs)
    }

    @Test fun wakePeriodQueriesRetainSourceHrFilteringAndP10History() {
        val f = Fixture()
        val sleepStart = day + 22 * 3_600_000L
        val priorWake = day + 7 * 3_600_000L
        val now = day + 86_400_000L + 7 * 3_600_000L
        val history = SleepHistoryTracker.History(mutableListOf(
            SleepHistoryTracker.Session(day - 2 * 3_600_000L, priorWake, wakeReason = "hr_steps")
        ), sleepStart)
        f.stored[stateKey] = SleepStateDetector.State(
            state = SleepStateDetector.SleepState.SLEEPING, enteredAtMs = sleepStart
        ).serialize()
        f.stored[historyKey] = history.serialize()
        f.hrs = (1..25).map { n -> HR(duration = 60_000L, timestamp = sleepStart + n * 60_000L,
            beatsPerMinute = 50.0 + n, device = "watch") } +
            (1..25).map { n -> HR(duration = 60_000L, timestamp = priorWake + n * 60_000L,
                beatsPerMinute = 75.0 + n, device = "watch") } +
            listOf(HR(duration = 60_000L, timestamp = sleepStart + 60_000L,
                beatsPerMinute = 1.0, device = "invalid", isValid = false))
        immediate { f.owner().completeCycle(now, 0, settings, 0, null) }
        val actual = SleepHistoryTracker.History.deserialize(f.stored.getValue(historyKey))
        val expected = SourceHistory.onWake(SourceHistory.History.deserialize(history.serialize()), now,
            sleepHrBpms = (1..25).map { 50.0 + it }, daytimeHrBpms = (1..25).map { 75.0 + it }, wakeReason = "boundary")
        assertEquals(Json.parseToJsonElement(expected.serialize()), Json.parseToJsonElement(actual.serialize()))
        assertTrue(Triple("getHeartRatesFromTimeToTime", sleepStart, now) in f.queries)
        assertTrue(Triple("getHeartRatesFromTimeToTime", priorWake, sleepStart) in f.queries)
        assertEquals(setOf(stateKey, historyKey), f.edits.single())
    }

    @Test fun queryFailureAndCancellationAreNotEmptyHistoryAndDoNotWriteState() {
        for (error in listOf(IllegalStateException("read failed"), CancellationException("cancelled"))) {
            val f = Fixture().apply { failQuery = error }
            val thrown = assertFailsWith<Exception> { immediate { f.owner().completeCycle(day, 0, settings, 0, null) } }
            assertSame(error, thrown)
            assertTrue(f.edits.isEmpty())
        }
    }

    @Test fun failedWriteDoesNotAdvanceCachedStateOrOverwriteHistory() {
        val f = Fixture().apply { failWrite = true }
        val owner = f.owner()
        assertFailsWith<IllegalStateException> {
            immediate { owner.completeCycle(day + 21 * 3_600_000L, 0, settings, 0, null) }
        }
        assertEquals(SleepStateDetector.SleepState.AWAKE, owner.snapshot().state.state)
        assertTrue(f.stored.isEmpty())
    }

    @Test fun importedOrUnsupportedStateCannotSilentlyBecomeAwake() {
        for (raw in listOf(" ", "{broken", "[]", """{"state":"UNKNOWN"}""",
            """{"state":"SLEEPING","unknownFutureField":1}""")) {
            val f = Fixture().apply { stored[stateKey] = raw }
            assertFailsWith<Exception> { f.owner().snapshot() }
            assertEquals(raw, f.stored[stateKey])
        }
        val f = Fixture().apply { stored[historyKey] = """{"sessions":"invalid"}""" }
        assertFailsWith<Exception> { f.owner().snapshot() }
    }

    @Test fun originalEmptyDefaultsAndExplicitNullEntryReasonRemainCompatible() {
        val f = Fixture()
        assertEquals(SleepStateDetector.SleepState.AWAKE, f.owner().snapshot().state.state)
        f.stored[stateKey] = SleepStateDetector.State().serialize()
        assertEquals(SleepStateDetector.SleepState.AWAKE, f.owner().snapshot().state.state)
    }

    @Test fun closedStorageOwnerCannotPersistAnOldSleepTransitionAfterImport() {
        val f = Fixture()
        val guarded = BoostOwnerStorage(f.storage)
        val owner = f.owner(guarded)
        owner.snapshot()
        guarded.close()
        f.stored[stateKey] = "imported"
        assertFailsWith<IllegalStateException> {
            immediate { owner.completeCycle(day + 21 * 3_600_000L, 0, settings, 0, null) }
        }
        assertEquals("imported", f.stored[stateKey])
        assertTrue(f.edits.isEmpty())
    }
}
