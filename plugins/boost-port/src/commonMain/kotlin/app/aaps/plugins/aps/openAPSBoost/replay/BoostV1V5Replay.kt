package app.aaps.plugins.aps.openAPSBoost.replay

import app.aaps.plugins.aps.openAPSBoost.BoostV1ReplayEngine
import app.aaps.plugins.aps.openAPSBoostV5.DetermineBasalBoostV5
import app.aaps.plugins.aps.openAPSBoostV5.MAX_COMMITTED_DOSE_U
import app.aaps.plugins.aps.openAPSBoostV5.MAX_CONFIRMED_COMMIT_DOSE_U
import app.aaps.plugins.aps.openAPSBoostV5.V5Decision
import app.aaps.plugins.aps.openAPSBoostV5.V5Inputs
import app.aaps.plugins.aps.openAPSBoostV5.V5PersistedState
import kotlin.math.abs
import kotlin.math.max

/**
 * Recorded external context for OpenAPSBoostV5Plugin.buildInputs. No Preferences,
 * history queries or wall clock enter replay. activeMode models the SOURCE mode;
 * it does not activate this port. Insulin-adding options default off.
 */
internal data class V5ReplayContext(
    val pumpBolusStep: Double,
    val activeMode: Boolean,
    val asleep: Boolean = false,
    val profileSwitched: Boolean = false,
    val pumpDisconnected: Boolean = false,
    val loopSuspended: Boolean = false,
    val manualBolusInAnnouncementWindow: Boolean = false,
    val fastCarbConfirmEnabled: Boolean = false,
    val aggressiveEarlyConfirmEnabled: Boolean = false,
    val composedFloorRequested: Boolean = false,
    val velocityBudgetRequested: Boolean = false,
    val insulinAddingFloorsAllowed: Boolean = false,
    val aggression: Double = 1.0,
    val hypoCaution: Double = 1.0,
    val sensitivity: Double = 1.0,
    val confirmedCapU: Double = MAX_CONFIRMED_COMMIT_DOSE_U,
    val committedCapU: Double = MAX_COMMITTED_DOSE_U,
    val primerCapU: Double = 0.0,
    val primerTbrFallback: Boolean = false,
    val primerBolusMode: Boolean = false,
)

/**
 * Full V1 -> V5 calculation, confined to caller-owned, sequential replay.
 * V1 retains the feature ring; V5's next state is returned for the caller to
 * retain/serialize. Neither result is an APSResult or a delivery command.
 * The final source V6 override returns a separate candidate, preserving V1's result.
 */
internal class BoostV1V5Replay(
    presentation: V1ReplayPresentation,
    private val clock: V1ReplayClock,
    private val risk: V1ReplayRiskTreeModel? = null,
    private val meal: V1ReplayMealTreeModel? = null,
    private val decideV5: (V5Inputs, V5PersistedState) -> V5Decision = DetermineBasalBoostV5()::decide,
) {
    private val v1 = BoostV1ReplayEngine(presentation, clock)
    private val v6 = V6ReplayOverride()

    data class Frame(
        val v1: V1ReplayResult,
        val v5Inputs: V5Inputs,
        val v5: V5Decision?,
        val finalCandidate: V1ReplayResult,
        val nextV5State: V5PersistedState,
        val v5Failure: String?,
    )

    fun run(
        cycle: V1ReplayCycle,
        context: V5ReplayContext,
        prior: V5PersistedState,
        settings: V6ReplaySettings = V6ReplaySettings(),
    ): Frame {
        val base = cycle.calculate(v1, risk, meal)
        val inputs = V1ToV5ReplayInputs.build(cycle, base, context, clock) { projected -> risk?.predictAtProjectedIob(projected) }
        // Source runShadow/override treats a V5 calculation failure as an unavailable
        // decision, caps the amplified V1 SMB to stock oref and drops any tranche hold.
        val attempt = runCatching { decideV5(inputs, prior) }
        val decision = attempt.getOrNull()
        return Frame(
            base, inputs, decision, v6.apply(base, decision, cycle, context, settings),
            decision?.newPersistedState ?: prior, attempt.exceptionOrNull()?.let { it::class.simpleName },
        )
    }

    fun restoreFeatureHistoryOnce(snapshots: List<V1ReplayFeatures.CycleSnapshot>) = v1.loadFeatureHistoryOnce(snapshots)
    fun featureHistory(): List<V1ReplayFeatures.CycleSnapshot> = v1.snapshotFeatureHistory()
    fun heldTrancheU(): Double = v6.heldTrancheU()
}

/** Source buildInputs arithmetic. No duplicate autosens/ISF calculation is added. */
internal object V1ToV5ReplayInputs {
    fun build(
        cycle: V1ReplayCycle,
        result: V1ReplayResult,
        context: V5ReplayContext,
        clock: V1ReplayClock,
        projectedRisk: (Double) -> Double?,
    ): V5Inputs {
        val gs = cycle.glucose
        val profile = cycle.profile
        val predictions = result.predBGs
        val shortGuard = predictions?.let { pred ->
            listOfNotNull(pred.IOB, pred.UAM, pred.ZT, pred.COB)
                .mapNotNull { it.take(6).minOrNull()?.toDouble() }.minOrNull()
        }
        return V5Inputs(
            delta = gs.delta,
            shortAvgDelta = gs.shortAvgDelta,
            deltaAccl = 100.0 * (gs.delta - gs.shortAvgDelta) / max(abs(gs.shortAvgDelta), 2.0),
            bg = gs.glucose,
            eventualBg = result.eventualBG ?: gs.glucose,
            targetBg = profile.target_bg,
            maxDelta = abs(gs.delta),
            minGuardBg = shortGuard ?: result.minGuardBG ?: gs.glucose,
            minGuardThreshold = profile.lgsThreshold?.toDouble() ?: 80.0,
            deltaHistory = listOf(gs.longAvgDelta, gs.shortAvgDelta, gs.delta),
            iob = cycle.iob.firstOrNull()?.iob ?: 0.0,
            maxIob = minOf(profile.boost_maxIOB, profile.max_iob),
            baseInsulinReq = (result.insulinReq ?: 0.0).coerceAtLeast(0.0),
            roundSmbTo = context.pumpBolusStep,
            enableSmbPreChecks = if (context.activeMode) cycle.microBolusAllowed else true,
            mlHypoRisk = result.mlHypoRisk,
            mlMealLikely = result.mlMealLikely,
            riskAtProjectedIob = { projected -> projectedRisk(projected) ?: (result.mlHypoRisk ?: 0.0) },
            recentLowBg = profile.recentLowBG,
            cumulativeRise30min = max(0.0, gs.shortAvgDelta * 6.0),
            hour = clock.hourAt(cycle.time),
            exerciseActive = profile.v5_exerciseActive,
            inPostExerciseWindow = profile.v5_inPostExerciseWindow,
            asleep = context.asleep,
            fastCarbConfirmEnabled = context.fastCarbConfirmEnabled,
            aggressiveEarlyConfirmEnabled = context.aggressiveEarlyConfirmEnabled,
            sensorQualityOk = if (context.activeMode) !cycle.flatBGsDetected else true,
            postRescueWindow = cycle.recentLowBG45Min < BoostV1ReplayEngine.POST_RESCUE_LOW_THRESHOLD_MGDL,
            v1WouldDoseU = result.units,
            composedFloorActive = context.activeMode && context.composedFloorRequested && context.insulinAddingFloorsAllowed,
            velocityBudgetActive = context.activeMode && context.velocityBudgetRequested && context.insulinAddingFloorsAllowed,
            profileSwitched = context.profileSwitched,
            pumpDisconnected = context.pumpDisconnected,
            loopSuspended = context.loopSuspended,
            timeJumpMinutes = 0.0,
            aggressionUserKnob = context.aggression,
            hypoCautionUserKnob = context.hypoCaution,
            sensitivityUserKnob = context.sensitivity,
            confirmedCapU = context.confirmedCapU,
            committedCapU = context.committedCapU,
            primerCapU = if (context.activeMode) context.primerCapU else 0.0,
            primerUseTempBasal = context.primerTbrFallback && !context.primerBolusMode,
            nowMs = cycle.time,
            mealAnnounced = (result.COB ?: 0.0) > 0.0 || context.manualBolusInAnnouncementWindow,
        )
    }
}
