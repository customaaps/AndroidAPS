package app.aaps.plugins.boostport.telemetry

/**
 * Read-only inventory of the missing V5 DoubleKey declarations in Dmitry.
 * Caller supplies symbolic declarations from a catalog; this does NOT add
 * keys, migrate settings, access Preferences or alter any insulin calculation.
 */
internal object BoostV5PreferenceCatalogPresenceAudit {
    enum class Finding { ALL_PRESENT, PARTIALLY_MISSING, ALL_MISSING, DUPLICATE_DECLARATIONS }

    data class Report(
        val finding: Finding,
        val present: Set<String>,
        val missing: Set<String>,
    )

    fun inspect(targetDoubleKeyNames: List<String>): Report {
        val expected = BoostV5ConfigResolutionAudit.Knob.entries.mapNotNull {
            BoostV5PreferenceKeyMapAudit.symbolicName(it)
        }.toSet()
        val actual = targetDoubleKeyNames.toSet()
        val present = expected intersect actual
        val missing = expected - actual
        val duplicates = targetDoubleKeyNames.size != actual.size
        return Report(
            finding = when {
                duplicates -> Finding.DUPLICATE_DECLARATIONS
                missing.isEmpty() -> Finding.ALL_PRESENT
                present.isEmpty() -> Finding.ALL_MISSING
                else -> Finding.PARTIALLY_MISSING
            },
            present = present,
            missing = missing,
        )
    }
}
