package app.aaps.plugins.boostport

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.plugins.boostport.aps.BoostFloorHistoryGate
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class BoostLiveTbrGateTest {
    private val now = 20_000L * 86_400_000L
    private val hour = 3_600_000L
    private fun bg(value: Double, at: Long = now - 1) = GV(timestamp = at, value = value,
        raw = value, trendArrow = TrendArrow.NONE, noise = null, sourceSensor = SourceSensor.UNKNOWN)
    private inner class Db {
        var rows = List(1000) { bg(100.0, now - it - 1) }
        var reads = 0
        var failure: Exception? = null
        val windows = mutableListOf<Pair<Long, Long>>()
        val api = Proxy.newProxyInstance(PersistenceLayer::class.java.classLoader,
            arrayOf(PersistenceLayer::class.java)) { _, method, args ->
            check(method.name == "getBgReadingsDataFromTimeToTime")
            reads++; windows += (args[0] as Long) to (args[1] as Long)
            failure?.let { throw it }
            rows
        } as PersistenceLayer
    }

    @Test fun disabledSwitchesDoNotQueryHistory() = runBlocking {
        val db = Db()
        assertEquals(BoostFloorHistoryGate.Decision(false, false), BoostFloorHistoryGate(db.api).forCycle(now, false, false))
        assertEquals(0, db.reads)
    }
    @Test fun earlyConfirmationAloneUsesTheCapturedFourteenDayWindow() = runBlocking {
        val db = Db()
        val result = BoostFloorHistoryGate(db.api).forCycle(now, false, true)
        assertFalse(result.floorsAllowed); assertTrue(result.aggressiveEarlyConfirmAllowed)
        assertEquals(listOf((now - 14 * 86_400_000L) to now), db.windows)
    }
    @Test fun allThreeFeaturesShareOneReadEvenForThinHistory() = runBlocking {
        val db = Db(); db.rows = db.rows.take(999)
        val gate = BoostFloorHistoryGate(db.api)
        assertEquals(BoostFloorHistoryGate.Decision(false, false), gate.forCycle(now, true, true))
        assertEquals(1, db.reads)
        db.rows = List(1000) { bg(100.0) }
        assertEquals(BoostFloorHistoryGate.Decision(true, true), gate.forCycle(now + 1, true, true))
        assertEquals(2, db.reads)
    }
    @Test fun exactTimeBelowRangeBoundariesBlockBothOutputs() = runBlocking {
        for ((count, value) in listOf(20 to 62.0, 35 to 69.0)) {
            val db = Db(); db.rows = List(1000) { bg(if (it < count) value else 100.0) }
            assertEquals(BoostFloorHistoryGate.Decision(false, false), BoostFloorHistoryGate(db.api).forCycle(now, true, true))
        }
        val db = Db(); db.rows = List(1000) { bg(if (it < 19) 62.0 else if (it < 34) 69.0 else 100.0) }
        assertEquals(BoostFloorHistoryGate.Decision(true, true), BoostFloorHistoryGate(db.api).forCycle(now, true, true))
    }
    @Test fun previouslyAllowedEarlyConfirmationIsBlockedWhenHourlyHistoryDeteriorates() = runBlocking {
        val db = Db(); val gate = BoostFloorHistoryGate(db.api)
        assertTrue(gate.forCycle(now, false, true).aggressiveEarlyConfirmAllowed)
        db.rows = List(1000) { bg(50.0) }
        assertTrue(gate.forCycle(now + hour - 1, false, true).aggressiveEarlyConfirmAllowed)
        assertFalse(gate.forCycle(now + hour, false, true).aggressiveEarlyConfirmAllowed)
        assertEquals(2, db.reads)
    }
    @Test fun clockReversalRefreshesTheCache() = runBlocking {
        val db = Db(); val gate = BoostFloorHistoryGate(db.api)
        assertTrue(gate.allowed(now))
        db.rows = List(1000) { bg(50.0, now - hour) }
        assertFalse(gate.allowed(now - 1))
        assertEquals(2, db.reads)
    }
    @Test fun invalidNonfiniteAndOutOfWindowRowsCannotDiluteTheLowPercentages() = runBlocking {
        for (bad in listOf(bg(Double.NaN), bg(Double.POSITIVE_INFINITY), bg(0.0),
            bg(100.0, now + 1), bg(100.0, now - 14 * 86_400_000L - 1), bg(100.0).copy(isValid = false))) {
            val db = Db(); db.rows = db.rows.dropLast(1) + bad
            assertEquals(BoostFloorHistoryGate.Decision(false, false), BoostFloorHistoryGate(db.api).forCycle(now, true, true))
        }
    }
    @Test fun readFailureInvalidatesAnExpiredAllowAndDoesNotAbortTheCycle() = runBlocking {
        val db = Db(); val gate = BoostFloorHistoryGate(db.api)
        assertTrue(gate.allowed(now))
        db.failure = IllegalStateException("database unavailable")
        assertEquals(BoostFloorHistoryGate.Decision(false, false), gate.forCycle(now + hour, true, true))
        db.failure = null
        assertTrue(gate.allowed(now + hour + 1))
    }
    @Test fun cancellationPropagatesInsteadOfProducingAPolicyDecision() = runBlocking {
        val db = Db(); db.failure = CancellationException("owner closed")
        assertFailsWith<CancellationException> { BoostFloorHistoryGate(db.api).forCycle(now, false, true) }
    }
    @Test fun invalidClockIsRejectedBeforeTheRead() = runBlocking {
        val db = Db()
        assertFailsWith<IllegalArgumentException> { BoostFloorHistoryGate(db.api).allowed(0L) }
        assertEquals(0, db.reads)
    }
}
