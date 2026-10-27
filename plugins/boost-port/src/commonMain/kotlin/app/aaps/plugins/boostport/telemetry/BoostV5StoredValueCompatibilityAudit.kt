package app.aaps.plugins.boostport.telemetry

/**
 * Offline inspection of archived V5 persisted DoubleKey values.
 *
 * All values are supplied by a caller. No AAPS Preferences are read/written,
 * no defaults are applied, and no insulin, APS or pump control is performed.
 * Out-of-range values are reported (never clamped).
 */
internal object BoostV5StoredValueCompatibilityAudit {
    enum class Finding { ABSENT, MATCH, NON_NUMERIC, NON_FINITE, OUT_OF_RANGE, UNKNOWN_KEY }

    data class Item(
        val storageKey: String,
        val finding: Finding,
        val numericValue: Double?,
    )

    fun inspect(stored: Map<String, String>): List<Item> {
        val known = BoostV5PreferenceStorageSchema.all().associateBy { it.storageKey }
        return stored.entries.sortedBy { it.key }.map { (key, raw) ->
            val definition = known[key]
            // Unknown keys must not be parsed as Boost configuration values.
            val parsed = if (definition == null) null else raw.toDoubleOrNull()
            val finding = when {
                definition == null -> Finding.UNKNOWN_KEY
                parsed == null -> Finding.NON_NUMERIC
                !parsed.isFinite() -> Finding.NON_FINITE
                parsed < definition.minimum || parsed > definition.maximum -> Finding.OUT_OF_RANGE
                else -> Finding.MATCH
            }
            Item(key, finding, parsed)
        }
    }

    /** A missing stored key means fallback would be used, not that a value is written. */
    fun inspectAllExpected(stored: Map<String, String>): List<Item> {
        val observed = inspect(stored)
        val entries = observed.associateBy { it.storageKey }
        return BoostV5PreferenceStorageSchema.all().map { definition ->
            entries[definition.storageKey] ?: Item(
                definition.storageKey, Finding.ABSENT, null
            )
        } + observed.filter { BoostV5PreferenceStorageSchema.byStorageKey(it.storageKey) == null }
    }
}
