package app.aaps.plugins.boostport.telemetry

/**
 * Read-only consistency check of a caller-parsed V6 ring against the model's
 * declared feature order. Does not load a model, run inference, access live
 * sensors, write persistence, register APS or control insulin delivery.
 */
internal object BoostMlRestoredFeatureParityAudit {
    enum class Finding {
        INVALID_FEATURE_SCHEMA, INVALID_HISTORY, INCOMPLETE_HISTORY,
        INVALID_CURRENT, INVALID_LAG_GRID, VECTOR_REJECTED, VECTOR_MISMATCH, MATCH
    }

    data class Report(
        val finding: Finding,
        val restoredRows: Int,
        val firstDifference: Int?,
    )

    fun inspect(
        rows: List<Map<String, String>>,
        modelFeatureNames: List<String>,
        staticValues: Map<String, Double>,
        current: BoostMlFeatureDiagnostics.Snapshot,
    ): Report {
        fun reject(kind: Finding, count: Int = 0, at: Int? = null) =
            Report(kind, count, at)
        if (BoostMlFeatureOrderAudit.inspect(modelFeatureNames).status !=
            BoostMlFeatureOrderAudit.Status.MATCH
        ) return reject(Finding.INVALID_FEATURE_SCHEMA)
        if (!current.cgmMgdl.isFinite() || !current.iobIob.isFinite() ||
            !current.iobActivity.isFinite() || !current.eventualBg.isFinite() ||
            !current.recentSmbUnits60m.isFinite() || !current.minDelta.isFinite()
        ) return reject(Finding.INVALID_CURRENT)
        val ring = BoostMlRingSchemaAudit.decode(rows)
            ?: return reject(Finding.INVALID_HISTORY)
        if (ring.size == 0 || ring.lagged(0) != current)
            return reject(Finding.INVALID_CURRENT, ring.size)
        if (ring.size < BoostMlFeatureDiagnostics.LOOKBACK)
            return reject(Finding.INCOMPLETE_HISTORY, ring.size)
        // A full six-row ring alone does not prove five-minute spacing.
        // Reject stale or irregular lag grids before comparing vectors.
        val continuity = BoostMlLagContinuityAudit.inspect(
            ring.snapshots(), current.timestampMs
        )
        if (continuity.finding != BoostMlLagContinuityAudit.Finding.COMPLETE)
            return reject(Finding.INVALID_LAG_GRID, ring.size)
        val vector = BoostMlFeatureDiagnostics.build(
            modelFeatureNames, current, ring, staticValues
        ) ?: return reject(Finding.VECTOR_REJECTED, ring.size)
        if (vector.size != modelFeatureNames.size)
            return reject(Finding.VECTOR_MISMATCH, ring.size)
        for ((index, feature) in modelFeatureNames.withIndex()) {
            val lagMarker = feature.lastIndexOf("_lag")
            val expected = if (lagMarker > 0) {
                val name = feature.substring(0, lagMarker)
                val lag = feature.substring(lagMarker + 4).toIntOrNull()
                    ?: return reject(Finding.VECTOR_MISMATCH, ring.size, index)
                ring.lagged(lag)?.valueOf(name)
            } else {
                staticValues[feature]
            }
            // Match the original model-vector builder's finite fallback.
            val finiteExpected = expected?.takeIf { it.isFinite() } ?: 0.0
            if (vector[index] != finiteExpected)
                return reject(Finding.VECTOR_MISMATCH, ring.size, index)
        }
        return Report(Finding.MATCH, ring.size, null)
    }
}
