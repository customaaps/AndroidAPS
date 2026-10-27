package app.aaps.plugins.aps.openAPSBoost

/**
 * Offline bridge between the V1 CGM/temp-basal branch reports and the eventual
 * AndroidAPS APSResult adapter. This is intentionally not an APSResult: the
 * Android target must explicitly decide how to interpret these diagnostics.
 *
 * No DI registration, pump command, bolus amount, or basal instruction leaves
 * this KMP component.
 */
internal object BoostV1ResultBridge {
    enum class Disposition {
        INPUT_REJECTED,
        CGM_EARLY_EXIT,
        KEEP_EXISTING,
        NO_ACTION,
        LEGACY_CANCEL_BRANCH,
        LEGACY_NEUTRAL_BRANCH,
        LEGACY_SET_BRANCH
    }

    data class Snapshot(
        val disposition: Disposition,
        val cgmFinding: BoostV1CgmGateShadow.Finding,
        val tempFinding: BoostV1TempBasalShadow.Finding?,
        val glucoseAgeMinutes: Double?,
        val legacyRateForComparison: Double?,
        val eligibleForPumpDispatch: Boolean = false
    )

    fun inspect(
        glucose: BoostV1CgmGateShadow.Input,
        tempBasal: BoostV1TempBasalShadow.Input
    ): Snapshot {
        val cgm = BoostV1CgmGateShadow.inspect(glucose)
        if (cgm.finding == BoostV1CgmGateShadow.Finding.INVALID_INPUT)
            return Snapshot(Disposition.INPUT_REJECTED, cgm.finding, null, cgm.ageMinutes, null)
        if (cgm.wouldEnterV1EarlyExit)
            return Snapshot(Disposition.CGM_EARLY_EXIT, cgm.finding, null, cgm.ageMinutes, null)

        val temp = BoostV1TempBasalShadow.inspect(tempBasal)
        val disposition = when (temp.finding) {
            BoostV1TempBasalShadow.Finding.INVALID -> Disposition.INPUT_REJECTED
            BoostV1TempBasalShadow.Finding.KEEP_CURRENT -> Disposition.KEEP_EXISTING
            BoostV1TempBasalShadow.Finding.NO_CHANGE -> Disposition.NO_ACTION
            BoostV1TempBasalShadow.Finding.WOULD_CANCEL -> Disposition.LEGACY_CANCEL_BRANCH
            BoostV1TempBasalShadow.Finding.WOULD_SET_NEUTRAL -> Disposition.LEGACY_NEUTRAL_BRANCH
            BoostV1TempBasalShadow.Finding.WOULD_SET -> Disposition.LEGACY_SET_BRANCH
        }
        return Snapshot(
            disposition = disposition,
            cgmFinding = cgm.finding,
            tempFinding = temp.finding,
            glucoseAgeMinutes = cgm.ageMinutes,
            legacyRateForComparison = temp.cappedRateForInspection
        )
    }
}
