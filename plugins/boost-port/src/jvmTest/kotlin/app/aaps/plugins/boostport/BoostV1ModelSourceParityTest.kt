package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1ReplayEngine
import app.aaps.plugins.aps.openAPSBoost.replay.*
import app.aaps.plugins.boostport.reference.ReferenceBoostMealModel
import app.aaps.plugins.boostport.reference.ReferenceBoostRiskModel
import app.aaps.plugins.boostport.reference.ReferenceDetermineBasalBoost
import app.aaps.plugins.boostport.telemetry.BoostMlAssetParser
import app.aaps.plugins.boostport.telemetry.BoostMlAssetSchemaAudit.Kind
import app.aaps.plugins.boostport.telemetry.BoostMlTreeDiagnostics
import org.json.JSONObject
import java.text.DecimalFormat
import kotlin.random.Random
import kotlin.test.*

/** Real archived assets and original model code; no fixed-score callbacks. */
class BoostV1ModelSourceParityTest {
    private fun raw(path: String): String = checkNotNull(javaClass.classLoader.getResourceAsStream(path))
        .bufferedReader().use { it.readText() }

    private val riskText by lazy { raw("boost/hypo_risk_model.json") }
    private val mealText by lazy { raw("boost/meal_likelihood_model.json") }
    private fun riskSource() = ReferenceBoostRiskModel { riskText }
    private fun mealSource() = ReferenceBoostMealModel { mealText }
    private fun parsed(kind: Kind, text: String): BoostMlTreeDiagnostics.Model =
        assertIs<BoostMlAssetParser.Result.Ready>(BoostMlAssetParser.parse(kind, text)).model
    private fun risk() = assertNotNull(V1ReplayRiskTreeModel.from(parsed(Kind.HYPO_RISK_V12, riskText)))
    private fun meal() = assertNotNull(V1ReplayMealTreeModel.from(parsed(Kind.MEAL_LIKELIHOOD, mealText)))

    private fun vector(random: Random): DoubleArray = riskNames.map { name ->
        when {
            name.startsWith("cgm_mgdl") || name.startsWith("sug_eventualBG") -> random.nextDouble(40.0, 350.0)
            name.startsWith("iob_activity") -> random.nextDouble(-0.01, 0.15)
            name.startsWith("recent_smb_units_60m") -> random.nextDouble(0.0, 5.0)
            name.startsWith("iob_iob") -> random.nextDouble(-2.0, 10.0)
            name.startsWith("sug_minDelta") || name == "sug_expectedDelta" -> random.nextDouble(-20.0, 20.0)
            name == "iob_basaliob" || name == "iob_netbasalinsulin" -> random.nextDouble(-2.0, 2.0)
            name == "bg_above_target" -> random.nextDouble(-60.0, 250.0)
            name == "direction_num" -> random.nextInt(-4, 5) / 2.0
            name == "hour" -> random.nextInt(24).toDouble()
            name == "time_since_last_smb_min" -> random.nextDouble(0.0, 720.0)
            name == "sug_COB" -> random.nextDouble(0.0, 100.0)
            name == "sug_TDD" -> random.nextDouble(5.0, 100.0)
            else -> random.nextDouble(0.0, 5.0)
        }
    }.toDoubleArray()

    private fun mealScore(model: V1ReplayMealModel, f: DoubleArray) = model.predictMealLikelihood(
        f[0], f[1], f[2], f[3], f[4], f[5].toInt(), f[6], f[7])

    @Test fun actualRiskAssetMatchesSourceAcrossTwoThousandVectors() {
        val original = riskSource()
        val candidate = risk()
        assertEquals(original.getFeatureNames(), candidate.getFeatureNames())
        assertEquals(100, original.getTreeCount())
        val random = Random(20261027)
        val scores = mutableSetOf<Double>()
        repeat(2000) {
            val f = vector(random)
            val expected = assertNotNull(original.predict(f))
            val actual = assertNotNull(candidate.predict(f))
            assertEquals(expected, actual, "risk vector $it")
            assertTrue(actual in 0.0..1.0)
            scores.add(actual)
        }
        assertTrue(scores.size > 100, "exercise different learned paths")
    }

    @Test fun actualMealAssetMatchesSourceAcrossTwoThousandVectors() {
        val original = mealSource()
        val candidate = meal()
        val random = Random(20261028)
        val scores = mutableSetOf<Double>()
        repeat(2000) {
            val f = vector(random)
            val actual = assertNotNull(mealScore(candidate, f))
            assertEquals(mealScore(original, f), actual, "meal vector $it")
            assertTrue(actual in 0.0..1.0)
            scores.add(actual)
        }
        assertEquals(50, original.getTreeCount())
        assertEquals(riskNames.take(8), original.getFeatureNames())
        assertTrue(scores.size > 100)
    }

    @Test fun everyRiskSplitThresholdAndAdjacentDoublesMatchSource() {
        val original = riskSource()
        val candidate = risk()
        val model = parsed(Kind.HYPO_RISK_V12, riskText)
        val f = vector(Random(7))
        var checked = 0
        fun visit(node: BoostMlTreeDiagnostics.Node) {
            if (node !is BoostMlTreeDiagnostics.Split) return
            val before = f[node.featureIndex]
            for (value in listOf(Math.nextDown(node.threshold), node.threshold, Math.nextUp(node.threshold))) {
                f[node.featureIndex] = value
                assertEquals(original.predict(f), candidate.predict(f), "split $checked")
                checked++
            }
            f[node.featureIndex] = before
            visit(node.left)
            visit(node.right)
        }
        model.trees.forEach(::visit)
        assertTrue(checked > 1000)
    }

    @Test fun sourceNonfiniteRiskBranchIsPreserved() {
        val original = riskSource()
        val candidate = risk()
        val f = vector(Random(11))
        for (i in f.indices) {
            val before = f[i]
            for (v in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
                f[i] = v
                assertEquals(original.predict(f), candidate.predict(f), "nonfinite feature $i=$v")
            }
            f[i] = before
        }
    }

    @Test fun sourceMealDirectComparisonIsPreservedForNonfiniteFeatures() {
        val original = mealSource()
        val candidate = meal()
        val f = vector(Random(12))
        for (i in (0..7).filter { it != 5 }) {
            val before = f[i]
            for (v in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
                f[i] = v
                assertEquals(mealScore(original, f), mealScore(candidate, f), "nonfinite meal feature $i=$v")
            }
            f[i] = before
        }
    }

    @Test fun projectedRiskMatchesSourceWithoutAccumulatingOrMutatingCallerInput() {
        val original = riskSource()
        val candidate = risk()
        assertNull(candidate.predictAtProjectedIob(2.0))
        val random = Random(19)
        repeat(100) {
            val f = vector(random)
            assertEquals(original.predict(f), candidate.predict(f))
            // The caller is free to reuse/mutate its array; both models must have copied it.
            f.fill(999.0)
            for (iob in listOf(-1.0, 0.0, 0.25, 1.0, 5.0, 10.0, 1.0))
                assertEquals(original.predictAtProjectedIob(iob), candidate.predictAtProjectedIob(iob))
        }
    }

    @Test fun sizeMismatchDoesNotReplaceTheLastSuccessfulProjectionBase() {
        val original = riskSource()
        val candidate = risk()
        val f = vector(Random(23))
        assertEquals(original.predict(f), candidate.predict(f))
        for (size in listOf(0, 8, 52, 54)) {
            assertNull(original.predict(DoubleArray(size)))
            assertNull(candidate.predict(DoubleArray(size)))
            assertEquals(original.predictAtProjectedIob(3.0), candidate.predictAtProjectedIob(3.0))
        }
        assertNull(candidate.predictHypoRisk(150.0, 1.0, 0.0, 50.0, 1.0, 12, 0.01, 0.5))
        assertNull(original.predictHypoRisk(150.0, 1.0, 0.0, 50.0, 1.0, 12, 0.01, 0.5))
    }

    @Test fun modelCollectionsAreDetachedFromCallerMutation() {
        val model = parsed(Kind.HYPO_RISK_V12, riskText)
        val names = model.featureNames.toMutableList()
        val trees = model.trees.toMutableList()
        val candidate = assertNotNull(V1ReplayRiskTreeModel.from(model.copy(featureNames = names, trees = trees)))
        names.clear()
        trees.clear()
        val f = vector(Random(2))
        assertEquals(riskSource().predict(f), candidate.predict(f))
        (candidate.getFeatureNames() as MutableList).clear()
        assertEquals(53, candidate.getFeatureNames().size)
    }

    @Test fun invalidOrIncompatibleAssetsNeverProduceAPartialModel() {
        val changes: List<(JSONObject) -> Unit> = listOf(
            { it.put("n_trees", 99) }, { it.put("objective", "regression") },
            { it.put("average_output", true) }, { it.getJSONArray("feature_names").put(0, "wrong") },
            { it.getJSONArray("trees").remove(0) },
            { it.getJSONArray("trees").getJSONObject(0).put("feature", 53) },
            { it.getJSONArray("trees").getJSONObject(0).put("decision_type", "<") },
            { it.getJSONArray("trees").getJSONObject(0).remove("right") },
        )
        changes.forEachIndexed { i, mutate ->
            val json = JSONObject(riskText)
            mutate(json)
            assertIs<BoostMlAssetParser.Result.Unavailable>(BoostMlAssetParser.parse(Kind.HYPO_RISK_V12, json.toString()), "corruption $i")
        }
        for (raw in listOf("", "{", " ".repeat(1_000_001), "[".repeat(70) + "0" + "]".repeat(70)))
            assertIs<BoostMlAssetParser.Result.Unavailable>(BoostMlAssetParser.parse(Kind.HYPO_RISK_V12, raw))
        assertIs<BoostMlAssetParser.Result.Unavailable>(BoostMlAssetParser.parse(Kind.MEAL_LIKELIHOOD, riskText))
        assertNull(V1ReplayRiskTreeModel.from(parsed(Kind.MEAL_LIKELIHOOD, mealText)))
        assertNull(V1ReplayMealTreeModel.from(parsed(Kind.HYPO_RISK_V12, riskText)))
    }

    private val presentation = object : V1ReplayPresentation {
        override fun glucose(value: Double) = value.toString()
        override fun fixed2(value: Double): String = DecimalFormat("0.00#").format(value)
        override fun withoutZeros(value: Double): String = DecimalFormat("0.##").format(value)
    }

    @Test fun completeV1CyclesWithRealModelsMatchSourceAcrossCadenceAndRestart() {
        var original = ReferenceDetermineBasalBoost(presentation, V1ReplayFixture.clock)
        var candidate = BoostV1ReplayEngine(presentation, V1ReplayFixture.clock)
        val riskSource = riskSource()
        val mealSource = mealSource()
        val risk = risk()
        val meal = meal()
        val scenarios = listOf(
            V1ReplayFixture.cycle(270.0, 12.0, 8.0, 4.0, 0.5),
            V1ReplayFixture.cycle(200.0, 9.0, 7.0, 3.0, 0.8),
            V1ReplayFixture.cycle(150.0, 6.0, 6.0, 3.0, 0.2),
            V1ReplayFixture.cycle(85.0, -2.0, -3.0, -2.0, 0.4),
            V1ReplayFixture.cycle(55.0, -3.0, -3.0, -2.0, 0.4),
        )
        var scored = 0
        for (n in 0 until 25) {
            if (n == 12) {
                val history = candidate.snapshotFeatureHistory()
                val json = original.serializeMlRingBuffer()
                original = ReferenceDetermineBasalBoost(presentation, V1ReplayFixture.clock).also { it.loadMlRingBufferOnce(json) }
                candidate = BoostV1ReplayEngine(presentation, V1ReplayFixture.clock).also { it.loadFeatureHistoryOnce(history) }
            }
            val base = scenarios[n % scenarios.size]
            val time = base.time + n * 300_000L + (if (n == 1) -240_000L else 0L) + (if (n >= 18) 7_200_000L else 0L)
            val c = base.copy(time = time, glucose = base.glucose.copy(date = time), recentSmb = 0.1 * (n % 5))
            val expected = original.determine_basal(c.glucose, c.temp.copy(), c.iob, c.profile.copy(),
                c.autosens.copy(), c.meal.copy(), c.microBolusAllowed, c.time, c.flat,
                riskSource, mealSource, c.recentSmb, c.cumulativeCap, c.recentLow45, c.sinceLastSmb, c.rescueRampCap)
            val actual = V1ReplayFixture.run(c.copy(riskModel = risk, mealModel = meal), candidate)
            assertEquals(expected.copy(reason = actual.reason), actual, "entire V1 result cycle $n")
            assertEquals(expected.reason.toString(), actual.reason.toString())
            if (actual.mlHypoRisk != null) {
                scored++
                assertNotNull(actual.mlMealLikely)
                assertTrue(actual.mlHypoRisk!! in 0.0..1.0)
            }
        }
        assertTrue(scored >= 10, "ML path must actually run")
    }

    private companion object {
        val riskNames = app.aaps.plugins.boostport.telemetry.BoostMlAssetSchemaAudit.RISK_FEATURES
    }
}
