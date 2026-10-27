package app.aaps.plugins.aps.openAPSBoost

/**
 * Offline-only port of original DetermineBasalBoost.enable_smb condition order.
 * A true condition is merely an observed historical branch, NOT an authorization
 * for insulin delivery. The caller cannot turn on an APS SMB feature with it.
 */
internal object BoostV1SmbEligibilityShadow {
    enum class Finding { INVALID_INPUT, BLOCKED, CONDITION_MATCHED }
    enum class Reason { MASTER_DISABLED, HIGH_TEMP_TARGET, ALWAYS, COB, RECENT_CARBS, LOW_TEMP_TARGET, NO_MATCH }
    data class Input(
        val microBolusAllowed: Boolean,
        val allowHighTempTarget: Boolean,
        val tempTargetSet: Boolean,
        val targetMgDl: Double,
        val enableAlways: Boolean,
        val enableWithCob: Boolean,
        val enableAfterCarbs: Boolean,
        val enableWithTempTarget: Boolean,
        val cobGrams: Double,
        val carbsGrams: Double
    )
    data class Report(
        val finding: Finding,
        val reason: Reason?,
        val canDeliverInsulin: Boolean = false
    )
    fun inspect(i: Input): Report {
        if (!i.targetMgDl.isFinite() || i.targetMgDl <= 0.0 ||
            !i.cobGrams.isFinite() || i.cobGrams < 0.0 ||
            !i.carbsGrams.isFinite() || i.carbsGrams < 0.0)
            return Report(Finding.INVALID_INPUT, null)
        val reason = when {
            !i.microBolusAllowed -> Reason.MASTER_DISABLED
            !i.allowHighTempTarget && i.tempTargetSet && i.targetMgDl > 100.0 -> Reason.HIGH_TEMP_TARGET
            i.enableAlways -> Reason.ALWAYS
            i.enableWithCob && i.cobGrams != 0.0 -> Reason.COB
            i.enableAfterCarbs && i.carbsGrams != 0.0 -> Reason.RECENT_CARBS
            i.enableWithTempTarget && i.tempTargetSet && i.targetMgDl < 100.0 -> Reason.LOW_TEMP_TARGET
            else -> Reason.NO_MATCH
        }
        val matched = reason == Reason.ALWAYS || reason == Reason.COB ||
            reason == Reason.RECENT_CARBS || reason == Reason.LOW_TEMP_TARGET
        return Report(if (matched) Finding.CONDITION_MATCHED else Finding.BLOCKED, reason)
    }
}
