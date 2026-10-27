package app.aaps.plugins.aps.openAPSBoost.replay

/** Recorded local time, supplied explicitly so replay never reads the host clock. */
internal interface V1ReplayClock {
    fun hourAt(timestampMs: Long): Int
    fun minuteAt(timestampMs: Long): Int
}

/** Formatting affects diagnostic strings only; no platform formatter enters the arithmetic. */
internal interface V1ReplayPresentation {
    fun glucose(value: Double): String
    fun fixed2(value: Double): String
    fun withoutZeros(value: Double): String
}

/** Caller-owned, offline model boundary. No asset loading, background work or persistence. */
internal interface V1ReplayRiskModel {
    fun getFeatureNames(): List<String>?
    fun predict(features: DoubleArray): Double?
    fun predictHypoRisk(
        cgmMgdl: Double, iobTotal: Double, iobBasal: Double,
        bgAboveTarget: Double, directionNum: Double, hour: Int,
        iobActivity: Double, insulinReq: Double
    ): Double?
}

internal fun interface V1ReplayMealModel {
    fun predictMealLikelihood(
        cgmMgdl: Double, iobTotal: Double, iobBasal: Double,
        bgAboveTarget: Double, directionNum: Double, hour: Int,
        iobActivity: Double, insulinReq: Double
    ): Double?
}
