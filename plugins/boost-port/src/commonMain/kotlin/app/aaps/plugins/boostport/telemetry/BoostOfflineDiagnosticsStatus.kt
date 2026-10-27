package app.aaps.plugins.boostport.telemetry

/** Semantic, no-dose status; presentation resolves text through the current locale. */
internal enum class BoostOfflineDiagnosticsStatus {
    NO_DATA,
    INVALID,
    REVIEW,
    AVAILABLE;

    companion object {
        fun fromWire(value: String?): BoostOfflineDiagnosticsStatus =
            entries.firstOrNull { it.name == value } ?: NO_DATA
    }
}
