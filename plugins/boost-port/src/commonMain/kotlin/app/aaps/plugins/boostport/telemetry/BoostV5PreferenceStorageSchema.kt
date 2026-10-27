package app.aaps.plugins.boostport.telemetry

/**
 * Inert source-compatibility inventory from the archived Boost V6 DoubleKey.
 * Never registers a DoubleKey, reads Preferences, validates a dose, or changes
 * the active APS algorithm. Values are historical schema metadata ONLY.
 */
internal object BoostV5PreferenceStorageSchema {
    data class Definition(
        val symbolicName: String,
        val storageKey: String,
        val factoryDefault: Double,
        val minimum: Double,
        val maximum: Double,
        val hiddenBySimpleMode: Boolean = true,
    )

    private val definitions = listOf(
        Definition("ApsBoostV5Aggression", "boost_v5_aggression", 1.0, 0.7, 1.6),
        Definition("ApsBoostV5HypoCaution", "boost_v5_hypo_caution", 1.0, 1.0, 2.0),
        Definition("ApsBoostV5ConfirmedCapU", "boost_v5_confirmed_cap_u", 2.5, 0.0, 7.5),
        Definition("ApsBoostV5CommittedCapU", "boost_v5_committed_cap_u", 0.5, 0.0, 2.5),
        Definition("ApsBoostCumulativeSmbCap60Min", "boost_cumulative_smb_cap_60min", 10.0, 0.0, 10.0),
        Definition("ApsBoostMaxIob", "boost_max_iob", 1.0, 0.1, 12.0),
        Definition("ApsBoostBolus", "boost_bolus_cap", 2.5, 0.1, 10.0),
        Definition("ApsBoostV5PrimerCapU", "boost_v5_primer_cap_u", 0.0, 0.0, 2.5),
    )

    /** Caller receives immutable metadata only; none of these are live keys. */
    fun all(): List<Definition> = definitions.toList()

    fun bySymbolicName(symbolicName: String): Definition? =
        definitions.firstOrNull { it.symbolicName == symbolicName }

    fun byStorageKey(storageKey: String): Definition? =
        definitions.firstOrNull { it.storageKey == storageKey }

    /** Reject malformed metadata or duplicate identifiers before comparison. */
    fun isConsistent(): Boolean =
        definitions.size == 8 &&
            definitions.map { it.symbolicName }.toSet().size == definitions.size &&
            definitions.map { it.storageKey }.toSet().size == definitions.size &&
            definitions.all {
                it.minimum.isFinite() && it.maximum.isFinite() &&
                    it.factoryDefault.isFinite() &&
                    it.minimum <= it.factoryDefault &&
                    it.factoryDefault <= it.maximum
            }
}
