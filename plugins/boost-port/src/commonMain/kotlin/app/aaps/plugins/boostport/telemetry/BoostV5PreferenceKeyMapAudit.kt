package app.aaps.plugins.boostport.telemetry

/**
 * Passive mapping of the eight BoostV5AutoConfigApply managed DoubleKey names.
 * String identifiers are for offline compatibility inspection only. No
 * Preferences are accessed or written and this is not a dosing adapter.
 */
internal object BoostV5PreferenceKeyMapAudit {
    private val names = linkedMapOf(
        BoostV5ConfigResolutionAudit.Knob.AGGRESSION to "ApsBoostV5Aggression",
        BoostV5ConfigResolutionAudit.Knob.HYPO_CAUTION to "ApsBoostV5HypoCaution",
        BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP to "ApsBoostV5ConfirmedCapU",
        BoostV5ConfigResolutionAudit.Knob.COMMITTED_CAP to "ApsBoostV5CommittedCapU",
        BoostV5ConfigResolutionAudit.Knob.CUMULATIVE_SMB_CAP to "ApsBoostCumulativeSmbCap60Min",
        BoostV5ConfigResolutionAudit.Knob.MAX_IOB to "ApsBoostMaxIob",
        BoostV5ConfigResolutionAudit.Knob.BOLUS_CAP to "ApsBoostBolus",
        BoostV5ConfigResolutionAudit.Knob.PRIMER_CAP to "ApsBoostV5PrimerCapU",
    )

    enum class Finding { MATCH, MISSING, UNKNOWN, DUPLICATE }

    data class Report(
        val finding: Finding,
        val expectedKeys: Set<String>,
        val missingKeys: Set<String>,
        val unknownKeys: Set<String>,
    )

    /**
     * Compare names copied from a target key catalogue against the source
     * symbolic DoubleKey names. Does not assume their serialized key strings.
     */
    fun inspect(symbolicNames: List<String>): Report {
        val expected = names.values.toSet()
        val actual = symbolicNames.toSet()
        val missing = expected - actual
        val unknown = actual - expected
        val finding = when {
            symbolicNames.size != actual.size -> Finding.DUPLICATE
            missing.isNotEmpty() -> Finding.MISSING
            unknown.isNotEmpty() -> Finding.UNKNOWN
            else -> Finding.MATCH
        }
        return Report(finding, expected, missing, unknown)
    }

    fun symbolicName(knob: BoostV5ConfigResolutionAudit.Knob): String? = names[knob]
}
