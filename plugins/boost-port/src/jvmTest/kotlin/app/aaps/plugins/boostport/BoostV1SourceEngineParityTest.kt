package app.aaps.plugins.boostport

import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.MealData
import app.aaps.plugins.aps.openAPSBoost.BoostV1ReplayEngine
import app.aaps.plugins.aps.openAPSBoost.replay.*
import app.aaps.plugins.boostport.reference.ReferenceDetermineBasalBoost
import org.json.JSONArray
import org.json.JSONObject
import java.text.DecimalFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Runs the full archived source and the portable engine on identical synthetic cycles. */
class BoostV1SourceEngineParityTest {
    private val presentation = object : V1ReplayPresentation {
        override fun glucose(value: Double) = value.toString()
        override fun fixed2(value: Double): String = DecimalFormat("0.00#").format(value)
        override fun withoutZeros(value: Double): String = DecimalFormat("0.##").format(value)
    }

    private fun reference(c: V1ReplayFixture.Cycle, engine: ReferenceDetermineBasalBoost) = engine.determine_basal(
        glucose_status = c.glucose, currenttemp = c.temp.copy(),
        iob_data_array = c.iob, profile = c.profile.copy(), autosens_data = c.autosens.copy(),
        meal_data = c.meal.copy(), microBolusAllowed = c.microBolusAllowed,
        currentTime = c.time, flatBGsDetected = c.flat, riskModel = c.riskModel, mealModel = c.mealModel,
        recentSmbVolume60Min = c.recentSmb, cumulativeSmbCap60Min = c.cumulativeCap,
        recentLowBG45Min = c.recentLow45, timeSinceLastSmbMin = c.sinceLastSmb,
        postRescueTightRampCap = c.rescueRampCap
    )

    private fun compare(
        cycle: V1ReplayFixture.Cycle,
        original: ReferenceDetermineBasalBoost = ReferenceDetermineBasalBoost(presentation, V1ReplayFixture.clock),
        candidate: BoostV1ReplayEngine = BoostV1ReplayEngine(presentation, V1ReplayFixture.clock)
    ): V1ReplayResult {
        val expected = reference(cycle, original)
        val actual = V1ReplayFixture.run(cycle, candidate)
        val label = "BG=${cycle.glucose.glucose}, delta=${cycle.glucose.delta}, IOB=${cycle.iob.first().iob}"
        assertEquals(expected.algorithm, actual.algorithm, label)
        assertEquals(expected.rate, actual.rate, "$label rate")
        assertEquals(expected.duration, actual.duration, "$label duration")
        assertEquals(expected.units, actual.units, "$label SMB")
        assertEquals(expected.carbsReq, actual.carbsReq, "$label carbs")
        assertEquals(expected.carbsReqWithin, actual.carbsReqWithin, "$label carbs horizon")
        assertEquals(expected.targetBG, actual.targetBG, "$label target")
        assertEquals(expected.eventualBG, actual.eventualBG, "$label eventual BG")
        assertEquals(expected.insulinReq, actual.insulinReq, "$label insulinReq")
        assertEquals(expected.minGuardBG, actual.minGuardBG, "$label guard")
        assertEquals(expected.dynamicISF, actual.dynamicISF, "$label ISF")
        assertEquals(expected.variable_sens, actual.variable_sens, "$label prediction ISF")
        assertEquals(expected.sensitivityRatio, actual.sensitivityRatio, "$label autosens")
        assertEquals(expected.carbRatio, actual.carbRatio, "$label carb ratio")
        assertEquals(expected.predBGs, actual.predBGs, "$label all prediction arrays")
        assertEquals(expected.boostTier, actual.boostTier, "$label tier")
        assertEquals(expected.fastCarbProtection, actual.fastCarbProtection, "$label fast carb")
        assertEquals(expected.mlHypoRisk, actual.mlHypoRisk, "$label ML risk")
        assertEquals(expected.mlMealLikely, actual.mlMealLikely, "$label meal likelihood")
        assertEquals(expected.mlRiskScale, actual.mlRiskScale, "$label risk scale")
        assertEquals(expected.mlMealG3Released, actual.mlMealG3Released, "$label G3 release")
        assertEquals(expected.mlG3ReleaseSource, actual.mlG3ReleaseSource, "$label G3 source")
        assertEquals(expected.deliverAt, actual.deliverAt, "$label recorded time")
        assertEquals(expected.reason.toString(), actual.reason.toString(), "$label reason")
        assertEquals(expected.consoleLog, actual.consoleLog, "$label log")
        assertEquals(expected.consoleError, actual.consoleError, "$label trace")
        return actual
    }

    @Test fun normalCycleMatchesSource() {
        val r = compare(V1ReplayFixture.cycle(110.0, 1.0, 1.0, 1.0, 0.1))
        assertNotNull(r.predBGs)
    }

    @Test fun hypoglycemiaMatchesSourceSuspend() {
        val r = compare(V1ReplayFixture.cycle(55.0, -2.0, -1.0, -1.0, 0.4))
        assertEquals(0.0, r.rate)
        assertTrue(assertNotNull(r.duration) >= 30)
    }

    @Test fun maxIobExhaustionMatchesSource() {
        val c = V1ReplayFixture.cycle(230.0, 5.0, 4.0, 3.0, 5.0)
        val r = compare(c.copy(profile = V1ReplayFixture.profile(230.0, maxIob = 1.5)))
        assertEquals(0.0, r.units ?: 0.0)
    }

    @Test fun smbDecisionMatchesSourceAndActuallyProducesASmb() {
        val r = compare(V1ReplayFixture.cycle(270.0, 12.0, 8.0, 4.0, 0.5))
        assertEquals("UAM_BOOST", r.boostTier)
        assertTrue(assertNotNull(r.units) > 0.0)
    }

    @Test fun unreliableCgmEarlyReturnsMatchSource() {
        val c = V1ReplayFixture.cycle().copy(temp = CurrentTemp(40, 2.0, 0))
        for (g in listOf(
            c.glucose.copy(glucose = 38.0), c.glucose.copy(noise = 3.0),
            c.glucose.copy(date = c.time - 13 * 60_000L),
            c.glucose.copy(date = c.time + 6 * 60_000L)
        )) {
            val r = compare(c.copy(glucose = g))
            assertEquals(1.0, r.rate)
            assertEquals(30, r.duration)
        }
    }

    @Test fun flatCgmShortensZeroTempAsInSource() {
        val c = V1ReplayFixture.cycle().copy(flat = true, temp = CurrentTemp(60, 0.0, 0))
        val r = compare(c)
        assertEquals(0.0, r.rate)
        assertEquals(30, r.duration)
    }

    @Test fun cobForecastAndDynamicCarbRatioMatchSource() {
        val c = V1ReplayFixture.cycle(200.0, 10.0, 7.0, 3.0, 0.5)
        val r = compare(c.copy(
            profile = c.profile.copy().apply { dynamicCarbRatio = 8.5; dynamicCarbRatioReason = "recorded" },
            meal = MealData(carbs = 40.0, mealCOB = 30.0, lastCarbTime = c.time - 600_000L)
        ))
        assertEquals(8.5, r.carbRatio)
        assertNotNull(r.predBGs?.COB)
    }

    @Test fun cumulativeSmbThresholdAndPostRescueCapsMatchSource() {
        val c = V1ReplayFixture.cycle(270.0, 12.0, 8.0, 4.0, 0.5)
        // Source compatibility, NOT a remaining-headroom safety guarantee:
        // the archived source checks past SMB volume >= cap before this cycle.
        val belowThreshold = compare(c.copy(cumulativeCap = 0.2, recentSmb = 0.15))
        assertTrue(assertNotNull(belowThreshold.units).isFinite())
        assertTrue(belowThreshold.units!! > 0.05)
        val reached = compare(c.copy(cumulativeCap = 0.2, recentSmb = 0.2))
        assertEquals(0.0, reached.units ?: 0.0)
        assertEquals("CUMULATIVE_SMB_CAP", reached.boostTier)
        for (cap in listOf(null, 0.3, 0.6)) {
            compare(c.copy(recentLow45 = 70.0, rescueRampCap = cap))
        }
    }

    @Test fun circadianAndNeutralTempClockBranchesMatchSource() {
        for (hour in listOf(0, 6, 12, 18, 23)) {
            val time = V1ReplayFixture.now.floorDiv(86_400_000L) * 86_400_000L + hour * 3_600_000L + 58 * 60_000L
            val c = V1ReplayFixture.cycle(180.0, 3.0, 2.0, 1.0, 0.5)
            compare(c.copy(time = time, glucose = c.glucose.copy(date = time),
                profile = c.profile.copy().apply { enableCircadianISF = true; skip_neutral_temps = true }))
        }
    }

    private class FixedRisk(private val names: List<String>, private val score: Double) : V1ReplayRiskModel {
        var vector: List<Double>? = null
        override fun getFeatureNames() = names
        override fun predict(features: DoubleArray): Double {
            vector = features.toList()
            return score
        }
        override fun predictHypoRisk(cgmMgdl: Double, iobTotal: Double, iobBasal: Double,
            bgAboveTarget: Double, directionNum: Double, hour: Int, iobActivity: Double, insulinReq: Double) = score
    }

    private fun names(): List<String> = listOf(
        "cgm_mgdl", "iob_iob", "iob_basaliob", "bg_above_target", "direction_num", "hour",
        "iob_activity", "sug_insulinReq", "sug_COB", "sug_eventualBG", "sug_expectedDelta",
        "sug_minDelta", "sug_TDD", "iob_bolusiob", "iob_netbasalinsulin", "recent_smb_units_60m",
        "time_since_last_smb_min"
    ) + V1ReplayFeatures.LOOKBACK_FEATURES.flatMap { feature -> (0..5).map { "${feature}_lag$it" } }

    @Test fun featureContractMatchesTheArchivedModelAsset() {
        val json = javaClass.classLoader.getResourceAsStream("boost/hypo_risk_model.json")
            ?.bufferedReader()?.use { it.readText() }
        val declared = JSONObject(assertNotNull(json)).getJSONArray("feature_names")
        assertEquals((0 until declared.length()).map { declared.getString(it) }, names())
    }

    @Test fun legacyModelRiskDowngradeAndScalingMatchSource() {
        for (risk in listOf(0.2, 0.5, 0.8)) {
            val c = V1ReplayFixture.cycle(270.0, 12.0, 8.0, 4.0, 0.5)
            val r = compare(c.copy(riskModel = FixedRisk(names().take(8), risk),
                mealModel = V1ReplayMealModel { _, _, _, _, _, _, _, _ -> 0.7 }))
            assertEquals(risk, r.mlHypoRisk)
            assertEquals(0.7, r.mlMealLikely)
        }
    }

    @Test fun fullFeatureVectorsAndHistoryMatchSourceAcrossCadenceAndGaps() {
        val original = ReferenceDetermineBasalBoost(presentation, V1ReplayFixture.clock)
        val candidate = BoostV1ReplayEngine(presentation, V1ReplayFixture.clock)
        val expectedRisk = FixedRisk(names(), 0.2)
        val actualRisk = FixedRisk(names(), 0.2)
        for ((n, offset) in listOf(0L, 60_000L, 300_000L, 600_000L, 7_200_000L).withIndex()) {
            val time = V1ReplayFixture.now + offset
            val c = V1ReplayFixture.cycle(270.0 + n, 12.0, 8.0, 4.0, 0.5)
                .copy(time = time, recentSmb = 0.1 * n)
            val before = reference(c.copy(glucose = c.glucose.copy(date = time), riskModel = expectedRisk), original)
            val after = V1ReplayFixture.run(c.copy(glucose = c.glucose.copy(date = time), riskModel = actualRisk), candidate)
            assertEquals(before.units, after.units)
            assertEquals(before.reason.toString(), after.reason.toString())
            val vector = assertNotNull(expectedRisk.vector)
            assertEquals(53, vector.size)
            assertEquals(vector, actualRisk.vector)
        }
        val sourceHistory = JSONArray(original.serializeMlRingBuffer())
        val history = candidate.snapshotFeatureHistory()
        assertEquals(sourceHistory.length(), history.size)
        assertEquals(1, history.size)
        assertEquals(sourceHistory.getJSONObject(0).getLong("ts"), history.single().ts)
    }

    @Test fun historyRestorationRunsOnlyOnceAsInSource() {
        val original = ReferenceDetermineBasalBoost(presentation, V1ReplayFixture.clock)
        val candidate = BoostV1ReplayEngine(presentation, V1ReplayFixture.clock)
        val snapshot = V1ReplayFeatures.CycleSnapshot(V1ReplayFixture.now - 300_000L, 240.0, 0.3, 0.01, 210.0, 0.2, 6.0)
        val json = JSONArray().put(JSONObject().put("ts", snapshot.ts).put("cgm_mgdl", snapshot.cgmMgdl)
            .put("iob_iob", snapshot.iobIob).put("iob_activity", snapshot.iobActivity)
            .put("sug_eventualBG", snapshot.sugEventualBG).put("recent_smb_units_60m", snapshot.recentSmbUnits60m)
            .put("sug_minDelta", snapshot.sugMinDelta)).toString()
        original.loadMlRingBufferOnce(json)
        candidate.loadFeatureHistoryOnce(listOf(snapshot))
        original.loadMlRingBufferOnce("[]")
        candidate.loadFeatureHistoryOnce(emptyList())
        val model = FixedRisk(names(), 0.2)
        compare(V1ReplayFixture.cycle(270.0, 12.0, 8.0, 4.0, 0.5).copy(riskModel = model), original, candidate)
        assertEquals(2, candidate.snapshotFeatureHistory().size)
        assertEquals(snapshot, candidate.snapshotFeatureHistory().first())
    }
}
