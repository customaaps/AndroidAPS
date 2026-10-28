package app.aaps.plugins.boostport

import androidx.collection.LongSparseArray
import app.aaps.core.data.model.*
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.plugins.boostport.aps.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlin.test.*

class BoostInsulinNeedOwnerTest {
    private val day = 86_400_000L
    private val now = 20_000L * day
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

    private inner class Fixture {
        val disk = Disk()
        var queries = 0
        var failRead = false
        var beforeRead: (() -> Unit)? = null
        val tdd = proxy<TddCalculator> { name, args ->
            check(name == "calculate"); assertEquals(33L, args[1]); queries++
            if (failRead) error("history failed")
            beforeRead?.invoke()
            LongSparseArray<TDD>().also { rows -> repeat(33) { i ->
                val at = now - (33 - i) * day
                val amount = if (i < 30) 30.0 + i % 5 - 2 else 41.0
                rows.put(at, TDD(timestamp = at, basalAmount = amount / 2, bolusAmount = amount / 2))
            } }
        }
        val db = proxy<PersistenceLayer> { name, _ -> when (name) {
            "getEffectiveProfileSwitchActiveAt" -> null
            "getBgReadingsDataFromTimeToTime" -> emptyList<GV>()
            else -> error(name)
        } }
        fun owner() = BoostInsulinNeedOwner(disk.api, db, tdd) { it / day * day }
    }

    @Test fun onceDailySummaryUsesCompleteDaysAndCooldownWaitsForPublication() = runBlocking {
        val f = Fixture(); val owner = f.owner()
        val notice = assertNotNull(owner.evaluate(now))
        assertTrue(notice.above)
        assertNull(notice.recentBg)
        assertTrue(f.disk.values[BoostInsulinNeedOwner.summary.key].toString().startsWith("ndw=ABOVE"))
        assertFalse(f.disk.values.containsKey(BoostInsulinNeedOwner.lastAbove.key))
        assertNull(owner.evaluate(now + 300_000L))
        assertEquals(1, f.queries)
        owner.acknowledge(notice)
        assertEquals(now, f.disk.values[BoostInsulinNeedOwner.lastAbove.key])
        assertNull(f.owner().evaluate(now + 300_000L))
    }

    @Test fun staleNoticeCannotBeAcknowledgedByANewOwner() = runBlocking {
        val f = Fixture()
        val notice = assertNotNull(f.owner().evaluate(now))
        f.owner().acknowledge(notice)
        assertFalse(f.disk.values.containsKey(BoostInsulinNeedOwner.lastAbove.key))
    }

    @Test fun readOrCommitFailureCannotAdvanceNotificationCooldown() = runBlocking {
        val f = Fixture(); f.failRead = true
        assertFailsWith<IllegalStateException> { f.owner().evaluate(now) }
        assertTrue(f.disk.values.isEmpty())
        f.failRead = false; f.disk.fail = true
        assertFailsWith<IllegalStateException> { f.owner().evaluate(now) }
        assertTrue(f.disk.values.isEmpty())
    }

    @Test fun settingsChangeDuringReadRejectsSummaryWrite() = runBlocking {
        val f = Fixture()
        f.beforeRead = { f.disk.values[BoostInsulinNeedOwner.lastAbove.key] = now }
        assertFailsWith<IllegalStateException> { f.owner().evaluate(now) }
        assertFalse(f.disk.values.containsKey(BoostInsulinNeedOwner.summary.key))
    }

    @Test fun cancellationLeavesSummaryAndCooldownUntouched() = runBlocking {
        val f = Fixture()
        f.beforeRead = { throw CancellationException("stopped") }
        assertFailsWith<CancellationException> { f.owner().evaluate(now) }
        assertTrue(f.disk.values.isEmpty())
    }
    private fun profile(duration: Long = 7_200_000L) = EPS(timestamp = now,
        basalBlocks = listOf(app.aaps.core.data.model.data.Block(day, 0.7)),
        isfBlocks = emptyList(), icBlocks = emptyList(), targetBlocks = emptyList(), glucoseUnit = GlucoseUnit.MGDL,
        originalProfileName = "test", originalCustomizedName = "test", originalTimeshift = 0L,
        originalPercentage = 70, originalDuration = duration, originalEnd = 0L,
        iCfg = ICfg("test", 55, 5.0, 1.0))

    @Test fun storedTemporaryBasalPercentageIsRemovedFromDailyProfileComparison() {
        assertEquals(24.0, assertNotNull(BoostInsulinNeedOwner.dailyBasal(profile())), 1e-8)
        assertEquals(16.8, assertNotNull(BoostInsulinNeedOwner.dailyBasal(profile(0L))), 1e-8)
    }

    @Test fun malformedOrInvalidProfileDoesNotBecomeAFalseBasalChange() {
        assertNull(BoostInsulinNeedOwner.dailyBasal(profile().copy(isValid = false)))
        assertNull(BoostInsulinNeedOwner.dailyBasal(profile().copy(basalBlocks = emptyList())))
    }

}

