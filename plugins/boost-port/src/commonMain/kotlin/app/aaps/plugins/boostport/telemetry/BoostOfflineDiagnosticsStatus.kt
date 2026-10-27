package app.aaps.plugins.boostport.telemetry

/** Human-readable, no-dose status messages suitable for a read-only screen. */
internal enum class BoostOfflineDiagnosticsStatus(val displayText: String) {
    NO_DATA("No validated offline replay is available."),
    INVALID("Offline replay failed validation. No results are displayed."),
    REVIEW("Offline replay flagged a glucose protection condition for review."),
    AVAILABLE("Offline replay diagnostics are available; no therapy is enabled.");

    companion object {
        fun fromWire(value: String?): BoostOfflineDiagnosticsStatus =
            entries.firstOrNull { it.name == value } ?: NO_DATA
    }
}
