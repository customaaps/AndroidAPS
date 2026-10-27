package app.aaps.plugins.boostport.telemetry

/**
 * Disconnected staging seam for the original DetermineBasalBoostV5
 * V5Inputs + V5PersistedState handoff.
 *
 * The original decide() is NEVER called. This object has no APSResult,
 * Preferences, pump, dosing callback or injectable live implementation.
 * A READY result means "structural inputs inspectable offline" only.
 */
internal object BoostV5DisabledCycleEntry {
    enum class Finding {
        OFFLINE_INPUTS_READY,
        INVALID_INPUTS,
        INVALID_PERSISTED_SCHEMA,
        INVALID_PERSISTED_STATE,
        FUTURE_STATE_CLOCK,
        INCONSISTENT_CYCLE_CLOCK,
    }

    data class Report(
        val finding: Finding,
        val inputFinding: BoostV5OrchestratorInputEnvelope.Finding,
        val stateShapeFinding: BoostV5PersistedStateShapeAudit.Finding?,
        val futureStateFields: Set<String> = emptySet(),
        val canInvokeDecide: Boolean = false,
        val canDeliverInsulin: Boolean = false,
    )

    /**
     * Caller-supplied snapshot fields, never loaded from real Preferences.
     * The original 0L clock signifies "unknown", which is unsuitable for
     * validating stored state chronology against a particular cycle.
     */
    fun inspect(
        input: BoostV5OrchestratorInputEnvelope.Cycle,
        persistedFields: Map<String, String>,
    ): Report {
        val inputResult = BoostV5OrchestratorInputEnvelope.inspect(input)
        if (inputResult.finding != BoostV5OrchestratorInputEnvelope.Finding.STRUCTURALLY_VALID) {
            return Report(Finding.INVALID_INPUTS, inputResult.finding, null)
        }
        if (input.nowMs == 0L) {
            return Report(Finding.INCONSISTENT_CYCLE_CLOCK, inputResult.finding, null)
        }
        val shape = BoostV5PersistedStateShapeAudit.inspect(persistedFields)
        val supported = shape.finding == BoostV5PersistedStateShapeAudit.Finding.CANONICAL ||
            shape.finding == BoostV5PersistedStateShapeAudit.Finding.LEGACY_DEFAULTS
        if (!supported) {
            return Report(Finding.INVALID_PERSISTED_SCHEMA, inputResult.finding, shape.finding)
        }
        val snapshot = BoostV5StateSnapshotCodec.decode(persistedFields)
            ?: return Report(Finding.INVALID_PERSISTED_STATE, inputResult.finding, shape.finding)
        val clock = BoostV5OfflineStateClockAudit.inspect(input.nowMs, snapshot)
        if (clock.finding != BoostV5OfflineStateClockAudit.Finding.CONSISTENT) {
            return Report(
                if (clock.finding == BoostV5OfflineStateClockAudit.Finding.FUTURE_STATE_CLOCK)
                    Finding.FUTURE_STATE_CLOCK else Finding.INVALID_PERSISTED_STATE,
                inputResult.finding, shape.finding, clock.futureFields,
            )
        }
        return Report(Finding.OFFLINE_INPUTS_READY, inputResult.finding, shape.finding)
    }
}
