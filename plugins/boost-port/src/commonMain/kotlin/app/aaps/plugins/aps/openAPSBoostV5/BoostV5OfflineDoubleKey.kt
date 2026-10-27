package app.aaps.plugins.aps.openAPSBoostV5

/** Offline-only source key definitions. NOT registered in AAPS Preferences. */
internal enum class BoostV5OfflineDoubleKey(val key: String, val defaultValue: Double, val min: Double, val max: Double) {
    ApsBoostBolus("boost_bolus_cap", 2.5, 0.1, 10.0),
    ApsBoostMaxIob("boost_max_iob", 1.0, 0.1, 12.0),
    ApsBoostV5Aggression("boost_v5_aggression", 1.0, 0.7, 1.6),
    ApsBoostV5HypoCaution("boost_v5_hypo_caution", 1.0, 1.0, 2.0),
    ApsBoostCumulativeSmbCap60Min("boost_cumulative_smb_cap_60min", 10.0, 0.0, 10.0),
    ApsBoostV5ConfirmedCapU("boost_v5_confirmed_cap_u", 2.5, 0.0, 7.5),
    ApsBoostV5CommittedCapU("boost_v5_committed_cap_u", 0.5, 0.0, 2.5),
    ApsBoostV5PrimerCapU("boost_v5_primer_cap_u", 0.0, 0.0, 2.5),
}
