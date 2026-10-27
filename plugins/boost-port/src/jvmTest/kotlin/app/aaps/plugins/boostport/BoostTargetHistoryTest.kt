package app.aaps.plugins.boostport

import app.aaps.core.data.aps.AverageTDD
import app.aaps.core.data.model.*
import app.aaps.core.interfaces.aps.DynamicCarbRatioPolicy
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.plugins.aps.openAPSBoost.replay.*
import java.lang.reflect.Proxy
import kotlin.coroutines.*
import kotlin.test.*

class BoostTargetHistoryTest {
    private val now = V1ReplayFixture.now
    private val min = 60_000L
    private val calls = mutableListOf<List<Any?>>()
    private val icfg = ICfg("test", 55, 5.0, 1.0)
    private fun bg(minutesAgo: Int, value: Double) = GV(timestamp = now - minutesAgo * min, raw = value,
        value = value, trendArrow = TrendArrow.NONE, noise = 0.0, sourceSensor = SourceSensor.RANDOM)
    private fun bolus(minutesAgo: Int, amount: Double, type: BS.Type = BS.Type.SMB, valid: Boolean = true) =
        BS(timestamp = now - minutesAgo * min, amount = amount, type = type, isValid = valid, iCfg = icfg)

    private fun database(bg: List<GV>, boluses: List<BS>, fail: Boolean = false): PersistenceLayer =
        Proxy.newProxyInstance(PersistenceLayer::class.java.classLoader, arrayOf(PersistenceLayer::class.java)) { _, m, args ->
            if (fail) error("database unavailable")
            calls.add(listOf(m.name, args[0], args[1], args[2]))
            when (m.name) {
                "getBgReadingsDataFromTimeToTime" -> bg
                "getBolusesFromTimeToTime" -> boluses
                else -> error("Unexpected database call ${m.name}")
            }
        } as PersistenceLayer

    private fun <T> immediate(block: suspend () -> T): T {
        var value: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { value = result }
        })
        return assertNotNull(value).getOrThrow()
    }

    @Test fun originalHistoryWindowsPreserveNadirBrakingAndRawDeliveredSmb() {
        val db = database(listOf(bg(60, 40.0), bg(45, 120.0), bg(40, 70.0), bg(35, 60.0)),
            listOf(bolus(1, 0.1), bolus(5, 4.0, BS.Type.NORMAL), bolus(10, 3.0, valid = false), bolus(60, 0.2), bolus(61, 1.0)))
        val history = immediate { BoostTargetHistoryReader.read(db, now) }
        assertEquals(40.0, history.recentLowBg60)
        assertEquals(60.0, history.recentLowBg45)
        assertEquals(400.0, history.brakingProduct)
        assertEquals(0.3, history.recentSmbVolume60, 1e-12)
        assertEquals(1.0, history.minutesSinceLastSmb)
        assertEquals<List<List<Any?>>>(listOf(
            listOf("getBgReadingsDataFromTimeToTime", now - 60 * min, now, true),
            listOf("getBolusesFromTimeToTime", now - 720 * min, now, false)), calls)
    }

    @Test fun emptyHistoryUsesSourceFirstUseValuesButReadFailureIsNotZeroDelivery() {
        assertEquals(BoostTargetHistory(999.0, 999.0, 0.0, 0.0, 720.0),
            immediate { BoostTargetHistoryReader.read(database(emptyList(), emptyList()), now) })
        assertFailsWith<IllegalStateException> {
            immediate { BoostTargetHistoryReader.read(database(emptyList(), emptyList(), fail = true), now) }
        }
    }

    @Test fun cycleAssemblerFeedsTheRealHistoryThroughProfileAndEngineInputs() {
        val recorded = V1ReplayFixture.cycle(180.0, 2.0, 2.0, 2.0, 0.5)
        val db = database(listOf(bg(10, 70.0)), listOf(bolus(5, 0.4)))
        val tdd = Proxy.newProxyInstance(TddCalculator::class.java.classLoader, arrayOf(TddCalculator::class.java)) { _, method, _ ->
            when (method.name) {
                "calculate", "averageTDD", "calculateDaily" -> null
                else -> error("Unexpected TDD call ${method.name}")
            }
        } as TddCalculator
        var crCalls = 0
        val cr = object : DynamicCarbRatioPolicy {
            override suspend fun calculate(input: DynamicCarbRatioPolicy.Input, knownTdd: DynamicCarbRatioPolicy.TddParts?): DynamicCarbRatioPolicy.Result {
                crCalls++
                assertEquals(now, input.atMs)
                assertEquals(10.0, input.baseCr)
                assertNotNull(knownTdd)
                return DynamicCarbRatioPolicy.Result(8.0, "shared", applied = true)
            }
        }
        val context = BoostProfileContext(now, 999.0, 100, 100.0, 100.0, 110.0, 105.0,
            75, true, 3.0, 10.0, 85.0, 1.0, 250.0, true, false, false, "RESTING")
        val cycle = immediate { BoostTargetCycleAssembler(db, tdd, cr).assemble(BoostTargetCycleAssembler.Inputs(
            recorded.glucose, recorded.temp, recorded.iob, recorded.meal, targetProfileFixture(),
            10.0, recorded.autosens, BoostIsfSettings(1.2, 0.7, false, 0.6, 210.0, 100.0,
                true, true, 160, false, 100, true), context, microBolusAllowed = false,
            flatBg = true, cumulativeSmbCap = 1.0)) }
        assertEquals(1, crCalls)
        assertEquals(now, cycle.time)
        assertEquals(70.0, cycle.profile.recentLowBG)
        assertEquals(70.0, cycle.recentLowBG45Min)
        assertEquals(0.4, cycle.recentSmbVolume60Min)
        assertEquals(5.0, cycle.timeSinceLastSmbMin)
        assertEquals(8.0, cycle.profile.dynamicCarbRatio)
        assertEquals(10.0, cycle.profile.carb_ratio)
        assertFalse(cycle.microBolusAllowed)
        assertTrue(cycle.flatBGsDetected)
        assertEquals(1.0, cycle.cumulativeSmbCap60Min)
    }
}
