package app.aaps.plugins.boostport.telemetry

import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostMlTreeDiagnosticsTest {
    private val m = BoostMlTreeDiagnostics
    private fun model(vararg trees: BoostMlTreeDiagnostics.Node) =
        BoostMlTreeDiagnostics.Model(listOf("cgm_mgdl"), trees.toList())

    @Test fun zeroLogitScoresOneHalf() {
        val result = m.score(model(BoostMlTreeDiagnostics.Leaf(0.0)), doubleArrayOf(100.0))!!
        assertEquals(0.0, result.rawLeafSum)
        assertEquals(0.5, result.logisticValue)
        assertEquals(1, result.contributingTrees)
    }

    @Test fun multipleTreesSumLeavesBeforeApplyingSigmoid() {
        val result = m.score(
            model(BoostMlTreeDiagnostics.Leaf(0.5), BoostMlTreeDiagnostics.Leaf(-0.25), BoostMlTreeDiagnostics.Leaf(1.25)),
            doubleArrayOf(100.0)
        )!!
        assertEquals(1.5, result.rawLeafSum, 1e-12)
        assertEquals(1.0 / (1.0 + exp(-1.5)), result.logisticValue, 1e-12)
        assertEquals(3, result.contributingTrees)
    }

    @Test fun splitAtThresholdChoosesLeftAndAboveChoosesRight() {
        val tree = BoostMlTreeDiagnostics.Split(0, 100.0, BoostMlTreeDiagnostics.Leaf(-2.0), BoostMlTreeDiagnostics.Leaf(2.0))
        assertEquals(-2.0, m.score(model(tree), doubleArrayOf(100.0))!!.rawLeafSum)
        assertEquals(2.0, m.score(model(tree), doubleArrayOf(100.1))!!.rawLeafSum)
    }

    @Test fun nestedTreeTraversesCorrectFeatureIndex() {
        val tree = BoostMlTreeDiagnostics.Split(
            0, 150.0,
            BoostMlTreeDiagnostics.Split(1, 0.5, BoostMlTreeDiagnostics.Leaf(-1.0), BoostMlTreeDiagnostics.Leaf(1.0)),
            BoostMlTreeDiagnostics.Leaf(3.0),
        )
        val mdl = BoostMlTreeDiagnostics.Model(listOf("cgm_mgdl", "iob_iob"), listOf(tree))
        assertEquals(-1.0, m.score(mdl, doubleArrayOf(100.0, 0.5))!!.rawLeafSum)
        assertEquals(1.0, m.score(mdl, doubleArrayOf(100.0, 0.6))!!.rawLeafSum)
        assertEquals(3.0, m.score(mdl, doubleArrayOf(160.0, 0.6))!!.rawLeafSum)
    }

    @Test fun missingOrExcessFeaturesAreRejectedNotSilentlyImputed() {
        val md = model(BoostMlTreeDiagnostics.Leaf(0.0))
        assertNull(m.score(md, doubleArrayOf()))
        assertNull(m.score(md, doubleArrayOf(100.0, 0.0)))
    }

    @Test fun nonFiniteFeaturesAndLeavesAreRejected() {
        assertNull(m.score(model(BoostMlTreeDiagnostics.Leaf(0.0)), doubleArrayOf(Double.NaN)))
        assertNull(m.score(model(BoostMlTreeDiagnostics.Leaf(0.0)), doubleArrayOf(Double.POSITIVE_INFINITY)))
        assertNull(m.score(model(BoostMlTreeDiagnostics.Leaf(Double.NaN)), doubleArrayOf(100.0)))
    }

    @Test fun invalidSplitIndexOrThresholdAreRejected() {
        assertNull(m.score(model(BoostMlTreeDiagnostics.Split(2, 10.0, BoostMlTreeDiagnostics.Leaf(1.0), BoostMlTreeDiagnostics.Leaf(2.0))),
            doubleArrayOf(100.0)))
        assertNull(m.score(model(BoostMlTreeDiagnostics.Split(0, Double.NaN, BoostMlTreeDiagnostics.Leaf(1.0), BoostMlTreeDiagnostics.Leaf(2.0))),
            doubleArrayOf(100.0)))
    }

    @Test fun emptyOrAmbiguousModelCannotProducePrediction() {
        assertNull(m.score(BoostMlTreeDiagnostics.Model(listOf("cgm_mgdl"), emptyList()), doubleArrayOf(100.0)))
        assertNull(m.score(BoostMlTreeDiagnostics.Model(emptyList(), listOf(BoostMlTreeDiagnostics.Leaf(1.0))), doubleArrayOf()))
        assertNull(m.score(
            BoostMlTreeDiagnostics.Model(listOf("cgm_mgdl", "cgm_mgdl"), listOf(BoostMlTreeDiagnostics.Leaf(1.0))),
            doubleArrayOf(100.0, 100.0)
        ))
    }

    @Test fun excessiveTreeCountAndDepthFailClosed() {
        assertNull(m.score(
            BoostMlTreeDiagnostics.Model(listOf("cgm_mgdl"), List(513) { BoostMlTreeDiagnostics.Leaf(0.0) }),
            doubleArrayOf(100.0)
        ))
        var tree: BoostMlTreeDiagnostics.Node = BoostMlTreeDiagnostics.Leaf(0.5)
        repeat(65) { tree = BoostMlTreeDiagnostics.Split(0, 100.0, tree, BoostMlTreeDiagnostics.Leaf(0.0)) }
        assertNull(m.score(model(tree), doubleArrayOf(0.0)))
    }

    @Test fun finiteLogitAlwaysProducesUnitIntervalScore() {
        val result = m.score(model(BoostMlTreeDiagnostics.Leaf(10.0)), doubleArrayOf(100.0))!!
        assertTrue(result.logisticValue in 0.0..1.0)
    }
}
