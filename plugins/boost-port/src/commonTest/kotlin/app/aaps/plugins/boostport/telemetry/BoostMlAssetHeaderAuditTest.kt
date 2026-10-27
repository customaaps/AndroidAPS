package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoostMlAssetHeaderAuditTest {
    private val audit = BoostMlAssetHeaderAudit
    private val riskKind = BoostMlAssetSchemaAudit.Kind.HYPO_RISK_V12
    private val mealKind = BoostMlAssetSchemaAudit.Kind.MEAL_LIKELIHOOD

    private val risk = BoostMlAssetHeaderAudit.Header(100, 53, "binary", false)
    private val meal = BoostMlAssetHeaderAudit.Header(50, 8, "binary", false)

    @Test fun bundledRiskModelHeaderIsAccepted() {
        val check = audit.inspect(riskKind, risk)!!
        assertEquals(riskKind, check.kind)
        assertEquals(53, check.declaredFeatures)
        assertEquals(100, check.declaredTrees)
    }

    @Test fun bundledMealModelHeaderIsAccepted() {
        val check = audit.inspect(mealKind, meal)!!
        assertEquals(mealKind, check.kind)
        assertEquals(8, check.declaredFeatures)
        assertEquals(50, check.declaredTrees)
    }

    @Test fun riskModelCannotBeSubstitutedForMealModel() {
        assertNull(audit.inspect(mealKind, risk))
        assertNull(audit.inspect(riskKind, meal))
    }

    @Test fun wrongModelObjectiveIsRejectedEvenWhenDimensionsMatch() {
        assertNull(audit.inspect(riskKind, risk.copy(objective = "regression")))
        assertNull(audit.inspect(riskKind, risk.copy(objective = "multiclass")))
        assertNull(audit.inspect(mealKind, meal.copy(objective = "")))
    }

    @Test fun averageOutputChangeRejectsPreviouslyKnownModel() {
        assertNull(audit.inspect(riskKind, risk.copy(averageOutput = true)))
        assertNull(audit.inspect(mealKind, meal.copy(averageOutput = true)))
    }

    @Test fun mismatchedFeatureDimensionFailsClosed() {
        assertNull(audit.inspect(riskKind, risk.copy(declaredFeatures = 8)))
        assertNull(audit.inspect(mealKind, meal.copy(declaredFeatures = 53)))
        assertNull(audit.inspect(mealKind, meal.copy(declaredFeatures = 0)))
    }

    @Test fun droppedOrExtraTreesFailClosed() {
        assertNull(audit.inspect(riskKind, risk.copy(declaredTrees = 99)))
        assertNull(audit.inspect(riskKind, risk.copy(declaredTrees = 101)))
        assertNull(audit.inspect(mealKind, meal.copy(declaredTrees = 0)))
    }

    @Test fun headerCheckDoesNotContainModelOutputsOrDoses() {
        val check = audit.inspect(riskKind, risk)!!
        assertEquals("binary", check.objective)
        assertEquals(risk.declaredTrees, check.declaredTrees)
    }
}
