package app.aaps.plugins.boostport

import app.aaps.core.interfaces.aps.Predictions
import app.aaps.plugins.aps.openAPSBoost.replay.*
import app.aaps.plugins.aps.openAPSBoostV5.*
import app.aaps.plugins.boostport.reference.ReferenceBoostMealModel
import app.aaps.plugins.boostport.reference.ReferenceBoostRiskModel
import app.aaps.plugins.boostport.reference.ReferenceDetermineBasalBoost
import app.aaps.plugins.boostport.reference.ReferenceV5InputAssembly
import app.aaps.plugins.boostport.reference.ReferenceV6ReplayOverride
import app.aaps.plugins.boostport.telemetry.BoostMlAssetParser
import app.aaps.plugins.boostport.telemetry.BoostMlAssetSchemaAudit.Kind
import java.text.DecimalFormat
import kotlin.test.*

class BoostV1V5FullReplayTest {
    private val clock = V1ReplayFixture.clock
    private val presentation = object : V1ReplayPresentation {
        override fun glucose(value: Double) = value.toString()
        override fun fixed2(value: Double): String = DecimalFormat("0.00#").format(value)
        override fun withoutZeros(value: Double): String = DecimalFormat("0.##").format(value)
    }
    private fun raw(path: String) = checkNotNull(javaClass.classLoader.getResourceAsStream(path))
        .bufferedReader().use { it.readText() }
    private val riskText by lazy { raw("boost/hypo_risk_model.json") }
    private val mealText by lazy { raw("boost/meal_likelihood_model.json") }
    private fun runner(withModels: Boolean = true): BoostV1V5Replay {
        fun parsed(kind: Kind, text: String) = assertIs<BoostMlAssetParser.Result.Ready>(BoostMlAssetParser.parse(kind, text)).model
        return BoostV1V5Replay(presentation, clock,
            if (withModels) assertNotNull(V1ReplayRiskTreeModel.from(parsed(Kind.HYPO_RISK_V12, riskText))) else null,
            if (withModels) assertNotNull(V1ReplayMealTreeModel.from(parsed(Kind.MEAL_LIKELIHOOD, mealText))) else null)
    }
    private fun cycle() = V1ReplayFixture.recorded(V1ReplayFixture.cycle(230.0, 8.0, 6.0, 3.0, 0.5))
    private fun reference(c: V1ReplayCycle, r: V1ReplayResult, context: V5ReplayContext,
                          projection: (Double) -> Double? = { null }) = ReferenceV5InputAssembly.referenceV5Inputs(
        context = context, clock = clock, recordedTime = c.time, projectedRisk = projection,
        rT = r, gs = c.glucose, iobArray = c.iob, opb = c.profile,
        pumpBolusStep = context.pumpBolusStep, activeMode = context.activeMode,
        microBolusAllowed = c.microBolusAllowed, flatBGsDetected = c.flatBGsDetected,
        asleep = context.asleep, postRescueWindow = c.recentLowBG45Min < 75.0)

    private fun compareInputs(expected: V5Inputs, actual: V5Inputs) {
        assertEquals(expected.copy(riskAtProjectedIob = actual.riskAtProjectedIob), actual)
        for (iob in listOf(0.0, 0.5, 1.0, 3.0, 10.0))
            assertEquals(expected.riskAtProjectedIob?.invoke(iob), actual.riskAtProjectedIob?.invoke(iob))
    }

    @Test fun allInputFieldsMatchArchivedPluginAcrossContextCombinations() {
        val c = cycle()
        val r = V1ReplayResult(runningDynamicIsf = true, insulinReq = 1.23, units = 0.2,
            eventualBG = 240.0, targetBG = 111.0, minGuardBG = 70.0,
            mlHypoRisk = 0.2, mlMealLikely = 0.7)
        for (active in listOf(false, true)) for (allowed in listOf(false, true))
            for (requested in listOf(false, true)) for (smb in listOf(false, true)) {
                val context = V5ReplayContext(0.05, active,
                    asleep = true, profileSwitched = true, pumpDisconnected = true, loopSuspended = true,
                    manualBolusInAnnouncementWindow = true, fastCarbConfirmEnabled = true,
                    aggressiveEarlyConfirmEnabled = true, composedFloorRequested = requested,
                    velocityBudgetRequested = requested, insulinAddingFloorsAllowed = allowed,
                    aggression = 1.1, hypoCaution = 1.2, sensitivity = 0.9,
                    confirmedCapU = 0.6, committedCapU = 0.7, primerCapU = 0.2,
                    primerTbrFallback = true, primerBolusMode = false)
                val input = c.copy(microBolusAllowed = smb, flatBGsDetected = true, recentLowBG45Min = 70.0,
                    profile = c.profile.copy(v5_exerciseActive = true, v5_inPostExerciseWindow = true))
                val projection = { iob: Double -> iob / 10.0 }
                val actual = V1ToV5ReplayInputs.build(input, r, context, clock, projection)
                compareInputs(reference(input, r, context, projection), actual)
                assertEquals(c.profile.target_bg, actual.targetBg, "source takes profile target, not result target")
                assertEquals(requested && allowed && active, actual.composedFloorActive)
            }
    }

    @Test fun shortHorizonGuardIncludesOnlyFirstSixPointsAndFallsBackInSourceOrder() {
        val c = cycle()
        val context = V5ReplayContext(0.05, true)
        val r = V1ReplayResult(runningDynamicIsf = true, minGuardBG = 70.0)
        val arrays = listOf(
            Predictions(IOB = List(6) { 140 } + 39, UAM = List(6) { 130 } + 39, ZT = listOf(120), COB = emptyList()),
            Predictions(IOB = emptyList()), null,
        )
        for (pred in arrays) {
            r.predBGs = pred
            val actual = V1ToV5ReplayInputs.build(c, r, context, clock) { null }
            compareInputs(reference(c, r, context), actual)
            assertEquals(if (pred?.ZT != null) 120.0 else 70.0, actual.minGuardBg)
        }
        r.minGuardBG = null
        val fallback = V1ToV5ReplayInputs.build(c, r, context, clock) { null }
        assertEquals(c.glucose.glucose, fallback.minGuardBg)
    }

    @Test fun sourceFallbacksAndBolusOverrideArePreserved() {
        val context = V5ReplayContext(0.1, false, primerTbrFallback = true, primerBolusMode = true)
        val c = cycle().let { it.copy(iob = emptyArray(), profile = it.profile.copy(max_iob = 1.5, boost_maxIOB = 10.0)) }
        val r = V1ReplayResult(runningDynamicIsf = false, insulinReq = -0.5, COB = 20.0)
        val actual = V1ToV5ReplayInputs.build(c, r, context, clock) { null }
        compareInputs(reference(c, r, context), actual)
        assertEquals(0.0, actual.baseInsulinReq)
        assertEquals(1.5, actual.maxIob)
        assertEquals(0.0, actual.iob)
        assertEquals(0.0, actual.riskAtProjectedIob?.invoke(3.0))
        assertTrue(actual.mealAnnounced)
        assertFalse(actual.primerUseTempBasal)
    }

    @Test fun completeV1AndV5DecisionsMatchSourceWithRealModelsAndRestart() {
        val riskSource = ReferenceBoostRiskModel { riskText }
        val mealSource = ReferenceBoostMealModel { mealText }
        var originalV1 = ReferenceDetermineBasalBoost(presentation, clock)
        var candidate = runner()
        var sourceOverride = ReferenceV6ReplayOverride()
        var expectedState = V5PersistedState()
        var actualState = V5PersistedState()
        var scored = 0
        var nonzero = 0
        for (n in 0 until 36) {
            if (n == 18) {
                originalV1 = ReferenceDetermineBasalBoost(presentation, clock).also { it.loadMlRingBufferOnce(originalV1.serializeMlRingBuffer()) }
                candidate = runner().also { it.restoreFeatureHistoryOnce(candidate.featureHistory()) }
                sourceOverride = ReferenceV6ReplayOverride()
                // Use the actual legacy persistent-field boundary; transient score streaks are lost.
                expectedState = assertNotNull(V5OfflineStateHandoff.restore(V5OfflineStateHandoff.export(expectedState)).state)
                actualState = assertNotNull(V5OfflineStateHandoff.restore(V5OfflineStateHandoff.export(actualState)).state)
            }
            val bg = when { n < 8 -> 140.0 + n * 12; n < 20 -> 236.0; else -> 230.0 - (n - 20) * 8 }
            val delta = if (n < 20) 8.0 else -3.0
            val base = V1ReplayFixture.cycle(bg, delta, delta, delta / 2, 0.5)
            val time = base.time + n * 300_000L
            val c = V1ReplayFixture.recorded(base.copy(time = time, glucose = base.glucose.copy(date = time)))
            val context = V5ReplayContext(0.05, true,
                profileSwitched = n == 22, loopSuspended = n == 24, pumpDisconnected = n == 26,
                manualBolusInAnnouncementWindow = n in 2..4)
            val sourceV1 = originalV1.determine_basal(c.glucose, c.currentTemp.copy(), c.iob, c.profile.copy(),
                c.autosens.copy(), c.meal.copy(), c.microBolusAllowed, c.time, c.flatBGsDetected,
                riskSource, mealSource, c.recentSmbVolume60Min, c.cumulativeSmbCap60Min,
                c.recentLowBG45Min, c.timeSinceLastSmbMin, c.postRescueTightRampCap)
            val sourceInputs = reference(c, sourceV1, context, riskSource::predictAtProjectedIob)
            val sourceDecision = DetermineBasalBoostV5().decide(sourceInputs, expectedState)
            val actual = candidate.run(c, context, actualState)
            val finalSource = sourceOverride.apply(sourceV1, sourceDecision, c, context, V6ReplaySettings())
            assertEquals(sourceV1.copy(reason = actual.v1.reason), actual.v1, "full V1 at $n")
            assertEquals(sourceV1.reason.toString(), actual.v1.reason.toString())
            compareInputs(sourceInputs, actual.v5Inputs)
            assertEquals(sourceDecision, actual.v5, "full V5 decision/state at $n")
            assertNull(actual.v5Failure)
            assertEquals(finalSource.copy(reason = actual.finalCandidate.reason), actual.finalCandidate, "V6 candidate at $n")
            assertEquals(finalSource.reason.toString(), actual.finalCandidate.reason.toString())
            assertEquals(sourceOverride.heldTrancheU(), candidate.heldTrancheU())
            assertEquals((sourceV1.insulinReq ?: 0.0).coerceAtLeast(0.0), actual.v5Inputs.baseInsulinReq)
            if (actual.v1.mlHypoRisk != null) scored++
            if (assertNotNull(actual.v5).finalDose > 0.0) nonzero++
            expectedState = sourceDecision.newPersistedState
            actualState = actual.nextV5State
        }
        assertTrue(scored >= 15)
        assertTrue(nonzero > 0, "must exercise an actual V5 candidate, not only zero outputs")
    }

    @Test fun absentModelsRetainSourceFallbackAndTrackMealOutage() {
        val replay = runner(withModels = false)
        val context = V5ReplayContext(0.05, true)
        var state = V5PersistedState()
        repeat(4) { n ->
            val c = cycle().copy(time = cycle().time + n * 300_000L)
            val frame = replay.run(c, context, state)
            assertNull(frame.v1.mlHypoRisk)
            assertNull(frame.v1.mlMealLikely)
            assertEquals(0.0, frame.v5Inputs.riskAtProjectedIob?.invoke(3.0))
            state = frame.nextV5State
        }
        assertEquals(4, state.mlMealLikelyNullStreak)
    }
}
