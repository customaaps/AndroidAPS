package app.aaps.plugins.boostport

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.plugins.aps.openAPSBoost.activity.MealTimeLearner
import app.aaps.plugins.aps.openAPSBoost.activity.SleepStateDetector.SleepState
import app.aaps.plugins.aps.openAPSBoost.replay.BoostProfileContext
import app.aaps.plugins.boostport.aps.*
import java.lang.reflect.Proxy
import kotlin.test.*
import kotlinx.coroutines.*

class BoostPolicyOwnersTest {
    private val day = 20_000L * 86_400_000L
    private fun context(at: Long = day + 12 * 3_600_000L, activity: String = "NONE", bg: Double = 130.0) =
        BoostProfileContext(at, bg, 100, 100.0, 90.0, 110.0, 100.0, 60, true, 2.5, 1.0,
            50.0, 1.0, 200.0, false, false, false, activity)
    private val recovery = BoostRecoveryOwner.Settings(true, 2.0, 144.0, 0.5, 10)
    private class Db {
        var target: TT? = null
        val inserted = mutableListOf<TT>()
        var cancelled = 0
        var failInsert = false
        var bg = emptyList<GV>()
        var historyReads = 0
        val api = Proxy.newProxyInstance(PersistenceLayer::class.java.classLoader,
            arrayOf(PersistenceLayer::class.java)) { _, method, args -> when (method.name) {
                "getTemporaryTargetActiveAt" -> target
                "insertAndCancelCurrentTemporaryTarget" -> {
                    if (failInsert) error("TT write failed")
                    target = (args[0] as TT).copy(); inserted.add(target!!)
                    PersistenceLayer.TransactionResult<TT>()
                }
                "cancelCurrentTemporaryTargetIfAny" -> { cancelled++; target = null; PersistenceLayer.TransactionResult<TT>() }
                "getBgReadingsDataFromTimeToTime" -> { historyReads++; bg }
                else -> error("Unexpected DB call ${method.name}")
            } } as PersistenceLayer
    }
    private class Disk {
        val values = mutableMapOf<String, String>()
        var writes = 0
        var fail = false
        val api = Proxy.newProxyInstance(KeyValueStore::class.java.classLoader,
            arrayOf(KeyValueStore::class.java)) { _, method, args -> when (method.name) {
                "getString" -> values[args[0]] ?: args[1]
                "putString" -> {
                    if (fail) error("write failed")
                    writes++; values[args[0] as String] = args[1] as String; null
                }
                else -> error("Unexpected store call ${method.name}")
            } } as KeyValueStore
    }

    @Test fun recoveryUsesSourceExerciseSpecificParametersAndStrictWindowEnd() = runBlocking {
        for ((activity, minutes, target, scale) in listOf(
            listOf("ACTIVE", 120, 144.0, 0.5), listOf("MODERATE_AEROBIC", 120, 144.0, 0.5),
            listOf("VIGOROUS_AEROBIC", 150, 144.0, 0.4), listOf("RESISTANCE", 180, 154.0, 0.6),
            listOf("LIGHT_AEROBIC", 60, 144.0, 0.7))) {
            val db = Db(); val owner = BoostRecoveryOwner(db.api); val start = context().atMs
            owner.prepare(recovery, context(start, activity as String), 999.0)
            val ended = owner.prepare(recovery, context(start + 10 * 60_000L), 999.0)
            assertEquals((minutes as Int) * 60_000L, db.inserted.single().duration)
            assertEquals(target, db.inserted.single().lowTarget)
            assertEquals(scale, ended.context.recoveryScale)
            assertEquals(ended.context.atMs + minutes * 60_000L, ended.context.recoveryEndMs)
            assertEquals(TT.Reason.ACTIVITY, db.inserted.single().reason)
        }
    }

    @Test fun stressAndShortExerciseDoNotCreateRecoveryAndUserTargetIsNotReplaced() = runBlocking {
        for (activity in listOf("STRESS", "ACTIVE")) {
            val db = Db(); val owner = BoostRecoveryOwner(db.api)
            owner.prepare(recovery, context(activity = activity), 999.0)
            owner.prepare(recovery, context(context().atMs + 9 * 60_000L), 999.0)
            assertTrue(db.inserted.isEmpty())
        }
        val db = Db().apply { target = TT(timestamp = day, reason = TT.Reason.EATING_SOON,
            lowTarget = 90.0, highTarget = 90.0, duration = 86_400_000L) }
        val owner = BoostRecoveryOwner(db.api)
        owner.prepare(recovery, context(activity = "ACTIVE"), 999.0)
        val result = owner.prepare(recovery, context(context().atMs + 20 * 60_000L), 999.0)
        assertTrue(db.inserted.isEmpty())
        assertTrue(result.context.recoveryEndMs > result.context.atMs)
        assertNull(owner.state.ownedTtStartMs)
        assertEquals(TT.Reason.EATING_SOON, db.target!!.reason)
    }

    @Test fun reboundCancelsOnlyAnOwnedActivityTargetAndPreservesSourceSameCycleTargets() = runBlocking {
        for (ownership in listOf("own", "user", "different-reason")) {
            val db = Db(); val owner = BoostRecoveryOwner(db.api); val start = context().atMs
            owner.prepare(recovery, context(start, "ACTIVE"), 999.0)
            owner.prepare(recovery, context(start + 10 * 60_000L), 999.0)
            if (ownership == "user") db.target = db.target!!.copy(timestamp = start)
            if (ownership == "different-reason") db.target = db.target!!.copy(reason = TT.Reason.EATING_SOON)
            val result = owner.prepare(recovery, context(start + 15 * 60_000L, bg = 120.0), 90.0)
            assertEquals(ownership == "own", result.cancelledOwnedTarget)
            assertEquals(if (ownership == "own") 1 else 0, db.cancelled)
            assertEquals(100.0, result.context.targetBg)
            if (ownership == "own") {
                assertEquals(0L, result.context.recoveryEndMs)
                assertEquals(false, result.context.temporaryTargetOverride)
            }
        }
    }

    @Test fun reboundThresholdsAreStrictAndFailedInsertionDoesNotAdvanceOwner() = runBlocking {
        val db = Db(); val owner = BoostRecoveryOwner(db.api); val start = context().atMs
        owner.prepare(recovery, context(start, "ACTIVE"), 999.0)
        val before = owner.state
        db.failInsert = true
        assertFailsWith<IllegalStateException> { owner.prepare(recovery, context(start + 10 * 60_000L), 999.0) }
        assertEquals(before, owner.state)
        db.failInsert = false
        owner.prepare(recovery, context(start + 10 * 60_000L), 999.0)
        assertFalse(owner.prepare(recovery, context(start + 15 * 60_000L, bg = 120.0), 100.0).cancelledOwnedTarget)
        assertFalse(owner.prepare(recovery, context(start + 20 * 60_000L, bg = 110.0), 90.0).cancelledOwnedTarget)
        assertTrue(owner.prepare(recovery.copy(enabled = false), context(start + 25 * 60_000L, bg = 111.0), 90.0).cancelledOwnedTarget)
    }

    @Test fun importedOutOfRangeRecoverySettingsRejectBeforeAnyTargetWrite() = runBlocking {
        for (settings in listOf(recovery.copy(targetMgdl = 50.0), recovery.copy(hours = 20.0),
            recovery.copy(scale = 2.0), recovery.copy(minDurationMinutes = 0))) {
            val db = Db(); val owner = BoostRecoveryOwner(db.api)
            assertFailsWith<IllegalArgumentException> { owner.prepare(settings, context(activity = "ACTIVE"), 999.0) }
            assertTrue(db.inserted.isEmpty())
            assertEquals(BoostRecoveryOwner.State(), owner.state)
        }
    }

    private fun learnedDisk(hour: Int = 13): Disk = Disk().apply {
        values[BoostApsSettingKeys.ApsBoostMealTimeHistory.key] = MealTimeLearner.History(
            (1..6).map { day - it * 86_400_000L + hour * 3_600_000L }.toMutableList()).serialize()
    }
    private val meal = BoostMealPolicyOwner.Settings(true, 72.0, 60, 1320, 420)

    @Test fun preMealIsLowerOnlyShadowFirstAndDoesNotRecalculateIsfTarget() {
        val c = context()
        val active = BoostMealPolicyOwner(learnedDisk().api).prepare(meal, c, 0L, SleepState.AWAKE, false, 999.0)
        assertEquals(72.0, active.context.targetBg)
        assertEquals(72.0, active.context.minBg)
        assertEquals(72.0, active.context.maxBg)
        assertEquals(c.isfTargetBg, active.context.isfTargetBg)
        assertTrue(active.reason!!.contains("ACTIVE"))
        val shadow = BoostMealPolicyOwner(learnedDisk().api).prepare(meal.copy(enabled = false), c, 0L, SleepState.AWAKE, false, 999.0)
        assertEquals(c, shadow.context)
        assertTrue(shadow.reason!!.contains("WOULD apply"))
        val lower = c.copy(minBg = 50.0, targetBg = 60.0)
        assertEquals(lower, BoostMealPolicyOwner(learnedDisk().api).prepare(meal, lower, 0L, SleepState.AWAKE, false, 999.0).context)
    }

    @Test fun preMealHonorsEverySourceBlockIncludingStressAndPriorSleep() {
        val c = context()
        for (activity in listOf("ACTIVE", "VIGOROUS_AEROBIC", "MODERATE_AEROBIC", "LIGHT_AEROBIC", "RESISTANCE", "STRESS")) {
            val ctx = c.copy(activityState = activity)
            assertEquals(ctx, BoostMealPolicyOwner(learnedDisk().api).prepare(meal, ctx, 0L, SleepState.AWAKE, false, 999.0).context)
        }
        for (sleep in listOf(SleepState.PRE_SLEEP, SleepState.SLEEPING))
            assertEquals(c, BoostMealPolicyOwner(learnedDisk().api).prepare(meal, c, 0L, sleep, false, 999.0).context)
        assertEquals(c, BoostMealPolicyOwner(learnedDisk().api).prepare(meal, c, 0L, SleepState.AWAKE, true, 999.0).context)
        assertEquals(c, BoostMealPolicyOwner(learnedDisk().api).prepare(meal, c, 0L, SleepState.AWAKE, false, 74.9).context)
        assertEquals(72.0, BoostMealPolicyOwner(learnedDisk().api).prepare(meal, c, 0L, SleepState.AWAKE, false, 75.0).context.targetBg)
        val recovering = c.copy(recoveryEnabled = true, recoveryEndMs = c.atMs + 1)
        assertEquals(recovering, BoostMealPolicyOwner(learnedDisk().api).prepare(meal, recovering, 0L, SleepState.AWAKE, false, 999.0).context)
    }

    @Test fun learningFiltersNightHistoryAndRecordsOnlyFreshAwakeDaytimeCommits() {
        val disk = learnedDisk(23); val owner = BoostMealPolicyOwner(disk.api)
        owner.prepare(meal, context(), 0L, SleepState.AWAKE, false, 999.0)
        assertTrue(MealTimeLearner.History.deserialize(disk.values.getValue(BoostApsSettingKeys.ApsBoostMealTimeHistory.key)).events.isEmpty())
        val initialWrites = disk.writes
        owner.completeCycle(context().atMs, false, false, SleepState.AWAKE)
        owner.completeCycle(context().atMs, true, true, SleepState.AWAKE)
        owner.completeCycle(context().atMs, true, false, SleepState.PRE_SLEEP)
        assertEquals(initialWrites, disk.writes)
        owner.completeCycle(context().atMs, true, false, SleepState.AWAKE)
        assertEquals(listOf(context().atMs), MealTimeLearner.History.deserialize(disk.values.getValue(BoostApsSettingKeys.ApsBoostMealTimeHistory.key)).events)
        val restored = BoostMealPolicyOwner(disk.api).prepare(meal, context(), 0L, SleepState.AWAKE, false, 999.0)
        assertNull(restored.reason)
    }

    @Test fun corruptMealHistoryRejectsAndDisabledTrialDoesNotWriteOrReadSeed() {
        val disk = Disk().apply { values[BoostApsSettingKeys.ApsBoostMealTimeHistory.key] = """{"events":"broken"}""" }
        assertFailsWith<IllegalArgumentException> { BoostMealPolicyOwner(disk.api).prepare(meal, context(), 0L, SleepState.AWAKE, false, 999.0) }
        assertNull(BoostTightRampPolicy.cap(disk.api, false, 1L) { error("must not create seed") })
        assertEquals(0, disk.writes)
    }

    @Test fun trialSeedIsPersistentAndDayAssignmentUsesOriginalKernel() {
        val disk = Disk()
        repeat(20) { n ->
            val cap = BoostTightRampPolicy.cap(disk.api, true, n.toLong()) { "fixed-install-seed" }
            val expected = if (app.aaps.plugins.aps.openAPSBoost.BoostV1ReplayEngine.tightRampArm("fixed-install-seed", n.toLong())) 0.6 else null
            assertEquals(expected, cap)
        }
        assertEquals(1, disk.writes)
        BoostTightRampPolicy.cap(disk.api, true, 1L) { error("must reuse seed after restart") }
    }

    @Test fun floorGateFailsClosedUntilEnoughHistoryAndCachesOnlyTrustedPercentages() = runBlocking {
        val db = Db(); val gate = BoostFloorHistoryGate(db.api)
        db.bg = List(999) { GV(timestamp = day - it, value = 100.0, raw = 100.0, trendArrow = TrendArrow.NONE, noise = null, sourceSensor = SourceSensor.UNKNOWN) }
        assertFalse(gate.allowed(day))
        db.bg = List(1000) { GV(timestamp = day - it, value = 100.0, raw = 100.0, trendArrow = TrendArrow.NONE, noise = null, sourceSensor = SourceSensor.UNKNOWN) }
        assertTrue(gate.allowed(day + 1))
        db.bg = List(1000) { GV(timestamp = day - it, value = 50.0, raw = 50.0, trendArrow = TrendArrow.NONE, noise = null, sourceSensor = SourceSensor.UNKNOWN) }
        assertTrue(gate.allowed(day + 2))
        assertEquals(2, db.historyReads)
        assertFalse(gate.allowed(day + 3_600_001L))
    }

    @Test fun closeCancelsSuspendedEffectsAndKeepsCallerCancellation() = runBlocking {
        val disk = Disk(); val storage = BoostOwnerStorage(disk.api)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var wrote = false
        val job = launch { storage.cycle { entered.complete(Unit); release.await(); wrote = true } }
        entered.await(); storage.close(); release.complete(Unit); job.join()
        assertTrue(job.isCancelled)
        assertFalse(wrote)
        assertFailsWith<IllegalStateException> { storage.cycle { } }
    }
}
