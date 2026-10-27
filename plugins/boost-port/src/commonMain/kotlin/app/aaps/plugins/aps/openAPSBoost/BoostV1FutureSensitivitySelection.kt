package app.aaps.plugins.aps.openAPSBoost

import kotlin.math.max

/**
 * Offline selection of the glucose argument for the legacy V1 future-ISF
 * calculation (DetermineBasalBoost.kt around 875–916).
 * This does not calculate or authorize a dose. Actual ISF is evaluated by
 * the already ported BoostV1IsfShadowCalculation with capEnabled=false.
 */
internal object BoostV1FutureSensitivitySelection {
    enum class Path { COB_ACCELERATION, RAPID_ACCELERATION, FLAT_HIGH, RISING, FALLING }
    data class Input(
        val boostActive: Boolean,
        val mealCob: Double,
        val deltaAcceleration: Double,
        val delta: Double,
        val shortAvgDelta: Double,
        val longAvgDelta: Double,
        val currentBg: Double,
        val sensitivityBg: Double,
        val eventualBg: Double,
        val minPredictedBg: Double,
        val dynamicIsfBgCap: Double
    )
    data class Result(
        val path: Path,
        val glucoseForIsf: Double,
        val valid: Boolean = true,
        val canDeliverInsulin: Boolean = false
    )
    fun select(i: Input): Result? {
        if (listOf(i.mealCob, i.deltaAcceleration, i.delta, i.shortAvgDelta,
                i.longAvgDelta, i.currentBg, i.sensitivityBg, i.eventualBg,
                i.minPredictedBg, i.dynamicIsfBgCap).any { !it.isFinite() } ||
            i.currentBg <= 0 || i.sensitivityBg <= 0 ||
            i.dynamicIsfBgCap <= 0 || i.mealCob < 0
        ) return null
        val futureBg = if (i.eventualBg > i.dynamicIsfBgCap)
            i.dynamicIsfBgCap + (i.eventualBg - i.dynamicIsfBgCap) / 2.0
        else i.eventualBg
        val path = when {
            i.boostActive && i.mealCob > 0 && i.deltaAcceleration > 0 ->
                Path.COB_ACCELERATION
            i.boostActive && i.delta > 4 && i.deltaAcceleration > 10 &&
                i.currentBg < 180 && i.eventualBg > i.currentBg ->
                Path.RAPID_ACCELERATION
            i.currentBg > 180 &&
                i.delta > -2 && i.delta < 2 &&
                i.shortAvgDelta > -2 && i.shortAvgDelta < 2 &&
                i.longAvgDelta > -2 && i.longAvgDelta < 2 ->
                Path.FLAT_HIGH
            (i.delta > 0 && i.deltaAcceleration > 1) ||
                i.eventualBg > i.currentBg -> Path.RISING
            else -> Path.FALLING
        }
        val bg = when (path) {
            Path.COB_ACCELERATION -> futureBg * 0.75 + i.sensitivityBg * 0.25
            Path.RAPID_ACCELERATION -> futureBg * 0.5 + i.sensitivityBg * 0.5
            Path.FLAT_HIGH -> i.minPredictedBg * 0.25 + i.sensitivityBg * 0.75
            Path.RISING -> i.sensitivityBg
            Path.FALLING -> max(i.minPredictedBg, 1.0)
        }
        return bg.takeIf { it.isFinite() && it > 0.0 }?.let { Result(path, it) }
    }
}
