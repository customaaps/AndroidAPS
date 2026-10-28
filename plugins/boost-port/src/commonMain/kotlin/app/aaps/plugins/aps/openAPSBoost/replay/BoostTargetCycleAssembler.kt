package app.aaps.plugins.aps.openAPSBoost.replay

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.DynamicCarbRatioPolicy
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.plugins.aps.openAPSBoost.activity.BoostActivityPreparation
import app.aaps.plugins.aps.openAPSBoost.activity.BoostActivitySettings
import app.aaps.plugins.aps.openAPSBoost.activity.BoostActivitySnapshot

/** One read/preparation path from target services to the full V1/V5/V6 engine input. */
internal class BoostTargetCycleAssembler(
    private val persistence: PersistenceLayer,
    private val tdd: TddCalculator,
    private val dynamicCr: DynamicCarbRatioPolicy,
) {
    data class Inputs(
        val glucose: GlucoseStatus,
        val currentTemp: CurrentTemp,
        val iob: Array<IobTotal>,
        val meal: MealData,
        val constrainedProfile: OapsProfile,
        val originalProfileCr: Double,
        val autosens: AutosensResult,
        val isfSettings: BoostIsfSettings,
        val context: BoostProfileContext,
        val microBolusAllowed: Boolean,
        val flatBg: Boolean,
        val cumulativeSmbCap: Double,
        val postRescueTightRampCap: Double? = null,
        val capturedHistory: BoostTargetHistory? = null,
    )

    /** Resolve activity first, then let the owner apply recovery/meal policy before ISF preparation. */
    suspend fun assembleWithActivity(
        input: Inputs,
        settings: BoostActivitySettings,
        snapshot: BoostActivitySnapshot,
        formatHr: (Double?) -> String,
        resolveRecoveryAndMealContext: suspend (BoostProfileContext, BoostActivityPreparation.BoostActivityResult) -> BoostProfileContext,
    ): V1ReplayCycle {
        val c = input.context
        val activity = BoostActivityPreparation(settings, snapshot, persistence, formatHr = formatHr)
            .calculate(c.atMs, input.constrainedProfile.temptargetSet, c.targetBg, c.minBg, c.maxBg, c.profilePercent)
        val activeContext = c.copy(profilePercent = activity.profileSwitch, boostActive = activity.boostActive,
            minBg = activity.minBg, maxBg = activity.maxBg, targetBg = activity.targetBg,
            activityState = activity.activityState, boostDebug = activity.debugReason,
            steps5 = activity.steps5, steps15 = activity.steps15, steps30 = activity.steps30, steps60 = activity.steps60)
        return assemble(input.copy(context = resolveRecoveryAndMealContext(activeContext, activity)))
    }

    suspend fun assemble(input: Inputs): V1ReplayCycle {
        val atMs = input.context.atMs
        val history = input.capturedHistory ?: BoostTargetHistoryReader.read(persistence, atMs)
        val parts = BoostTargetTddReader.read(tdd, atMs)
        val prepared = BoostTargetProfileAdapter.prepareWithSharedCr(
            input.constrainedProfile, input.autosens, input.isfSettings, parts,
            input.context.copy(glucose = input.glucose.glucose,
                recentLowBg = history.recentLowBg60, brakingProduct = history.brakingProduct),
            dynamicCr, profileCarbRatio = input.originalProfileCr,
        )
        return V1ReplayCycle(
            input.glucose, input.currentTemp.copy(), input.iob, prepared.profile, prepared.autosens,
            input.meal.copy(), input.microBolusAllowed, atMs, input.flatBg,
            history.recentSmbVolume60, input.cumulativeSmbCap, history.recentLowBg45,
            history.minutesSinceLastSmb, input.postRescueTightRampCap,
            BoostIsfDiagnosticInput(parts, prepared.isf, input.isfSettings),
        )
    }
}
