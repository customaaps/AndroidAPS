package app.aaps.plugins.boostport.telemetry

/**
 * Inert KMP field-schema adaptation of BoostMlFeatureBuilder's v12
 * persisted six-cycle ring. The source serializes a JSON array; this
 * isolated audit instead consumes caller-supplied immutable string maps.
 *
 * It does not parse JSON, read/write AAPS Preferences, fetch CGM/IOB,
 * run model inference, affect insulin calculations, or control a pump.
 * All fields must be present and finite: corrupted snapshots fail closed.
 */
internal object BoostMlRingSchemaAudit {
    const val MAX_ROWS = BoostMlFeatureDiagnostics.LOOKBACK

    val FIELDS = listOf(
        "ts", "cgm_mgdl", "iob_iob", "iob_activity", "sug_eventualBG",
        "recent_smb_units_60m", "sug_minDelta",
    )

    private fun valid(s: BoostMlFeatureDiagnostics.Snapshot): Boolean =
        s.timestampMs > 0L &&
            s.cgmMgdl.isFinite() && s.iobIob.isFinite() &&
            s.iobActivity.isFinite() && s.eventualBg.isFinite() &&
            s.recentSmbUnits60m.isFinite() && s.minDelta.isFinite()

    /**
     * Produces the exact original JSON field labels as ordinary map keys;
     * no JSON object, platform preferences, or persisted file is created.
     */
    fun encode(ring: BoostMlFeatureDiagnostics.Ring): List<Map<String, String>>? {
        val entries = ring.snapshots()
        if (entries.size > MAX_ROWS || entries.any { !valid(it) } ||
            entries.zipWithNext().any { (a, b) -> b.timestampMs <= a.timestampMs }
        ) return null
        return entries.map { s ->
            mapOf(
                "ts" to s.timestampMs.toString(),
                "cgm_mgdl" to s.cgmMgdl.toString(),
                "iob_iob" to s.iobIob.toString(),
                "iob_activity" to s.iobActivity.toString(),
                "sug_eventualBG" to s.eventualBg.toString(),
                "recent_smb_units_60m" to s.recentSmbUnits60m.toString(),
                "sug_minDelta" to s.minDelta.toString(),
            )
        }
    }

    /**
     * Strict read-only import. Replays the original 4m30s replacement,
     * 35-minute age expiration and six-entry maximum using the already
     * staged BoostMlFeatureDiagnostics.Ring. Invalid row = null, never
     * a partially restored model vector.
     */
    fun decode(rows: List<Map<String, String>>): BoostMlFeatureDiagnostics.Ring? {
        if (rows.size > MAX_ROWS) return null
        var result = BoostMlFeatureDiagnostics.Ring()
        var previousTs = 0L
        for (row in rows) {
            val timestamp = row["ts"]?.toLongOrNull() ?: return null
            if (timestamp <= previousTs) return null
            fun d(key: String) = row[key]?.toDoubleOrNull()?.takeIf { it.isFinite() }
            val next = BoostMlFeatureDiagnostics.Snapshot(
                timestampMs = timestamp,
                cgmMgdl = d("cgm_mgdl") ?: return null,
                iobIob = d("iob_iob") ?: return null,
                iobActivity = d("iob_activity") ?: return null,
                eventualBg = d("sug_eventualBG") ?: return null,
                recentSmbUnits60m = d("recent_smb_units_60m") ?: return null,
                minDelta = d("sug_minDelta") ?: return null,
            )
            if (!valid(next)) return null
            result = result.accept(next)
            previousTs = timestamp
        }
        return result
    }
}
