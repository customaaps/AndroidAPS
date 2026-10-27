package app.aaps.plugins.aps.openAPSBoost

/**
 * Groups three independent source-derived V1 diagnostics into one immutable,
 * caller-fed offline report. No APS, profile mutation, dosing or pump access.
 */
internal object BoostV1OfflineCycleMath {
    enum class Finding { READY, INVALID_INPUT }
    data class Input(
        val bgMgDl: Double,
        val targetBg: Double,
        val eventualBg: Double,
        val bgi: Double,
        val hourOfDay: Int
    )
    data class Report(
        val finding: Finding,
        val reboundScale: Double? = null,
        val expectedDelta: Double? = null,
        val circadianFactor: Double? = null,
        val canDeliverInsulin: Boolean = false
    )

    fun inspect(input: Input): Report {
        val rebound = BoostV1PostRescueShadow.inspect(input.bgMgDl)
        val expected = BoostV1ExpectedDeltaShadow.inspect(input.targetBg, input.eventualBg, input.bgi)
        val circadian = BoostV1CircadianShadow.inspect(input.hourOfDay)
        if (rebound.finding != BoostV1PostRescueShadow.Finding.READY ||
            expected.finding != BoostV1ExpectedDeltaShadow.Finding.READY ||
            circadian.finding != BoostV1CircadianShadow.Finding.READY
        ) return Report(Finding.INVALID_INPUT)
        return Report(
            finding = Finding.READY,
            reboundScale = rebound.reboundScale,
            expectedDelta = expected.expectedDelta,
            circadianFactor = circadian.factor
        )
    }
}
