package app.aaps.plugins.boostport

import app.aaps.core.interfaces.aps.*
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.boostport.aps.*
import app.aaps.plugins.aps.openAPSBoost.replay.*
import java.lang.reflect.Proxy
import kotlin.coroutines.*
import kotlin.test.*
import kotlinx.coroutines.CancellationException

class BoostApsBoundaryTest {
    private val fixture = V1ReplayFixture.cycle(180.0, 3.0, 2.0, 2.0, 0.5)
    private fun input(allowed: Boolean = false) = BoostApsCalculator.Input(fixture.glucose, fixture.temp,
        fixture.iob, fixture.meal, targetProfileFixture(), 10.0, 50.0, fixture.autosens,
        100, 75, fixture.time, allowed, fixture.flat, false, false, false)
    private inline fun <reified T> proxy(noinline invoke: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, a -> invoke(m.name, a ?: emptyArray()) } as T

    private class Disk {
        val values = mutableMapOf<String, Any>()
        var failWrites = false
        val store = Proxy.newProxyInstance(KeyValueStore::class.java.classLoader, arrayOf(KeyValueStore::class.java)) { _, m, a ->
            when {
                m.name.startsWith("get") -> values[a[0] as String] ?: a[1]
                m.name.startsWith("put") -> {
                    if (failWrites) error("state write failed")
                    values[a[0] as String] = a[1]; null
                }
                else -> error("Unexpected storage call ${m.name}")
            }
        } as KeyValueStore
    }
    private fun calculator(disk: Disk, cancel: Boolean = false): BoostApsCalculator {
        val db = proxy<PersistenceLayer> { name, _ ->
            if (cancel) throw CancellationException("query cancelled")
            when (name) {
                "getBgReadingsDataFromTimeToTime", "getBolusesFromTimeToTime", "getStepsCountFromTimeToTime", "getHeartRatesFromTime" -> emptyList<Any>()
                else -> error("Unexpected DB query $name")
            }
        }
        val tdd = proxy<TddCalculator> { name, _ -> when (name) {
            "calculate", "calculateDaily", "averageTDD" -> null
            else -> error("Unexpected TDD query $name")
        } }
        val prefs = proxy<Preferences> { name, args ->
            check(name == "get")
            (args[0] as DoublePreferenceKey).defaultValue
        }
        val date = proxy<DateUtil> { name, args -> when (name) {
            "beginOfDay" -> (args[0] as Long).let { it - it % 86_400_000L }
            "getTimeZoneOffsetMinutes" -> 0
            else -> error("Unexpected clock call $name")
        } }
        val util = proxy<ProfileUtil> { name, args -> when (name) {
            "getUnits" -> app.aaps.core.data.model.GlucoseUnit.MGDL
            "fromMgdlToStringInUnits" -> args[0].toString()
            else -> error("Unexpected presentation call $name")
        } }
        val assets = BoostApsModelSource { name -> javaClass.classLoader.getResourceAsStream(name)?.bufferedReader()?.use { it.readText() } }
        return BoostApsCalculatorFactoryImpl(db, tdd, DynamicCarbRatioPolicy.Disabled, disk.store, prefs, date, util, assets).create()
    }
    private fun <T> immediate(block: suspend () -> T): T {
        var value: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { value = result }
        })
        return assertNotNull(value).getOrThrow()
    }

    @Test fun realEngineProducesStandardRtWithSameCycleOverviewValuesAndLiveStateKey() {
        val disk = Disk()
        val observed = immediate { calculator(disk).calculate(input()) }
        val result = assertIs<BoostApsCalculator.Result.Calculated>(observed, observed.toString())
        assertEquals(APSResult.Algorithm.BOOST, result.result.algorithm)
        assertEquals(fixture.time, result.result.timestamp)
        assertEquals(50.0, result.result.profileIsfMgdl)
        assertEquals(10.0, result.result.profileCarbRatio)
        assertTrue((result.result.units ?: 0.0) == 0.0)
        assertNotNull(result.result.predBGs)
        assertNotNull(BoostReplayStateCodec.decode(disk.values.getValue(BoostApsSettingKeys.Session.key) as String))
        assertFalse(disk.values.containsKey("boost_port_replay_session_v1"))
        val restored = RT.deserialize(result.result.serialize())
        assertEquals(result.result, restored.copy(reason = result.result.reason))
    }

    @Test fun importedCorruptSourceStateDoesNotStartAnUnlockedSession() {
        val disk = Disk().apply { values[BoostApsSettingKeys.ApsBoostV5State.key] = "{broken" }
        assertIs<BoostApsCalculator.Result.Rejected>(immediate { calculator(disk).calculate(input()) })
        assertEquals("{broken", disk.values[BoostApsSettingKeys.ApsBoostV5State.key])
        assertFalse(disk.values.containsKey(BoostApsSettingKeys.Session.key))
    }

    @Test fun failedStateWriteAndPendingContextAreExplicitRejections() {
        val failedDisk = Disk().apply { failWrites = true }
        assertIs<BoostApsCalculator.Result.Rejected>(immediate { calculator(failedDisk).calculate(input()) })
        for (key in listOf(BoostApsSettingKeys.ApsBoostPostExerciseRecoveryEnabled,
            BoostApsSettingKeys.ApsBoostNightModeEnabled, BoostApsSettingKeys.ApsBoostHealthConnectHrEnabled)) {
            val pending = Disk().apply { values[key.key] = true }
            assertIs<BoostApsCalculator.Result.Rejected>(immediate { calculator(pending).calculate(input()) })
            assertFalse(pending.values.containsKey(BoostApsSettingKeys.Session.key))
        }
    }

    @Test fun cancellationPropagatesAndDoesNotCreateState() {
        val disk = Disk()
        assertFailsWith<CancellationException> { immediate { calculator(disk, cancel = true).calculate(input()) } }
        assertFalse(disk.values.containsKey(BoostApsSettingKeys.Session.key))
    }

    @Test fun closedOwnerCannotReadOrOverwriteImportedState() {
        val disk = Disk()
        val owner = calculator(disk)
        assertIs<BoostApsCalculator.Result.Calculated>(immediate { owner.calculate(input()) })
        owner.close()
        disk.values[BoostApsSettingKeys.Session.key] = "imported state"
        val before = disk.values.toMap()
        assertIs<BoostApsCalculator.Result.Rejected>(immediate { owner.calculate(input()) })
        assertEquals(before, disk.values)
    }

    @Test fun rtBoundaryRejectsNonfinitePartialAndForbiddenSmbInsteadOfSilentlyClamping() {
        val valid = V1ReplayResult(runningDynamicIsf = true, timestamp = fixture.time, units = 0.0,
            reason = StringBuilder("source"), dynamicISF = 40.0)
        val mapped = BoostApsResultMapper.toRt(valid, input())
        valid.reason.append(" changed")
        assertEquals("source", mapped.reason.toString())
        assertFailsWith<IllegalArgumentException> { BoostApsResultMapper.toRt(valid.copy(units = 0.5), input()) }
        assertFailsWith<IllegalArgumentException> { BoostApsResultMapper.toRt(valid.copy(rate = 1.0), input()) }
        assertFailsWith<IllegalArgumentException> { BoostApsResultMapper.toRt(valid.copy(variable_sens = Double.NaN), input()) }
        assertFailsWith<IllegalArgumentException> { BoostApsResultMapper.toRt(valid.copy(timestamp = 1), input()) }
    }
}
