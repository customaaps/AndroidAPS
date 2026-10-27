package app.aaps.plugins.boostport.telemetry

/**
 * Read-only KMP parity port of Boost/dev's BoostTddSettingTransition,
 * separated from OpenAPSBoostPlugin.applyTddSettingTransition.
 *
 * Returns a hypothetical before/after preference value pair for an OFFLINE
 * comparison only. It does not read or write AAPS Preferences, persist
 * settings, register a plugin, change Dmitry's ISF order, calculate insulin,
 * issue an SMB or communicate with a pump.
 *
 * Legacy details:
 * - disabling TDD clears adjustSens and sets velocityPct to zero;
 * - entering V6 clears adjustSens without wiping an enabled TDD velocity;
 * - a recorded false->true TDD edge restores the configured on-percentage;
 * - enabling TDD does not turn adjustSens back on.
 */
internal object BoostTddSettingTransitionAudit {
    data class Input(
        val useTdd: Boolean,
        val adjustSens: Boolean,
        val velocityPct: Double,
        val switchedToV6: Boolean,
        val tddJustEnabled: Boolean = false,
        val velocityOnPct: Double = 100.0,
    )

    data class Projection(
        val wouldAdjustSens: Boolean,
        val wouldVelocityPct: Double,
        val wouldClearAdjustSens: Boolean,
        val wouldZeroVelocity: Boolean,
        val wouldRestoreVelocity: Boolean,
    ) {
        val wouldChange: Boolean get() =
            wouldClearAdjustSens || wouldZeroVelocity || wouldRestoreVelocity
    }

    /** Only a recorded "false" to true edge is a positive TDD-enable event. */
    fun tddJustEnabled(previousRecordedValue: String, enabledNow: Boolean): Boolean =
        enabledNow && previousRecordedValue == "false"

    /** First V6 cycle also includes a fresh install with no prior engine. */
    fun switchedToV6(lastEngineMode: String, v6Active: Boolean): Boolean =
        v6Active && lastEngineMode != "v6"

    /**
     * Does not apply settings. Invalid, non-finite percentages abstain;
     * no guessed defaults are written to a live preference store.
     */
    fun inspect(input: Input): Projection? {
        if (!input.velocityPct.isFinite() || input.velocityPct < 0.0 ||
            !input.velocityOnPct.isFinite() || input.velocityOnPct < 0.0
        ) return null
        val clearAdjust = input.adjustSens && (!input.useTdd || input.switchedToV6)
        val zeroVelocity = !input.useTdd && input.velocityPct != 0.0
        val restoreVelocity = input.useTdd && input.tddJustEnabled &&
            input.velocityPct != input.velocityOnPct
        val nextVelocity = when {
            zeroVelocity -> 0.0
            restoreVelocity -> input.velocityOnPct
            else -> input.velocityPct
        }
        return Projection(
            wouldAdjustSens = input.adjustSens && !clearAdjust,
            wouldVelocityPct = nextVelocity,
            wouldClearAdjustSens = clearAdjust,
            wouldZeroVelocity = zeroVelocity,
            wouldRestoreVelocity = restoreVelocity,
        )
    }
}
