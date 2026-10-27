package app.aaps.plugins.boostport.telemetry

/**
 * Pure, disabled staging seam between the two archived Boost V5 profile
 * projections. Never constructs OapsProfileBoost, invokes APS or calculates
 * insulin. It cannot access the pump or Android Preferences.
 */
internal object BoostV5OfflineProfileAssembly {
    enum class Finding { VALID, BASE_INPUT_INVALID, ADDITIONAL_INPUT_INVALID }

    data class Snapshot(
        val base: BoostV5ProfileInputBoundary.OfflineSnapshot,
        val additional: BoostV5ProfileAdditionalInputs.OfflineSnapshot,
    )
    data class Report(
        val finding: Finding,
        val snapshot: Snapshot?,
        val baseFinding: BoostV5ProfileInputBoundary.Finding,
        val additionalFinding: BoostV5ProfileAdditionalInputs.Finding,
    )

    fun inspect(
        base: BoostV5ProfileInputBoundary.Source,
        additional: BoostV5ProfileAdditionalInputs.Source,
    ): Report {
        val first = BoostV5ProfileInputBoundary.inspect(base)
        val second = BoostV5ProfileAdditionalInputs.inspect(additional)
        val finding = when {
            first.snapshot == null -> Finding.BASE_INPUT_INVALID
            second.snapshot == null -> Finding.ADDITIONAL_INPUT_INVALID
            else -> Finding.VALID
        }
        val snapshot = if (first.snapshot != null && second.snapshot != null) {
            Snapshot(first.snapshot, second.snapshot)
        } else null
        return Report(finding, snapshot, first.finding, second.finding)
    }
}
