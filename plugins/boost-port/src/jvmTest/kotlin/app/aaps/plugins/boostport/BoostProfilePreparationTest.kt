package app.aaps.plugins.boostport

import androidx.collection.LongSparseArray
import app.aaps.core.data.aps.AverageTDD
import app.aaps.core.data.model.TDD
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.DynamicCarbRatioPolicy
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.plugins.aps.openAPSBoost.BoostV1ReplayEngine
import app.aaps.plugins.aps.openAPSBoost.replay.*
import app.aaps.plugins.boostport.reference.ReferenceBoostIsfPreparation
import app.aaps.plugins.boostport.reference.ReferenceBoostProfileAdapter
import app.aaps.plugins.boostport.reference.ReferenceDetermineBasalBoost
import java.lang.reflect.Proxy
import java.text.DecimalFormat
import kotlin.coroutines.*
import kotlin.random.Random
import kotlin.test.*

class BoostProfilePreparationTest {
    private val presentation = object : V1ReplayPresentation {
        override fun glucose(value: Double) = value.toString()
        override fun fixed2(value: Double): String = DecimalFormat("0.00#").format(value)
        override fun withoutZeros(value: Double): String = DecimalFormat("0.##").format(value)
    }
    private fun settings(tdd: Boolean = true) = BoostIsfSettings(1.2, 0.7, tdd, 0.6, 210.0, 100.0,
        true, true, 160, true, 100, true)
    private fun input() = BoostIsfInputs(50.0, 100, 100.0, 55, 180.0, false, 1.15)
    private fun parts() = BoostTddParts(40.0, 45.0, 50.0, 8.0, 6.0)
    private fun context() = BoostProfileContext(V1ReplayFixture.now, 180.0, 100,
        100.0, 100.0, 110.0, 105.0, 75, true, 3.0, 10.0, 85.0, 1.0,
        250.0, true, false, false, "RESTING")

    @Test fun completeIsfPreparationMatchesSourceAcrossTwoThousandCombinations() {
        val random = Random(271026)
        repeat(2000) { n ->
            val cfg = settings(n % 2 == 0).copy(adjustSensitivity = n % 3 != 0,
                autosensWhenNoTdd = n % 5 != 0, adjustmentFactor = listOf(0, 1, 100, 300, 400)[n % 5])
            val input = input().copy(profileSens = random.nextDouble(20.0, 100.0),
                profilePercent = random.nextInt(70, 151), glucoseValue = random.nextDouble(50.0, 350.0),
                targetBg = random.nextDouble(80.0, 180.0), isTempTarget = n % 4 == 0,
                orefAutosensRatio = random.nextDouble(0.7, 1.3))
            val p = when (n % 6) {
                0 -> parts().copy(average7Days = null)
                1 -> parts().copy(last4Hours = null)
                2 -> parts().copy(last24Hours = 0.0)
                else -> parts().copy(last4Hours = random.nextDouble(1.0, 20.0))
            }
            assertEquals(ReferenceBoostIsfPreparation.calculate(input, cfg, p),
                BoostIsfPreparation.calculate(input, cfg, p), "ISF preparation $n")
        }
    }

    @Test fun autosensIsAppliedOnceAndRemainsAlternativeToTddAndTempTargetAdjustment() {
        val c = settings(false)
        val i = input().copy(orefAutosensRatio = 1.2)
        val normal = BoostIsfPreparation.calculate(i, c, parts())
        assertEquals(41.7, normal.sensNormalTarget)
        assertEquals(41.7, normal.variableSens)
        assertEquals(0.0, normal.dynIsfVelocity)
        assertEquals(1.2, BoostIsfPreparation.selectSensitivityRatio(false, true, normal.ratio, 1.2))
        val legacy = BoostIsfPreparation.calculate(i, c.copy(autosensWhenNoTdd = false), parts())
        assertEquals(50.0, legacy.sensNormalTarget)
        val temp = BoostIsfPreparation.calculate(i.copy(isTempTarget = true, targetBg = 160.0), c, parts())
        assertEquals(71.4, temp.sensNormalTarget)
        assertEquals(0.7, temp.ratio)
        val tddA = BoostIsfPreparation.calculate(i, settings(), parts())
        val tddB = BoostIsfPreparation.calculate(i.copy(orefAutosensRatio = 0.7), settings(), parts())
        assertEquals(tddA, tddB)
    }

    @Test fun weightedTddPullDownBoundaryAndMissingHistoryMatchSource() {
        val c = settings().copy(adjustSensitivity = false)
        val low = parts().copy(average7Days = 100.0, average1Day = 80.0, last4Hours = 5.0, last8To4Hours = 5.0)
        val result = BoostIsfPreparation.calculate(input(), c, low)
        // W8H=30, adjusted7D=30+(30/100)*(100-30)=51.
        assertEquals(51.0 * 0.34 + 80.0 * 0.33 + 30.0 * 0.33, result.tdd)
        for (p in listOf(low.copy(average7Days = 0.0), low.copy(average1Day = null), low.copy(last8To4Hours = null))) {
            val r = BoostIsfPreparation.calculate(input(), c, p)
            assertEquals(0.0, r.tdd)
            assertTrue(r.isfDebug.contains("incomplete"))
            assertEquals(ReferenceBoostIsfPreparation.calculate(input(), c, p), r)
        }
    }

    @Test fun allSeventyThreeProfileFieldsMatchTheSourceConstructor() {
        val base = baseProfile()
        for (percent in listOf(80, 100, 120)) for (useTdd in listOf(false, true))
            for (activity in listOf("RESTING", "STRESS", "RESISTANCE")) for (recover in listOf(false, true)) {
                val ctx = context().copy(profilePercent = percent, activityState = activity,
                    recoveryEnabled = recover, recoveryEndMs = V1ReplayFixture.now + 600_000L, recoveryScale = 0.6,
                    steps5 = 10, steps15 = 20, steps30 = 40, steps60 = 80,
                    recentLowBg = 72.0, brakingProduct = 1200.0, dynamicCarbRatio = 8.5,
                    dynamicCarbRatioReason = "recorded", boostDebug = "source activity")
                val cfg = settings(useTdd)
                val autosens = AutosensResult(ratio = 1.15, carbsAbsorbed = 15.0, sensResult = "recorded oref")
                val originalAutosens = autosens.copy()
                val prepared = BoostTargetProfileAdapter.prepare(base, autosens, cfg, parts(), ctx)
                val originalIsf = ReferenceBoostIsfPreparation.calculate(BoostIsfInputs(base.sens / (percent / 100.0),
                    percent, ctx.isfTargetBg, 55, ctx.glucose, base.temptargetSet, autosens.ratio), cfg, parts())
                val expected = ReferenceBoostProfileAdapter.build(base, cfg, ctx, originalIsf)
                assertEquals(expected, prepared.profile)
                assertEquals(originalAutosens, autosens, "input Autosens not mutated")
                assertEquals(ReferenceBoostIsfPreparation.selectSensitivityRatio(useTdd, cfg.autosensWhenNoTdd,
                    originalIsf.ratio, autosens.ratio), prepared.autosens.ratio)
                assertEquals(15.0, prepared.autosens.carbsAbsorbed)
            }
    }

    @Test fun peakClampRecoveryBoundaryAndCanonicalUnitsArePreserved() {
        val base = baseProfile().copy(current_basal = 1.3, bolus_increment = 0.025, max_basal = 3.9, max_iob = 2.7)
        for ((peak, expectedPeak, divisor) in listOf(Triple(20, 30, 90), Triple(59, 59, 61), Triple(60, 60, 70), Triple(100, 75, 55))) {
            val p = BoostTargetProfileAdapter.prepare(base, AutosensResult(), settings(), parts(), context().copy(insulinPeak = peak)).profile
            assertEquals(expectedPeak, p.insulinPeak); assertEquals(divisor, p.insulinDivisor)
            assertEquals(1.3, p.current_basal); assertEquals(0.025, p.bolus_increment)
            assertEquals(3.9, p.max_basal); assertEquals(2.7, p.max_iob)
        }
        val ctx = context().copy(recoveryEnabled = true, recoveryEndMs = V1ReplayFixture.now, recoveryScale = 0.5)
        assertEquals(3.0, BoostTargetProfileAdapter.prepare(base, AutosensResult(), settings(), parts(), ctx).profile.boost_bolus)
        val earlier = ctx.copy(atMs = ctx.atMs - 1)
        val recovery = BoostTargetProfileAdapter.prepare(base, AutosensResult(), settings(), parts(), earlier).profile
        assertEquals(1.5, recovery.boost_bolus); assertTrue(recovery.v5_inPostExerciseWindow)
    }

    @Test fun preparedTargetProfileFeedsTheCompleteSourceAndPortableV1EnginesEqually() {
        for (useTdd in listOf(false, true)) for (percent in listOf(80, 100, 120)) {
            val ctx = context().copy(glucose = 270.0, profilePercent = percent)
            val prepared = BoostTargetProfileAdapter.prepare(baseProfile(), AutosensResult(ratio = 1.15), settings(useTdd), parts(), ctx)
            val c = V1ReplayFixture.cycle(270.0, 12.0, 8.0, 4.0, 0.5).copy(profile = prepared.profile, autosens = prepared.autosens)
            val source = ReferenceDetermineBasalBoost(presentation, V1ReplayFixture.clock)
                .determine_basal(c.glucose, c.temp.copy(), c.iob, c.profile.copy(), c.autosens.copy(), c.meal.copy(),
                    c.microBolusAllowed, c.time, c.flat, recentSmbVolume60Min = c.recentSmb,
                    cumulativeSmbCap60Min = c.cumulativeCap, recentLowBG45Min = c.recentLow45,
                    timeSinceLastSmbMin = c.sinceLastSmb, postRescueTightRampCap = c.rescueRampCap)
            val actual = V1ReplayFixture.run(c, BoostV1ReplayEngine(presentation, V1ReplayFixture.clock))
            assertEquals(source.copy(reason = actual.reason), actual)
            assertEquals(source.reason.toString(), actual.reason.toString())
        }
    }

    @Test fun boostProfileUsesTheSameSharedCrPolicyWithoutAnotherHistoryReadOrProfileEdit() {
        var request: DynamicCarbRatioPolicy.Input? = null
        var history: DynamicCarbRatioPolicy.TddParts? = null
        val policy = object : DynamicCarbRatioPolicy {
            override suspend fun calculate(input: DynamicCarbRatioPolicy.Input, knownTdd: DynamicCarbRatioPolicy.TddParts?): DynamicCarbRatioPolicy.Result {
                request = input; history = knownTdd
                return DynamicCarbRatioPolicy.Result(8.0, "shared CR", true)
            }
        }
        val base = baseProfile()
        val ctx = context()
        val prepared = immediate { BoostTargetProfileAdapter.prepareWithSharedCr(base.copy(carb_ratio = 8.0), AutosensResult(), settings(), parts(), ctx, policy, profileCarbRatio = 10.0) }
        assertEquals(8.0, prepared.profile.dynamicCarbRatio)
        assertEquals(10.0, prepared.profile.carb_ratio, "retain the original CR; apply the dynamic value once")
        assertEquals("shared CR", prepared.profile.dynamicCarbRatioReason)
        assertEquals(10.0, base.carb_ratio)
        assertEquals(DynamicCarbRatioPolicy.Input(10.0, ctx.glucose, ctx.carbRatioTargetBg, base.sens, ctx.atMs), request)
        assertEquals(40.0, assertNotNull(history).average7Days)
        val off = immediate { BoostTargetProfileAdapter.prepareWithSharedCr(base, AutosensResult(), settings(), parts(), ctx, DynamicCarbRatioPolicy.Disabled, profileCarbRatio = 10.0) }
        assertEquals(0.0, off.profile.dynamicCarbRatio)
        assertEquals(10.0, off.profile.carb_ratio)
    }

    private fun <T> immediate(operation: suspend () -> T): T {
        var result: Result<T>? = null
        operation.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(value: Result<T>) { result = value }
        })
        return assertNotNull(result).getOrThrow()
    }

    @Test fun targetTddReaderUsesOneAnchorAndTheOriginalFiveWindows() {
        val atMs = V1ReplayFixture.now
        val calls = mutableListOf<List<Any?>>()
        val calculator = Proxy.newProxyInstance(TddCalculator::class.java.classLoader, arrayOf(TddCalculator::class.java)) { _, m, args ->
            when (m.name) {
                "calculate" -> {
                    calls.add(listOf(m.name, args[0], args[1], args[2]))
                    LongSparseArray<TDD>().apply { put(0, TDD(timestamp = atMs, utcOffset = 0,
                        totalAmount = if (args[1] == 7L) 40.0 else 45.0)) }
                }
                "averageTDD" -> {
                    @Suppress("UNCHECKED_CAST") val days = args[0] as LongSparseArray<TDD>
                    AverageTDD(days.valueAt(0), true)
                }
                "calculateDaily" -> {
                    calls.add(listOf(m.name, args[0], args[1], args[2]))
                    TDD(timestamp = atMs, utcOffset = 0, totalAmount = when (args[1]) { -24L -> 50.0; -4L -> 8.0; else -> 6.0 })
                }
                else -> error("Unexpected TDD call ${m.name}")
            }
        } as TddCalculator
        var result: Result<BoostTddParts>? = null
        val operation: suspend () -> BoostTddParts = { BoostTargetTddReader.read(calculator, atMs) }
        operation.startCoroutine(object : Continuation<BoostTddParts> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(value: Result<BoostTddParts>) { result = value }
        })
        assertEquals(parts(), assertNotNull(result).getOrThrow())
        assertEquals<List<List<Any?>>>(listOf(
            listOf("calculate", atMs, 7L, true), listOf("calculate", atMs, 1L, true),
            listOf("calculateDaily", atMs, -24L, 0L), listOf("calculateDaily", atMs, -4L, 0L),
            listOf("calculateDaily", atMs, -8L, -4L)), calls)
    }

    private fun baseProfile(): OapsProfile = targetProfileFixture()

}
