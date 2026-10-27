package app.aaps.plugins.boostport.telemetry

/**
 * UI-neutral display boundary for the standalone Boost port.
 *
 * No AndroidAPS APSResult, RT, APS plugin, pump or treatment interface is
 * imported or constructed. The application may render this immutable status
 * but may not use it as a treatment recommendation.
 */
internal object BoostPortReadOnlyDisplay {
    enum class Status { NO_DATA, INVALID_DIAGNOSTICS, REVIEW_REQUIRED, DIAGNOSTICS_AVAILABLE }
    data class View(
        val status: Status,
        val cycleCount: Int,
        val explanation: String,
        val isTreatmentRecommendation: Boolean = false,
        val canDeliverInsulin: Boolean = false
    )
    fun project(report: BoostV1V5OfflineIntegration.Report?): View {
        if (report == null) return View(Status.NO_DATA, 0, "No offline replay is available")
        if (report.finding != BoostV1V5OfflineIntegration.Finding.READY ||
            report.reviewedCycles <= 0)
            return View(Status.INVALID_DIAGNOSTICS, 0, "Boost offline replay failed validation")
        return if (report.riskReviewRequired)
            View(Status.REVIEW_REQUIRED, report.reviewedCycles,
                "Offline historical glucose protection flag; manual review required")
        else
            View(Status.DIAGNOSTICS_AVAILABLE, report.reviewedCycles,
                "Validated offline replay; Boost is not controlling insulin")
    }
}
