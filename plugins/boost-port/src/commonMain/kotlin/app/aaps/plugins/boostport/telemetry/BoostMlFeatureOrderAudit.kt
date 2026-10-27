package app.aaps.plugins.boostport.telemetry

/**
 * Read-only schema check for the legacy Boost v12 53-feature model.
 * Verifies names and order only. Does not run inference, access CGM/IOB,
 * persist model state, register APS or issue insulin/pump commands.
 */
internal object BoostMlFeatureOrderAudit {
    val BASE = listOf(
        "cgm_mgdl", "iob_iob", "iob_basaliob", "bg_above_target",
        "direction_num", "hour", "iob_activity", "sug_insulinReq",
        "sug_COB", "sug_eventualBG", "sug_expectedDelta",
        "sug_minDelta", "sug_TDD", "iob_bolusiob",
        "iob_netbasalinsulin", "recent_smb_units_60m",
        "time_since_last_smb_min"
    )
    val EXPECTED: List<String> = BASE +
        BoostMlFeatureDiagnostics.LOOKBACK_FEATURES.flatMap { feature ->
            (0 until BoostMlFeatureDiagnostics.LOOKBACK).map { lag ->
                "${feature}_lag${lag}"
            }
        }

    enum class Status { MATCH, COUNT_MISMATCH, DUPLICATE, WRONG_ORDER }

    data class Result(
        val status: Status,
        val expectedCount: Int,
        val actualCount: Int,
        val firstMismatch: Int?,
    )

    fun inspect(names: List<String>): Result {
        val status = when {
            names.size != EXPECTED.size -> Status.COUNT_MISMATCH
            names.distinct().size != names.size -> Status.DUPLICATE
            names != EXPECTED -> Status.WRONG_ORDER
            else -> Status.MATCH
        }
        val mismatch = if (status == Status.MATCH) null else
            (0 until minOf(names.size, EXPECTED.size)).firstOrNull {
                names[it] != EXPECTED[it]
            } ?: minOf(names.size, EXPECTED.size)
        return Result(status, EXPECTED.size, names.size, mismatch)
    }
}
