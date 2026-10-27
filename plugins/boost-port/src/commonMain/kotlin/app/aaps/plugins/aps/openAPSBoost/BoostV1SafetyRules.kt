package app.aaps.plugins.aps.openAPSBoost

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.plugins.aps.openAPSBoost.replay.V1ReplayProfile

// Keep the source's object-form calls at the monolithic engine seam. Wider
// primitive calls previously exceeded ART's register budget in the source.
internal fun boostSmbWithinMaxIob(microBolus: Double, profile: V1ReplayProfile, iob: IobTotal): Double =
    boostSmbWithinMaxIob(microBolus, profile.max_iob, iob.iob, profile.bolus_increment)

internal fun boostG3HoldConditionsMet(meal: MealData, glucose: GlucoseStatus): Boolean =
    boostG3HoldConditionsMet(meal.mealCOB, glucose.delta, glucose.shortAvgDelta)

/**
 * Pure Boost V1 safety helpers, isolated from all live APS/pump control.
 * Migrated from the SHA-verified V6 legacy snapshot.
 */
internal fun boostPercentScaleReduction(bg: Double, slope: Double): Double =
    if (bg > 180.0 && slope > 0.0) 0.0 else (abs(bg - 180.0) / 72.0) * slope

internal fun boostSmbWithinMaxIob(microBolus: Double, maxIob: Double, iob: Double, bolusIncrement: Double): Double {
    val headroom = maxIob - iob
    if (!(headroom > 0.0)) return 0.0
    if (microBolus <= headroom) return microBolus
    if (!(bolusIncrement > 0.0)) return min(microBolus, headroom)
    val steps = 1.0 / bolusIncrement
    return floor(headroom * steps + 1e-9) / steps
}

internal fun boostG3HoldConditionsMet(mealCob: Double, delta: Double, shortAvgDelta: Double): Boolean =
    mealCob < 1.0 && delta >= 5.0 && shortAvgDelta >= 3.0

internal fun boostSpikeOverrideAllowed(boostActive: Boolean, tier: String?, inPostRescueWindow: Boolean, mlTierDowngrade: Boolean): Boolean =
    boostActive && tier == "REGULAR_OREF1" && !inPostRescueWindow && !mlTierDowngrade
