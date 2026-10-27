package app.aaps.plugins.aps.openAPSBoost

/**
 * Pure setting-transition rules adapted from Boost/dev 3d9d471dbb and f393d02e55.
 * Called by OpenAPSBoostPlugin.applyTddSettingTransition before dosing; it does not
 * change the ordering of Autosens/ISF calculations in Dmitry.
 */
internal data class BoostTddSettingTransition(
    val adjustSens: Boolean,
    val velocityPct: Double,
    val clearedAdjustSens: Boolean,
    val zeroedVelocity: Boolean,
    val restoredVelocity: Boolean
) {
    val changed: Boolean get() = clearedAdjustSens || zeroedVelocity || restoredVelocity
}

internal fun boostTddSettingsTransition(
    useTdd: Boolean,
    adjustSens: Boolean,
    velocityPct: Double,
    switchedToV6: Boolean,
    tddJustEnabled: Boolean = false,
    velocityOnPct: Double = 100.0
): BoostTddSettingTransition {
    val clearAdjust = adjustSens && (!useTdd || switchedToV6)
    val zeroVelocity = !useTdd && velocityPct != 0.0
    val restoreVelocity = useTdd && tddJustEnabled && velocityPct != velocityOnPct
    return BoostTddSettingTransition(
        adjustSens = adjustSens && !clearAdjust,
        velocityPct = when {
            zeroVelocity -> 0.0
            restoreVelocity -> velocityOnPct
            else -> velocityPct
        },
        clearedAdjustSens = clearAdjust,
        zeroedVelocity = zeroVelocity,
        restoredVelocity = restoreVelocity
    )
}

internal fun boostTddJustEnabled(previous: String, enabledNow: Boolean): Boolean =
    enabledNow && previous == "false"

/** Detect the first V6 cycle, including an install with no recorded engine mode. */
internal fun boostSwitchedToV6(lastEngineMode: String, v6Active: Boolean): Boolean =
    v6Active && lastEngineMode != "v6"
