package app.aaps.plugins.boostport.telemetry

/**
 * Non-actionable analogue of the result envelope that AndroidAPS normally
 * receives from determine_basal. Does not implement APSResult or RT.
 */
internal object BoostV1OfflineResultEnvelope {
    enum class Finding { INVALID_CLOCK, INVALID_FORECAST, DIAGNOSTIC_ONLY }
    data class Input(
        val atMs: Long,
        val forecast: BoostV1ForecastPipeline.Report
    )
    data class Report(
        val finding: Finding,
        val atMs: Long? = null,
        val predictedIobMgDl: List<Int> = emptyList(),
        val predictedCobMgDl: List<Int> = emptyList(),
        val predictedZtMgDl: List<Int> = emptyList(),
        val predictedUamMgDl: List<Int> = emptyList(),
        val canDeliverInsulin: Boolean = false
    )
    fun inspect(input: Input): Report {
        if (input.atMs <= 0L) return Report(Finding.INVALID_CLOCK)
        if (input.forecast.finding != BoostV1ForecastPipeline.Finding.READY)
            return Report(Finding.INVALID_FORECAST)
        val curves = input.forecast.observations ?: return Report(Finding.INVALID_FORECAST)
        return Report(
            finding = Finding.DIAGNOSTIC_ONLY,
            atMs = input.atMs,
            predictedIobMgDl = curves.iob.toList(),
            predictedCobMgDl = curves.cob.toList(),
            predictedZtMgDl = curves.zt.toList(),
            predictedUamMgDl = curves.uam.toList()
        )
    }
}
