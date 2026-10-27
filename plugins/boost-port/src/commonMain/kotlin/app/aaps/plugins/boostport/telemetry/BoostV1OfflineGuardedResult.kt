package app.aaps.plugins.boostport.telemetry

import app.aaps.plugins.aps.openAPSBoost.BoostV1CgmGateShadow

/**
 * Offline diagnostic gate at the V1 result boundary.
 * No pump or AndroidAPS result object can be produced by this code.
 */
internal object BoostV1OfflineGuardedResult {
    enum class Finding { INVALID_CONTEXT, SENSOR_REJECTED, FORECAST_REJECTED, DIAGNOSTIC_ONLY }
    data class Input(
        val clockMs: Long,
        val cgm: BoostV1CgmGateShadow.Input,
        val result: BoostV1OfflineResultEnvelope.Input
    )
    data class Report(
        val finding: Finding,
        val cgmFinding: BoostV1CgmGateShadow.Finding? = null,
        val result: BoostV1OfflineResultEnvelope.Report? = null,
        val canDeliverInsulin: Boolean = false
    )
    fun inspect(i: Input): Report {
        if (i.clockMs <= 0L || i.cgm.currentTimeMs != i.clockMs || i.result.atMs != i.clockMs)
            return Report(Finding.INVALID_CONTEXT)
        val sensor = BoostV1CgmGateShadow.inspect(i.cgm)
        if (sensor.finding != BoostV1CgmGateShadow.Finding.USABLE)
            return Report(Finding.SENSOR_REJECTED, cgmFinding = sensor.finding)
        val forecast = BoostV1OfflineResultEnvelope.inspect(i.result)
        if (forecast.finding != BoostV1OfflineResultEnvelope.Finding.DIAGNOSTIC_ONLY)
            return Report(Finding.FORECAST_REJECTED, cgmFinding = sensor.finding)
        return Report(Finding.DIAGNOSTIC_ONLY, sensor.finding, forecast)
    }
}
