package app.aaps.plugins.boostport

import androidx.collection.LongSparseArray
import app.aaps.core.data.model.*
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.plugins.aps.openAPSBoostV5.BoostV5AutoConfig as Derive
import app.aaps.plugins.aps.openAPSBoostV5.BoostV5AutoConfigApply as Apply
import app.aaps.plugins.aps.openAPSBoostV5.BoostV5OfflineDoubleKey as Knob
import app.aaps.plugins.boostport.aps.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlin.test.*

class BoostAutoConfigOwnerTest {
    private val now = 20_000L * 86_400_000L
    private inline fun <reified T> proxy(noinline fn: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, a -> fn(m.name, a ?: emptyArray()) } as T
    private inner class Disk {
        val values = mutableMapOf<String, Any>()
        var commits = 0
        var fail = false
        val api = proxy<KeyValueStore> { name, args -> when (name) {
            "contains" -> values.containsKey(args[0])
            "getAll" -> values.toMap()
            "getDouble" -> (values[args[0]] as? Number)?.toDouble() ?: args[1]
            "getString", "getBoolean", "getInt", "getLong" -> values[args[0]] ?: args[1]
            "edit" -> {
                assertEquals(true, args[0])
                val staged = values.toMutableMap()
                val editor = proxy<KeyValueStore.Editor> { op, v -> when {
                    op == "remove" -> staged.remove(v[0])
                    op.startsWith("put") -> staged[v[0] as String] = v[1]!!
                    else -> error(op)
                }; Unit }
                @Suppress("UNCHECKED_CAST")
                (args[1] as KeyValueStore.Editor.() -> Unit).invoke(editor)
                if (fail) error("commit failed")
                values.clear(); values.putAll(staged); commits++; Unit
            }
            else -> error("unexpected storage $name")
        } }
    }
    private inner class History {
        var days = 7
        var readings = 2000
        var dose = 20.0
        var low70 = 0
        var severe54 = 0
        var queries = 0
        var beforeRead: (() -> Unit)? = null
        var fail = false
        val windows = mutableListOf<Long>()
        val tdd = proxy<TddCalculator> { name, args ->
            check(name == "calculate")
            assertEquals(now <= args[0] as Long, true)
            windows += args[1] as Long
            LongSparseArray<TDD>().also { rows -> repeat(days) {
                rows.put(it.toLong(), TDD(timestamp = now - (it + 1) * 86_400_000L, basalAmount = dose / 2, bolusAmount = dose / 2))
            } }
        }
        val db = proxy<PersistenceLayer> { name, _ ->
            queries++
            beforeRead?.also { beforeRead = null; it() }
            if (fail) error("history failed")
            when (name) {
                "getBolusesFromTimeToTime" -> List(10) { BS(timestamp = now - it * 60_000L, amount = 2.0, type = BS.Type.NORMAL, iCfg = ICfg("test", 55, 5.0, 1.0)) } +
                    List(10) { BS(timestamp = now - it * 60_000L, amount = 0.2, type = BS.Type.SMB, iCfg = ICfg("test", 55, 5.0, 1.0)) }
                "getBgReadingsDataFromTimeToTime" -> List(readings) { i -> GV(timestamp = now - i * 60_000L,
                    value = when { i < severe54 -> 50.0; i < low70 -> 65.0; else -> 120.0 }, raw = 0.0,
                    trendArrow = TrendArrow.NONE, noise = null, sourceSensor = SourceSensor.UNKNOWN) }
                "getApsResults" -> emptyList<app.aaps.core.interfaces.aps.APSResult>()
                else -> error("unexpected DB $name")
            }
        }
    }
    private fun owner(d: Disk, h: History) = BoostAutoConfigOwner(d.api, h.db, h.tdd) { "iso-$it" }
    private fun resolved(d: Disk, key: String) = d.values[BoostAutoConfigOwner.resolved(key)] == true

    @Test fun writtenLedgerSurvivesRestartAndPreservesManualComputedCaps(): Unit = runBlocking {
        val d = Disk(); val h = History()
        owner(d, h).evaluate(now, 3.0, 5.0)
        assertTrue(d.values[BoostAutoConfigOwner.WRITTEN].toString().contains(Knob.ApsBoostCumulativeSmbCap60Min.key))
        d.values[Knob.ApsBoostCumulativeSmbCap60Min.key] = Knob.ApsBoostCumulativeSmbCap60Min.defaultValue
        d.values[Knob.ApsBoostV5PrimerCapU.key] = 0.0
        h.dose = 40.0
        owner(d, h).evaluate(now + 7 * 86_400_000L, 3.0, 5.0)
        assertEquals(Knob.ApsBoostCumulativeSmbCap60Min.defaultValue, d.values[Knob.ApsBoostCumulativeSmbCap60Min.key])
        assertEquals(0.0, d.values[Knob.ApsBoostV5PrimerCapU.key])
        assertTrue(d.values[BoostApsSettingKeys.ApsBoostV5RedriveSummary.key].toString().contains("kept-CumulativeSmbCap60Min"))
    }

    @Test fun weeklySwitchAndRouteTightenImmediatelyAndRequireTwoWindowsToLoosen(): Unit = runBlocking {
        val d = Disk(); val h = History()
        owner(d, h).evaluate(now, 3.0, 5.0)
        val aec = BoostApsSettingKeys.ApsBoostV5AggressiveEarlyConfirm.key
        val route = BoostApsSettingKeys.ApsBoostV5PrimerTbrFallback.key
        assertEquals(true, d.values[aec]); assertEquals(false, d.values[route])
        h.low70 = 90; h.severe54 = 20
        owner(d, h).evaluate(now + 7 * 86_400_000L, 3.0, 5.0)
        assertEquals(false, d.values[aec]); assertEquals(true, d.values[route])
        h.low70 = 0; h.severe54 = 0
        owner(d, h).evaluate(now + 14 * 86_400_000L, 3.0, 5.0)
        assertEquals(false, d.values[aec]); assertEquals(true, d.values[route])
        owner(d, h).evaluate(now + 21 * 86_400_000L, 3.0, 5.0)
        assertEquals(true, d.values[aec]); assertEquals(false, d.values[route])
    }

    @Test fun malformedWrittenLedgerRejectsWithoutAnyWrite(): Unit = runBlocking {
        val d = Disk(); val h = History()
        d.values[BoostAutoConfigOwner.WRITTEN] = "broken"
        val before = d.values.toMap()
        assertFails { owner(d, h).evaluate(now, 3.0, 5.0) }
        assertEquals(before, d.values); assertEquals(0, d.commits)
    }

    @Test fun insufficientHistoryKeepsKnobsOpenAndRetriesWithoutInventedSettings() = runBlocking {
        val d = Disk(); val h = History().apply { days = 6 }
        val o = owner(d, h)
        assertNull(o.evaluate(now, 3.0, 5.0))
        assertTrue(Apply.managedDoubleKeys.none { resolved(d, it.key) })
        assertFalse(d.values.containsKey(Knob.ApsBoostV5CommittedCapU.key))
        assertTrue(d.values[BoostApsSettingKeys.ApsBoostV5RedriveSummary.key].toString().contains("ONBOARDING_INCOMPLETE"))
        h.days = 7
        assertNotNull(o.evaluate(now + 300_000, 3.0, 5.0))
        assertTrue(Apply.managedDoubleKeys.all { resolved(d, it.key) })
        assertEquals(listOf(14L, 14L), h.windows) // redrive remains stamped until its next due time
    }

    @Test fun onboardingUsesSourceFormulaKeepsTunedCapAndSizesBudgetFromOperativeCaps() = runBlocking {
        val d = Disk(); val h = History()
        d.values[Knob.ApsBoostV5CommittedCapU.key] = 0.8
        val notice = assertNotNull(owner(d, h).evaluate(now, 3.0, 5.0))
        assertEquals(0.8, d.values[Knob.ApsBoostV5CommittedCapU.key])
        assertEquals(2.0, d.values[Knob.ApsBoostV5ConfirmedCapU.key])
        assertEquals(3.6, d.values[Knob.ApsBoostCumulativeSmbCap60Min.key])
        assertEquals(3.0, d.values[Knob.ApsBoostMaxIob.key])
        assertEquals(5.0, d.values[Knob.ApsBoostBolus.key])
        assertEquals(true, d.values[BoostApsSettingKeys.ApsBoostV5AggressiveEarlyConfirm.key])
        assertEquals(false, d.values[BoostApsSettingKeys.ApsBoostV5PrimerTbrFallback.key])
        assertEquals(0, notice.held)
        assertEquals(listOf(14L, 28L), h.windows)
        assertEquals(1, d.commits)
        val snapshot = d.values.toMap(); val reads = h.queries
        assertNull(owner(d, h).evaluate(now + 300_000L, 3.0, 5.0))
        assertEquals(snapshot, d.values)
        assertEquals(reads, h.queries)
    }

    @Test fun raiseGuardsAndUserBooleanArePreservedDuringRealHistoryGathering() = runBlocking {
        val d = Disk(); val h = History().apply { dose = 80.0; low70 = 90; severe54 = 20 }
        d.values[BoostApsSettingKeys.ApsBoostV5FastCarbConfirm.key] = false // tuned away from true
        val notice = assertNotNull(owner(d, h).evaluate(now, 3.0, 5.0))
        assertTrue(notice.held > 0)
        assertFalse(d.values.containsKey(Knob.ApsBoostV5CommittedCapU.key))
        assertEquals(false, d.values[BoostApsSettingKeys.ApsBoostV5FastCarbConfirm.key])
        assertEquals(true, d.values[BoostApsSettingKeys.ApsBoostV5PrimerTbrFallback.key])
        assertTrue((d.values[Knob.ApsBoostV5PrimerCapU.key] as Double) > 0)
    }

    @Test fun weeklyRedrivePersistsBaselineAndPreservesUserOffsetAcrossRestart() = runBlocking {
        val d = Disk(); val h = History()
        d.values[Knob.ApsBoostV5CommittedCapU.key] = 0.8 // 1.6x the initial driver 0.5
        owner(d, h).evaluate(now, 3.0, 5.0)
        h.dose = 30.0
        val changed = assertNotNull(owner(d, h).evaluate(now + 7 * 86_400_000L, 3.0, 5.0))
        assertTrue(changed.redriven > 0)
        assertEquals(1.0, d.values[Knob.ApsBoostV5CommittedCapU.key]) // source +25% per-evaluation bound
        assertTrue(d.values[BoostApsSettingKeys.ApsBoostV5RedriveBaseline.key].toString().contains("0.625"))
        assertTrue(d.values[BoostApsSettingKeys.ApsBoostV5RedriveSummary.key].toString().contains("CommittedCapU:1.0"))
        assertTrue(d.values[BoostApsSettingKeys.ApsBoostV5RedriveHistory.key].toString().contains("was 0.8"))
        assertEquals(listOf(14L, 28L, 28L), h.windows)
        // The clipped driver movement remains available for the next weekly run.
        assertNotNull(owner(d, h).evaluate(now + 14 * 86_400_000L, 3.0, 5.0))
        assertEquals(1.2, d.values[Knob.ApsBoostV5CommittedCapU.key])
        val baseline = d.values[BoostApsSettingKeys.ApsBoostV5RedriveBaseline.key]
        assertNull(owner(d, h).evaluate(now + 14 * 86_400_000L + 300_000, 3.0, 5.0))
        assertEquals(baseline, d.values[BoostApsSettingKeys.ApsBoostV5RedriveBaseline.key])
    }

    @Test fun legacyFlagAndSchemaMigrationReopenOldFactoryDefaultsAndRetainTunedValues() = runBlocking {
        val d = Disk(); val h = History().apply { days = 0; readings = 0 }
        d.values[BoostApsSettingKeys.ApsBoostV5AutoConfigDone.key] = true
        d.values[Knob.ApsBoostV5CommittedCapU.key] = 0.25 // historical factory
        d.values[BoostAutoConfigOwner.resolved(Knob.ApsBoostV5CommittedCapU.key)] = true
        d.values[Knob.ApsBoostV5ConfirmedCapU.key] = 4.5 // tuned
        owner(d, h).evaluate(now, 3.0, 5.0)
        assertFalse(resolved(d, Knob.ApsBoostV5CommittedCapU.key))
        assertTrue(resolved(d, Knob.ApsBoostV5ConfirmedCapU.key))
        assertEquals(false, d.values[BoostApsSettingKeys.ApsBoostV5AutoConfigDone.key])
        assertEquals(Apply.AUTO_CONFIG_SCHEMA_VERSION, d.values[BoostAutoConfigOwner.SCHEMA])
        assertEquals(Derive.REDRIVE_SCHEMA_VERSION, d.values[BoostAutoConfigOwner.REDRIVE_SCHEMA])
        assertTrue(BoostAutoConfigOwner.ownedKeys.all { it.exportable })
        assertEquals(BoostAutoConfigOwner.ownedKeys.size, BoostAutoConfigOwner.ownedKeys.map { it.key }.distinct().size)
    }

    @Test fun databaseAndTransactionFailuresLeaveAllKnobsFlagsAndClocksUnchangedAndRetry() = runBlocking {
        val d = Disk(); val h = History(); val o = owner(d, h)
        h.fail = true
        assertFailsWith<IllegalStateException> { o.evaluate(now, 3.0, 5.0) }
        assertTrue(d.values.isEmpty())
        h.fail = false; d.fail = true
        assertFailsWith<IllegalStateException> { o.evaluate(now, 3.0, 5.0) }
        assertTrue(d.values.isEmpty())
        d.fail = false
        assertNotNull(o.evaluate(now, 3.0, 5.0))
        assertEquals(1, d.commits)
    }

    @Test fun malformedBaselineRejectsWithoutResettingDataOrStampingNextRun() = runBlocking {
        val d = Disk(); val h = History()
        owner(d, h).evaluate(now, 3.0, 5.0)
        d.values[BoostApsSettingKeys.ApsBoostV5RedriveBaseline.key] = "broken"
        val snapshot = d.values.toMap()
        assertFails { owner(d, h).evaluate(now + 7 * 86_400_000L, 3.0, 5.0) }
        assertEquals(snapshot, d.values)
    }

    @Test fun aUserEditDuringHistoryReadAbortsStagedConfigurationWithoutOverwritingIt() = runBlocking {
        val d = Disk(); val h = History()
        h.beforeRead = { d.values[Knob.ApsBoostV5CommittedCapU.key] = 1.7 }
        assertFailsWith<IllegalStateException> { owner(d, h).evaluate(now, 3.0, 5.0) }
        assertEquals(mapOf<String, Any>(Knob.ApsBoostV5CommittedCapU.key to 1.7), d.values)
        assertEquals(0, d.commits)
        assertNotNull(owner(d, h).evaluate(now, 3.0, 5.0))
        assertEquals(1.7, d.values[Knob.ApsBoostV5CommittedCapU.key])
    }

    @Test fun closingOwnerDuringHistoryReadCancelsBeforeAnyConfigCommit() = runBlocking {
        val d = Disk(); val h = History(); val entered = CompletableDeferred<Unit>()
        val tdd = object : TddCalculator by h.tdd {
            override suspend fun calculate(timestamp: Long, days: Long, allowMissingDays: Boolean): LongSparseArray<TDD>? {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val guarded = BoostOwnerStorage(d.api)
        val job = launch { guarded.cycle { BoostAutoConfigOwner(guarded, h.db, tdd) { "iso" }.evaluate(now, 3.0, 5.0) } }
        entered.await(); guarded.close(); job.join()
        assertTrue(job.isCancelled)
        assertTrue(d.values.isEmpty())
    }
}

