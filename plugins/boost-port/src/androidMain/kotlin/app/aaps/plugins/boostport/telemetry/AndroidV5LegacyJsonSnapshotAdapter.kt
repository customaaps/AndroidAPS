package app.aaps.plugins.boostport.telemetry

import org.json.JSONObject

/**
 * Android-only, disconnected parser for the exact V5StateStore JSON field names.
 * It does not touch Preferences, expose a writer, register an APS plugin, or
 * call the V1/V5 dosing engines. Corrupt persisted input fails closed.
 */
internal object AndroidV5LegacyJsonSnapshotAdapter {
    private val knownFields = listOf(
        "mealHypothesis", "mealHypothesisAge", "mealHypothesisLastAgeMs",
        "maxScoreInObserving", "maxEventualBgOffsetInObserving",
        "committedInSession", "lastCommitMs", "nonPositiveRunStartMs",
        "maxScoreAtMs", "maxOffsetAtMs", "mlMealLikelyNullStreak",
        "primerAppliedU", "primerNettingResidualU", "primerIobU", "primerIobUpdatedMs"
    )

    /**
     * An empty or malformed legacy blob is not interpreted as a new IDLE state:
     * offline replay must explicitly distinguish first install from corruption.
     */
    fun inspect(raw: String?): BoostV5StateSnapshotCodec.Snapshot? {
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(raw)
            // Reject unexpected JSON value types.
            val fields = buildMap {
                for (key in knownFields) {
                    if (!json.has(key) || json.isNull(key)) {
                        if (json.has(key)) return null
                        continue
                    }
                    val value = json.get(key)
                    // The source store writes strongly typed JSON. Stringified
                    // booleans/numbers can conceal externally modified state.
                    val validType = when (key) {
                        "mealHypothesis" -> value is String
                        "committedInSession" -> value is Boolean
                        else -> value is Number
                    }
                    if (!validType) return null
                    put(key, value.toString())
                }
            }
            // Preserve source-compatible optional fields but refuse unknown
            // required field corruption through the portable codec.
            BoostV5StateSnapshotCodec.decode(fields)
        } catch (_: Exception) {
            null
        }
    }

    /** Inert serialization for parity fixtures only; never writes storage. */
    fun serialize(snapshot: BoostV5StateSnapshotCodec.Snapshot): String? {
        val fields = BoostV5StateSnapshotCodec.encode(snapshot) ?: return null
        return JSONObject().apply {
            for ((key, value) in fields) {
                val normalized: Any = when (key) {
                    "mealHypothesis" -> value
                    "committedInSession" -> value.toBooleanStrictOrNull() ?: return null
                    "mealHypothesisAge", "mlMealLikelyNullStreak" -> value.toIntOrNull() ?: return null
                    "mealHypothesisLastAgeMs", "lastCommitMs", "nonPositiveRunStartMs",
                    "maxScoreAtMs", "maxOffsetAtMs", "primerIobUpdatedMs" -> value.toLongOrNull() ?: return null
                    else -> value.toDoubleOrNull() ?: return null
                }
                put(key, normalized)
            }
        }.toString()
    }
}
