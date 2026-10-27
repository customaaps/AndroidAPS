package app.aaps.plugins.boostport.telemetry

import app.aaps.plugins.aps.openAPSBoost.BoostV1OfflineCycleMath
import app.aaps.plugins.aps.openAPSBoost.BoostV1IsfShadowCalculation
import app.aaps.plugins.aps.openAPSBoost.BoostV1SmbEligibilityShadow
import app.aaps.plugins.aps.openAPSBoost.BoostV1TempBasalShadow

/** Read-only grouping of archived V1 diagnostic components. */
internal object BoostV1UnifiedOfflineInspection {
    enum class Finding { INVALID_INPUT, READY }
    data class Input(
        val snapshot: BoostV1OfflineDependencyBundle.Input,
        val isf: BoostV1IsfShadowCalculation.Input,
        val smb: BoostV1SmbEligibilityShadow.Input,
        val temp: BoostV1TempBasalShadow.Input
    )
    data class Report(
        val finding: Finding,
        val shadowIsf: Double? = null,
        val expectedDelta: Double? = null,
        val canDeliverInsulin: Boolean = false
    )
    fun inspect(i: Input): Report {
        if (BoostV1OfflineDependencyBundle.inspect(i.snapshot).finding !=
            BoostV1OfflineDependencyBundle.Finding.READY) return Report(Finding.INVALID_INPUT)
        if (i.snapshot.engine.glucose.mgDl != i.isf.bgMgDl ||
            i.snapshot.engine.profile.targetMgDl != i.smb.targetMgDl ||
            i.snapshot.engine.meal.cobGrams != i.smb.cobGrams ||
            i.snapshot.engine.meal.carbsGrams != i.smb.carbsGrams ||
            i.snapshot.engine.profile.basalUnitsPerHour != i.temp.profileBasal)
            return Report(Finding.INVALID_INPUT)
        val v1 = BoostV1OfflineCycleMath.inspect(i.snapshot.scalar)
        val isf = BoostV1IsfShadowCalculation.inspect(i.isf)
        val smb = BoostV1SmbEligibilityShadow.inspect(i.smb)
        val temp = BoostV1TempBasalShadow.inspect(i.temp)
        if (v1.finding != BoostV1OfflineCycleMath.Finding.READY ||
            isf.finding != BoostV1IsfShadowCalculation.Finding.READY ||
            smb.finding == BoostV1SmbEligibilityShadow.Finding.INVALID_INPUT ||
            temp.finding == BoostV1TempBasalShadow.Finding.INVALID)
            return Report(Finding.INVALID_INPUT)
        return Report(Finding.READY, isf.shadowIsf, v1.expectedDelta)
    }
}
