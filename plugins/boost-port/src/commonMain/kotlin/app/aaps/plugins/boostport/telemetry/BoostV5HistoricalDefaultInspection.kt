package app.aaps.plugins.boostport.telemetry

/**
 * Offline historical-default classification for the archived Boost V5 schema.
 * A historical default must not be mistaken for deliberate user tuning.
 * This never reads/writes Preferences or changes an APS setting.
 */
internal object BoostV5HistoricalDefaultInspection {
    enum class Finding { ABSENT, FACTORY_CURRENT, FACTORY_HISTORICAL, USER_TUNED, INVALID }
    data class Item(val storageKey: String, val finding: Finding)

    private val previous = mapOf(
        "boost_v5_confirmed_cap_u" to listOf(1.0),
        "boost_v5_committed_cap_u" to listOf(0.25),
        "boost_cumulative_smb_cap_60min" to listOf(1.5, 6.0),
    )
    private const val EPS = 1e-4

    fun inspect(stored: Map<String, String>): List<Item> =
        BoostV5PreferenceStorageSchema.all().map { definition ->
            val raw = stored[definition.storageKey]
            val value = raw?.toDoubleOrNull()
            val finding = when {
                raw == null -> Finding.ABSENT
                value == null || !value.isFinite() -> Finding.INVALID
                // Historical defaults can fall outside a newer release's
                // supported range. Recognize them before validating whether
                // a genuinely custom value fits the current limits.
                kotlin.math.abs(value - definition.factoryDefault) <= EPS -> Finding.FACTORY_CURRENT
                previous[definition.storageKey].orEmpty().any {
                    kotlin.math.abs(value - it) <= EPS
                } -> Finding.FACTORY_HISTORICAL
                value < definition.minimum || value > definition.maximum -> Finding.INVALID
                else -> Finding.USER_TUNED
            }
            Item(definition.storageKey, finding)
        }
}
