package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostMlAssetSchemaAuditTest {
    private val audit = BoostMlAssetSchemaAudit
    private val leaf = BoostMlTreeDiagnostics.Leaf(0.15)

    private fun model(
        kind: BoostMlAssetSchemaAudit.Kind,
        node: BoostMlTreeDiagnostics.Node = leaf,
    ) = BoostMlTreeDiagnostics.Model(
        featureNames = audit.requiredFeatureNames(kind),
        trees = List(audit.requiredTreeCount(kind)) { node },
    )

    @Test fun riskV12SchemaHasExactlyFiftyThreeInputsAndOneHundredTrees() {
        val kind = BoostMlAssetSchemaAudit.Kind.HYPO_RISK_V12
        val result = audit.inspect(kind, model(kind))!!
        assertEquals(53, result.features)
        assertEquals(100, result.trees)
        assertEquals(100, result.traversedNodes)
        assertEquals(1, result.depthMax)
    }

    @Test fun mealLikelihoodHasOriginalEightInputsAndFiftyTrees() {
        val kind = BoostMlAssetSchemaAudit.Kind.MEAL_LIKELIHOOD
        val result = audit.inspect(kind, model(kind))!!
        assertEquals(8, result.features)
        assertEquals(50, result.trees)
    }

    @Test fun baseFeatureOrderMatchesBothLegacyModels() {
        val names = audit.requiredFeatureNames(BoostMlAssetSchemaAudit.Kind.HYPO_RISK_V12)
        assertEquals(audit.BASE_FEATURES, names.take(8))
        assertEquals(audit.BASE_FEATURES,
            audit.requiredFeatureNames(BoostMlAssetSchemaAudit.Kind.MEAL_LIKELIHOOD))
    }

    @Test fun riskLagFieldsFollowSixFiveMinuteLookbackSlots() {
        val names = audit.requiredFeatureNames(BoostMlAssetSchemaAudit.Kind.HYPO_RISK_V12)
        assertEquals("cgm_mgdl_lag0", names[17])
        assertEquals("cgm_mgdl_lag5", names[22])
        assertEquals("iob_iob_lag0", names[23])
        assertEquals("sug_minDelta_lag5", names.last())
        assertEquals(names.size, names.distinct().size)
    }

    @Test fun swappedModelKindCannotPassEvenIfTreeShapesMatch() {
        assertNull(audit.inspect(BoostMlAssetSchemaAudit.Kind.MEAL_LIKELIHOOD,
            model(BoostMlAssetSchemaAudit.Kind.HYPO_RISK_V12)))
    }

    @Test fun droppedDuplicateOrReorderedFeatureNamesFailClosed() {
        val kind = BoostMlAssetSchemaAudit.Kind.HYPO_RISK_V12
        val original = model(kind)
        assertNull(audit.inspect(kind, original.copy(
            featureNames = original.featureNames.dropLast(1))))
        assertNull(audit.inspect(kind, original.copy(
            featureNames = original.featureNames.reversed())))
        assertNull(audit.inspect(kind, original.copy(
            featureNames = original.featureNames.dropLast(1) + "iob_iob")))
    }

    @Test fun missingExtraOrNonMatchingTreeCountCannotBeAccepted() {
        val kind = BoostMlAssetSchemaAudit.Kind.HYPO_RISK_V12
        val original = model(kind)
        assertNull(audit.inspect(kind, original.copy(trees = original.trees.take(99))))
        assertNull(audit.inspect(kind, original.copy(trees = emptyList())))
        assertNull(audit.inspect(kind, original.copy(trees = original.trees + leaf)))
    }

    @Test fun wellFormedSplitCountsBothBranchesBeforeGrantingSnapshotStatus() {
        val kind = BoostMlAssetSchemaAudit.Kind.MEAL_LIKELIHOOD
        val split = BoostMlTreeDiagnostics.Split(0, 110.0, leaf, leaf)
        val result = audit.inspect(kind, model(kind, split))!!
        assertEquals(150, result.traversedNodes)
        assertEquals(2, result.depthMax)
    }

    @Test fun unreachableInvalidBranchIsNotIgnoredBySchemaInspection() {
        val kind = BoostMlAssetSchemaAudit.Kind.MEAL_LIKELIHOOD
        val split = BoostMlTreeDiagnostics.Split(
            0, 100.0, leaf, BoostMlTreeDiagnostics.Leaf(Double.NaN))
        assertNull(audit.inspect(kind, model(kind, split)))
    }

    @Test fun splitWithInvalidFeatureIndexOrNonFiniteThresholdFailsClosed() {
        val kind = BoostMlAssetSchemaAudit.Kind.MEAL_LIKELIHOOD
        assertNull(audit.inspect(kind, model(kind,
            BoostMlTreeDiagnostics.Split(-1, 1.0, leaf, leaf))))
        assertNull(audit.inspect(kind, model(kind,
            BoostMlTreeDiagnostics.Split(8, 1.0, leaf, leaf))))
        assertNull(audit.inspect(kind, model(kind,
            BoostMlTreeDiagnostics.Split(2, Double.POSITIVE_INFINITY, leaf, leaf))))
    }

    @Test fun excessiveDepthRejectsMaliciousTreeInsteadOfStackOverflow() {
        val kind = BoostMlAssetSchemaAudit.Kind.MEAL_LIKELIHOOD
        var tree: BoostMlTreeDiagnostics.Node = leaf
        repeat(65) { tree = BoostMlTreeDiagnostics.Split(0, 0.5, tree, leaf) }
        assertNull(audit.inspect(kind, model(kind, tree)))
    }

    @Test fun schemaInspectionReturnsMetadataNotProbabilityOrDosing() {
        val kind = BoostMlAssetSchemaAudit.Kind.HYPO_RISK_V12
        val result = audit.inspect(kind, model(kind))!!
        assertTrue(result.depthMax > 0)
        assertEquals(kind, result.kind)
    }
}
