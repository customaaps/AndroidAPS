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
                m.name == "edit" -> {
                    val pending = values.toMutableMap()
                    val editor = Proxy.newProxyInstance(KeyValueStore.Editor::class.java.classLoader,
                        arrayOf(KeyValueStore.Editor::class.java)) { _, method, args ->
                        if (method.name == "remove") pending.remove(args[0])
                        else { check(method.name.startsWith("put")); pending[args[0] as String] = args[1] }
                        null
                    } as KeyValueStore.Editor
                    @Suppress("UNCHECKED_CAST")
                    val block = a[1] as KeyValueStore.Editor.() -> Unit
                    editor.block()
                    if (failWrites) error("state write failed")
                    values.clear()
                    values.putAll(pending)
                    null
                }
                m.name == "getAll" -> values.toMap()
                m.name == "contains" -> values.containsKey(a[0])
                m.name.startsWith("get") -> values[a[0] as String] ?: a[1]
                m.name.startsWith("put") -> {
                    if (failWrites) error("state write failed")
                    values[a[0] as String] = a[1]; null
                }
                else -> error("Unexpected storage call ${m.name}")
            }
        } as KeyValueStore
    }
    private fun calculator(disk: Disk, cancel: Boolean = false, zone: java.time.ZoneId? = null, activity: BoostApsActivitySource? = null): BoostApsCalculator {
        val db = proxy<PersistenceLayer> { name, _ ->
            if (cancel) throw CancellationException("query cancelled")
            when (name) {
                "getBgReadingsDataFromTimeToTime", "getBolusesFromTimeToTime", "getStepsCountFromTimeToTime", "getHeartRatesFromTime", "getHeartRatesFromTimeToTime" -> emptyList<Any>()
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
            "beginOfDay" -> (args[0] as Long).let { at ->
                if (zone == null) at - at % 86_400_000L else
                    java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
            }
            "toISOString" -> "iso-${args[0]}"
            "getTimeZoneOffsetMinutes" -> if (zone == null) 0 else
                zone.rules.getOffset(java.time.Instant.ofEpochMilli(args[0] as Long)).totalSeconds / 60
            else -> error("Unexpected clock call $name")
        } }
        val util = proxy<ProfileUtil> { name, args -> when (name) {
            "getUnits" -> app.aaps.core.data.model.GlucoseUnit.MGDL
            "fromMgdlToStringInUnits" -> args[0].toString()
            else -> error("Unexpected presentation call $name")
        } }
        val assets = BoostApsModelSource { name -> javaClass.classLoader.getResourceAsStream(name)?.bufferedReader()?.use { it.readText() } }
        return BoostApsCalculatorFactoryImpl(db, tdd, DynamicCarbRatioPolicy.Disabled, disk.store, prefs, date, util, assets, activity).create()
    }
    private fun <T> immediate(block: suspend () -> T): T {
        var value: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { value = result }
        })
        return assertNotNull(value).getOrThrow()
    }

    @Test fun invalidAutoConfigurationLimitsRejectBeforeAnyStateWrite() {
        for (limit in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            val disk = Disk()
            assertIs<BoostApsCalculator.Result.Rejected>(immediate {
                calculator(disk).calculate(input().copy(maxBolusAllowed = limit))
            })
            assertTrue(disk.values.isEmpty())
        }
    }

    @Test fun actualBoundaryRunsAutoConfigurationWhenConstrainedBolusLimitIsProvided() {
        val disk = Disk()
        val result = assertIs<BoostApsCalculator.Result.Calculated>(immediate {
            calculator(disk).calculate(input().copy(maxBolusAllowed = 5.0))
        })
        assertNull(result.autoConfigNotice) // no history: never manufacture a derived setting
        assertTrue(result.result.reason.toString().contains("autordv="))
        assertEquals(2, disk.values["boost_v5_autoconfig_schema_version"])
        assertFalse(disk.values.containsKey(BoostApsSettingKeys.ApsBoostV5PrimerCapU.key))
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
        assertNotNull(result.result.sleepState)
        assertEquals(0, result.result.hrReadingsCount15m)
        assertEquals("none", result.result.hrSource_states)
        val restored = RT.deserialize(result.result.serialize())
        assertEquals(result.result, restored.copy(reason = result.result.reason))
    }

    @Test fun platformPhoneSnapshotDrivesRealActivityAndIsReleasedExactlyOnce() {
        var opened = 0; var closed = 0; var reads = 0; var hc = false
        val source = object : BoostApsActivitySource {
            override fun open(): BoostApsActivitySource.Lease {
                opened++
                return object : BoostApsActivitySource.Lease {
                    override fun snapshot(nowMs: Long, offsetMinutes: Int): BoostApsActivitySource.Snapshot {
                        reads++
                        return BoostApsActivitySource.Snapshot(600, 1000, 1400, 2000, 3000, true)
                    }
                    override fun healthConnect(enabled: Boolean, nowMs: Long, pollMinutes: Int) { hc = enabled }
                    override fun close() { closed++ }
                }
            }
        }
        val request = nightRequest().let {
            val at = it.atMs - 10 * 3_600_000L
            it.copy(atMs = at, glucose = object : GlucoseStatus by it.glucose { override val date = at })
        }
        val baseline = assertIs<BoostApsCalculator.Result.Calculated>(immediate { calculator(Disk()).calculate(request) })
        val disk = Disk().apply { values[BoostApsSettingKeys.ApsBoostHealthConnectHrEnabled.key] = true }
        val owner = calculator(disk, activity = source)
        val active = assertIs<BoostApsCalculator.Result.Calculated>(immediate { owner.calculate(request) })
        assertEquals(baseline.profile.current_basal * 0.8, active.profile.current_basal)
        assertTrue(hc)
        owner.close(); owner.close()
        assertEquals(1, opened); assertEquals(1, closed)
        val previousReads = reads
        assertIs<BoostApsCalculator.Result.Rejected>(immediate { owner.calculate(request) })
        assertEquals(previousReads, reads)
    }

    @Test fun hrDarkWarningIsRaisedOncePerOwnedDaytimeEpisode() {
        val disk = Disk(); val owner = calculator(disk)
        val base = input()
        val midday = base.atMs / 86_400_000L * 86_400_000L + 12 * 3_600_000L
        val warnings = (0..14).map { n ->
            val at = midday + n * 5 * 60_000L
            val request = base.copy(atMs = at, glucose = object : GlucoseStatus by base.glucose { override val date = at })
            assertIs<BoostApsCalculator.Result.Calculated>(immediate { owner.calculate(request) }).hrFeedDarkMinutes
        }.filterNotNull()
        assertEquals(listOf(60L), warnings)
    }

    @Test fun importedCorruptSourceStateDoesNotStartAnUnlockedSession() {
        val disk = Disk().apply { values[BoostApsSettingKeys.ApsBoostV5State.key] = "{broken" }
        assertIs<BoostApsCalculator.Result.Rejected>(immediate { calculator(disk).calculate(input()) })
        assertEquals("{broken", disk.values[BoostApsSettingKeys.ApsBoostV5State.key])
        assertFalse(disk.values.containsKey(BoostApsSettingKeys.Session.key))
    }

    @Test fun calculatorCompletesSleepAfterProducingTheCycleAndRestoresOnNewOwner() {
        val disk = Disk()
        val initial = input()
        val nightLead = initial.atMs / 86_400_000L * 86_400_000L + 21 * 3_600_000L
        val glucose = object : GlucoseStatus by initial.glucose { override val date = nightLead }
        val request = initial.copy(atMs = nightLead, glucose = glucose)
        val calculated = assertIs<BoostApsCalculator.Result.Calculated>(immediate { calculator(disk).calculate(request) })
        assertEquals("PRE_SLEEP", calculated.result.sleepState)
        assertEquals(nightLead, calculated.result.sleepStateEnteredAtMs)
        assertEquals(0, calculated.result.sleepLearnedSessionCount)
        val raw = disk.values[BoostApsSettingKeys.ApsBoostSleepState.key] as String
        assertEquals(app.aaps.plugins.aps.openAPSBoost.activity.SleepStateDetector.SleepState.PRE_SLEEP,
            app.aaps.plugins.aps.openAPSBoost.activity.SleepStateDetector.State.deserialize(raw).state)
        assertIs<BoostApsCalculator.Result.Calculated>(immediate {
            calculator(disk).calculate(request.copy(atMs = nightLead + 60_000L))
        })
    }

    @Test fun invalidSleepHistoryRejectsBeforeCreatingAnEngineSession() {
        val disk = Disk().apply { values[BoostApsSettingKeys.ApsBoostSleepHistory.key] = """{"sessions":"invalid"}""" }
        assertIs<BoostApsCalculator.Result.Rejected>(immediate { calculator(disk).calculate(input()) })
        assertFalse(disk.values.containsKey(BoostApsSettingKeys.Session.key))
    }

    @Test fun failedStateWriteAndPendingContextAreExplicitRejections() {
        val failedDisk = Disk().apply { failWrites = true }
        assertIs<BoostApsCalculator.Result.Rejected>(immediate { calculator(failedDisk).calculate(input()) })
        val hcUnavailable = Disk().apply { values[BoostApsSettingKeys.ApsBoostHealthConnectHrEnabled.key] = true }
        assertIs<BoostApsCalculator.Result.Calculated>(immediate { calculator(hcUnavailable).calculate(input()) })
    }

    private fun nightRequest(allowed: Boolean = true): BoostApsCalculator.Input {
        val base = input(allowed)
        val at = base.atMs / 86_400_000L * 86_400_000L + 22 * 3_600_000L
        val glucose = object : GlucoseStatus by base.glucose { override val date = at }
        return base.copy(atMs = at, glucose = glucose, originalProfileTargetMgDl = 180.0,
            profile = base.profile.copy(temptargetSet = true))
    }

    @Test fun nightPolicyBlocksTheRealV1V5V6SmbWithoutChangingPredictionOrProfileTargets() {
        val request = nightRequest()
        val off = assertIs<BoostApsCalculator.Result.Calculated>(immediate { calculator(Disk()).calculate(request) })
        assertTrue((off.result.units ?: 0.0) > 0.0, "fixture must exercise an actual SMB")
        val disk = Disk().apply { values[BoostApsSettingKeys.ApsBoostNightModeEnabled.key] = true }
        val on = assertIs<BoostApsCalculator.Result.Calculated>(immediate { calculator(disk).calculate(request) })
        assertEquals(0.0, on.result.units ?: 0.0)
        assertEquals(off.profile.target_bg, on.profile.target_bg)
        assertEquals(off.result.predBGs, on.result.predBGs)
        assertEquals(off.result.variable_sens, on.result.variable_sens)
        assertTrue(on.nightModeActive)
        assertFalse(off.nightModeActive)
    }

    @Test fun nightPolicyCannotOverrideIncomingSmbDenialEvenOutsideTheWindow() {
        val request = nightRequest(false).let {
            val at = it.atMs - 10 * 3_600_000L
            val glucose = object : GlucoseStatus by it.glucose { override val date = at }
            it.copy(atMs = at, glucose = glucose)
        }
        val disk = Disk().apply { values[BoostApsSettingKeys.ApsBoostNightModeEnabled.key] = true }
        val result = assertIs<BoostApsCalculator.Result.Calculated>(immediate { calculator(disk).calculate(request) })
        assertEquals(0.0, result.result.units ?: 0.0)
    }

    @Test fun enabledNightPolicyRequiresOriginalTargetBeforeCreatingOrRestoringState() {
        for (target in listOf(null, Double.NaN, 0.0, Double.POSITIVE_INFINITY)) {
            val disk = Disk().apply { values[BoostApsSettingKeys.ApsBoostNightModeEnabled.key] = true }
            val rejected = assertIs<BoostApsCalculator.Result.Rejected>(immediate {
                calculator(disk).calculate(nightRequest().copy(originalProfileTargetMgDl = target))
            })
            assertTrue(rejected.reason.contains("original Boost night target"))
            assertTrue(disk.values.keys.none { it == BoostApsSettingKeys.Session.key })
        }
    }

    @Test fun disabledNightModeDoesNotReadUnusedCorruptOffset() {
        val disk = Disk().apply { values[BoostApsSettingKeys.ApsBoostNightModeBgOffset.key] = Double.NaN }
        assertIs<BoostApsCalculator.Result.Calculated>(immediate { calculator(disk).calculate(input()) })
    }

    @Test fun storedNightSettingsUseIndependentSwitchesAndCanonicalMgdl() {
        for (flags in 0..7) {
            val disk = Disk().apply {
                values[BoostApsSettingKeys.ApsBoostNightModeEnabled.key] = true
                values[BoostApsSettingKeys.ApsBoostNightModeAutoBySleep.key] = flags and 1 != 0
                values[BoostApsSettingKeys.ApsBoostNightModeDisableWithCob.key] = flags and 2 != 0
                values[BoostApsSettingKeys.ApsBoostNightModeDisableWithLowTt.key] = flags and 4 != 0
                values[BoostApsSettingKeys.ApsBoostNightModeStart.key] = "7:00"
                values[BoostApsSettingKeys.ApsBoostNightModeEnd.key] = "24:00"
                values[BoostApsSettingKeys.ApsBoostNightModeBgOffset.key] = 27.5
            }
            assertEquals(BoostNightModePolicy.Settings(true, 7 * 3_600_000L, 0L,
                flags and 1 != 0, flags and 2 != 0, flags and 4 != 0, 27.5),
                BoostApsRuntimeSettings(disk.store).night())
        }
    }

    @Test fun nightWindowFollowsSourceWallClockOnSpringAndAutumnDstDays() {
        val zone = java.time.ZoneId.of("America/New_York")
        for ((local, expectedActive) in listOf("2026-03-08T07:30" to false, "2026-11-01T06:30" to true)) {
            val at = java.time.LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()
            val base = nightRequest()
            val glucose = object : GlucoseStatus by base.glucose { override val date = at }
            val request = base.copy(atMs = at, glucose = glucose)
            val disk = Disk().apply { values[BoostApsSettingKeys.ApsBoostNightModeEnabled.key] = true }
            val result = assertIs<BoostApsCalculator.Result.Calculated>(immediate { calculator(disk, zone = zone).calculate(request) })
            assertEquals(expectedActive, result.nightModeActive, local)
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
