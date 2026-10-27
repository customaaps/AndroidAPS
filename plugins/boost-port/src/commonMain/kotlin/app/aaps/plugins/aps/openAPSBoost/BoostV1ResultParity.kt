package app.aaps.plugins.aps.openAPSBoost

/**
 * Offline parity check for outputs captured from two independent executions
 * using exactly the same input snapshot. Does not run either algorithm and
 * cannot turn a comparison into a pump command.
 */
internal object BoostV1ResultParity {
    enum class Field {
        ALGORITHM, TEMP_REQUEST, TEMP_RATE, TEMP_DURATION, SMB_UNITS, TARGET_BG
    }
    data class Output(
        val algorithm: String,
        val tempRequested: Boolean,
        val rate: Double?,
        val durationMinutes: Int?,
        val smbUnits: Double?,
        val targetBg: Double?
    )
    data class Report(
        val comparable: Boolean,
        val mismatches: Set<Field>,
        val equivalent: Boolean = comparable && mismatches.isEmpty(),
        val canDeliverInsulin: Boolean = false
    )

    /**
     * Rates and SMB units use strict numeric parity, not a permissive dosing
     * tolerance. A tolerance can hide clinically important differences.
     */
    fun compare(original: Output, candidate: Output): Report {
        val valid = listOfNotNull(
            original.rate, candidate.rate,
            original.smbUnits, candidate.smbUnits,
            original.targetBg, candidate.targetBg
        ).all { it.isFinite() } &&
            listOf(original, candidate).all {
                (!it.tempRequested || (it.rate != null && it.durationMinutes != null)) &&
                    (it.durationMinutes == null || it.durationMinutes >= 0) &&
                    (it.rate == null || it.rate >= 0.0) &&
                    (it.smbUnits == null || it.smbUnits >= 0.0)
            }
        if (!valid) return Report(false, emptySet())
        val different = buildSet {
            if (original.algorithm != candidate.algorithm) add(Field.ALGORITHM)
            if (original.tempRequested != candidate.tempRequested) add(Field.TEMP_REQUEST)
            if (original.tempRequested || candidate.tempRequested) {
                if (original.rate != candidate.rate) add(Field.TEMP_RATE)
                if (original.durationMinutes != candidate.durationMinutes) add(Field.TEMP_DURATION)
            }
            if (original.smbUnits != candidate.smbUnits) add(Field.SMB_UNITS)
            if (original.targetBg != candidate.targetBg) add(Field.TARGET_BG)
        }
        return Report(true, different)
    }
}
