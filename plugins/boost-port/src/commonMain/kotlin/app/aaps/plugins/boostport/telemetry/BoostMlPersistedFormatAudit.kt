package app.aaps.plugins.boostport.telemetry

/**
 * Read-only compatibility inspection of the original BoostMlFeatureBuilder
 * JSON-array row layout, represented here as caller-parsed field maps.
 * Does not parse JSON or access Preferences, CGM, APS, insulin or pumps.
 *
 * Unlike the legacy deserializer this reports invalid rows explicitly instead
 * of replacing their fields with zero. No restored data is handed to a model.
 */
internal object BoostMlPersistedFormatAudit {
    enum class Finding {
        EMPTY, TOO_MANY_ROWS, MISSING_FIELD, INVALID_VALUE,
        INVALID_TIMESTAMP, OUT_OF_ORDER, NORMALIZED_ON_RESTORE, MATCH
    }

    data class Report(
        val finding: Finding,
        val inputCount: Int,
        val restoredCount: Int,
        val offendingRow: Int?,
    )

    fun inspect(rows: List<Map<String, String>>): Report {
        fun result(kind: Finding, row: Int? = null, restored: Int = 0) =
            Report(kind, rows.size, restored, row)

        if (rows.isEmpty()) return result(Finding.EMPTY)
        if (rows.size > BoostMlRingSchemaAudit.MAX_ROWS)
            return result(Finding.TOO_MANY_ROWS)
        var previous = 0L
        for ((index, row) in rows.withIndex()) {
            if (!row.keys.containsAll(BoostMlRingSchemaAudit.FIELDS))
                return result(Finding.MISSING_FIELD, index)
            val ts = row["ts"]?.toLongOrNull()
                ?: return result(Finding.INVALID_TIMESTAMP, index)
            if (ts <= 0L) return result(Finding.INVALID_TIMESTAMP, index)
            if (ts <= previous) return result(Finding.OUT_OF_ORDER, index)
            previous = ts
            if (BoostMlRingSchemaAudit.FIELDS.drop(1).any { field ->
                    row[field]?.toDoubleOrNull()?.isFinite() != true
                }) return result(Finding.INVALID_VALUE, index)
        }
        val ring = BoostMlRingSchemaAudit.decode(rows)
            ?: return result(Finding.INVALID_VALUE)
        val canonical = BoostMlRingSchemaAudit.encode(ring)
            ?: return result(Finding.INVALID_VALUE)
        // Legacy sub-4m30s updates replace the most recent slot and gaps
        // discard stale rows; this is observable rather than silent.
        if (canonical.size != rows.size ||
            canonical.indices.any { i ->
                BoostMlRingSchemaAudit.FIELDS.any { field ->
                    canonical[i][field] !=
                        (if (field == "ts") rows[i][field]
                         else rows[i][field]?.toDoubleOrNull()?.toString())
                }
            }) return result(Finding.NORMALIZED_ON_RESTORE, restored = canonical.size)
        return result(Finding.MATCH, restored = ring.size)
    }
}
