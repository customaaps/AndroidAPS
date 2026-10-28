// Source V6 final override used by live Boost and replay; pump enactment belongs to Loop.
package app.aaps.plugins.aps.openAPSBoost.replay

import app.aaps.core.interfaces.utils.Round
import app.aaps.plugins.aps.openAPSBoostV5.ConfirmTrancheController
import kotlin.math.roundToLong
import app.aaps.plugins.aps.openAPSBoostV5.V5Decision
import app.aaps.plugins.aps.openAPSBoostV5.MealHypothesis
import kotlin.math.max

/** One sequential replay session; tranche holds are in memory, as in the source plugin. */
internal class V6ReplayOverride {
    private val confirmTranche = ConfirmTrancheController()

    fun heldTrancheU(): Double = confirmTranche.heldU()

    /** Return a separate candidate; never mutate the V1 result or issue a command. */
    fun apply(
        base: V1ReplayResult,
        v5decision: V5Decision?,
        cycle: V1ReplayCycle,
        context: V5ReplayContext,
        settings: V6ReplaySettings,
    ): V1ReplayResult {
        val it = base.copy(reason = StringBuilder(base.reason.toString()))
        val v5Active = context.activeMode
        val microBolusAllowed = cycle.microBolusAllowed
        val v5Asleep = context.asleep
        val inPostRescueWindow = cycle.recentLowBG45Min < 75.0
        val cumulativeSmbCap60Min = cycle.cumulativeSmbCap60Min
        val recentSmbVolume60Min = cycle.recentSmbVolume60Min
        val recentLowBG45Min = cycle.recentLowBG45Min
        val oapsProfile = cycle.profile
        val iobArray = cycle.iob
        val glucoseStatus = cycle.glucose
        val currentTemp = cycle.currentTemp
        val now = cycle.time
        val cumulativeCapReached = cumulativeSmbCap60Min > 0.0 && recentSmbVolume60Min >= cumulativeSmbCap60Min
        var trancheRan = false
        // Cumulative-cap telemetry every cycle: makes a cap suppression distinguishable from a
        // zero-dose decision in NS (the fields were otherwise never written).
        if (v5Active && microBolusAllowed && v5decision != null && !v5Asleep && !cumulativeCapReached) {
            val v1WouldDose = it.units ?: 0.0
            // Non-meal-state cap (2026-07-02): V6 may only OUT-dose V1 when it holds a meal
            // hypothesis (CONFIRMED/COMMITTED). In IDLE/OBSERVING/RECOVERING the V5 state caps
            // don't apply (applyStateDoseCap passes them through) and IDLE's 1.0× multiplier can
            // front a multi-unit correction that bypasses V1's per-SMB sizing — 5-month cohort
            // shadow data: ~1,430U cumulative IDLE excess over V1, worst 3.7U vs 0.45U at BG 210,
            // incl. 2.0U at 05:02 where V1 dosed 0. Capping at V1's would-dose makes IDLE match
            // its own spec ("standard oref dose; no meal hypothesis"); genuine meal rises still
            // get full V6 dosing via OBSERVING→CONFIRMED.
            // 2026-07-17 velocity-budget floor exemption: when the ACTIVE velocity-budget floor
            // lifted this cycle's dose (velocityBudgetExempt), treat it as a meal state so the
            // floored hold can out-dose V1 on the budget≈0 high tail (V1 doses ~0 there). Safe by
            // construction: the exempt dose is committedCap + maxIOB bounded, the floor requires
            // !postRescueWindow, and the cumulative-60min / boost-active / sleep gates all still run.
            // 2026-07-20 V1-acceleration primer (bolus mode): the OBSERVING primer is already folded
            // into finalDose and floored/netted in-engine, so exempt it from the non-meal v1-cap
            // (it must out-dose V1's OBSERVING dose — that IS the reclaimed early insulin). The
            // primer's own floors (recentLow≥80, !postRescue, awake, !exercise, maxIOB) ran in-engine;
            // the cumulative-60min/sleep/boost-active seam guards above still apply.
            val inMealState = v5decision.mealHypothesis.name == "CONFIRMED" || v5decision.mealHypothesis.name == "COMMITTED" ||
                v5decision.velocityBudgetExempt ||
                (v5decision.primerBolusU > 0.0 && !v5decision.primerUseTempBasal)
            // Post-rescue meal-state cap (2026-07-04): inside the post-rescue window the meal-state
            // exemption is suppressed and CONFIRMED/COMMITTED are ALSO capped at V1's would-dose —
            // which is hypo-restrained by V1's aligned tier guard (same value, same threshold; see
            // applyV6OverrideCaps KDoc for the 2026-07-03 nadir-40 incident + backtest evidence).
            val caps = applyV6OverrideCaps(inMealState, inPostRescueWindow, v5decision.finalDose, v1WouldDose, recentLowBG45Min)
            var overrideDose = caps.dose
            // 2026-08-27 confirm tranche. Split the confirm commitment: part now, the rest ten
            // minutes later only if the rise continues. The confirm shot is currently the same
            // size whether the excursion reaches 20 mg/dL or 100, and the trace separates those
            // two ends at 0.730 at the confirming cycle against 0.893 ten minutes on, a paired
            // gain of +0.162 [+0.066, +0.264]. Bounded: this can only deliver LESS than the
            // engine would without it.
            //
            // The release is evaluated inside this same block deliberately. Ten minutes after a
            // confirm the block runs on 76.4% of cycles, and the 23.6% where it does not divide
            // into exactly two causes, the rolling cumulative SMB cap and the sleep gate, both
            // of which are states in which the engine has already decided against a micro bolus.
            // A release that cannot land is that machinery agreeing with the withhold.
            if (settings.confirmTrancheEnabled) {
                trancheRan = true
                confirmTranche.immediateFraction = settings.immediateFraction
                confirmTranche.releaseThreshold = settings.releaseThreshold
                val before = overrideDose
                var gateNote = ""
                overrideDose = if (v5decision.mealHypothesis == MealHypothesis.CONFIRMED) {
                    confirmTranche.onConfirm(now, glucoseStatus.glucose, before)
                } else {
                    val release = confirmTranche.onCycleBounded(
                        now, glucoseStatus.glucose,
                        ConfirmTrancheController.ReleaseBounds(
                            inMealState = v5decision.mealHypothesis == MealHypothesis.COMMITTED,
                            hardGateFired = v5decision.phase3.reductions.hardGateFired != null,
                            postRescueWindow = inPostRescueWindow,
                            ceilingU = trancheReleaseCeiling(
                                before,
                                minOf(oapsProfile.boost_maxIOB, oapsProfile.max_iob),
                                iobArray.firstOrNull()?.iob ?: 0.0,
                                context.confirmedCapU
                            )
                        )
                    )
                    gateNote = release.note
                    before + release.units
                }
                it.reason.append("tranche=${Round.roundTo(before, 0.001)},"
                    + "${Round.roundTo(overrideDose, 0.001)},"
                    + "${Round.roundTo(confirmTranche.heldU(), 0.001)},"
                    + "${confirmTranche.probeProbability(glucoseStatus.glucose)?.let { p -> Round.roundTo(p, 0.001) } ?: "-"},"
                    + "${v5decision.mealHypothesis}; ")
                if (gateNote.isNotEmpty()) it.reason.append("trancheGate=$gateNote; ")
            }
            it.units = overrideDose
            it.reason.append("V6-ACTIVE drove SMB ${Round.roundTo(overrideDose, 0.001)}U (base would=${Round.roundTo(v1WouldDose, 0.001)}U, state=${v5decision.mealHypothesis}${caps.capNote}); ")
            // 2026-07 composed brake-floor breadcrumb: when the Advanced toggle is ON,
            // decision.floorWouldAdd carries the uplift the floor actually APPLIED (active
            // semantics — see V5Decision KDoc). Log the un-floored→floored delivery so a
            // floored cycle is auditable straight from the reason line. Whenever the uplift
            // is > 0 the floored dose is inside every seam cap by construction (RECOVERING
            // v1-bound + !postRescueWindow are conditions of the floor itself), so
            // overrideDose == v5decision.finalDose here and X→Y is the delivered truth.
            val floorUplift = if (context.composedFloorRequested) v5decision.floorWouldAdd ?: 0.0 else 0.0
            if (floorUplift > 0.0) {
                it.reason.append("brake-floor applied: ${Round.roundTo(v5decision.finalDose - floorUplift, 0.001)}→${Round.roundTo(v5decision.finalDose, 0.001)} U; ")
            }
            // 2026-07-17 velocity-budget floor breadcrumb: when ON, decision.velocityBudgetWouldAdd
            // carries the uplift actually APPLIED (out-dosing V1 on the budget≈0 tail via the
            // non-meal-cap exemption). overrideDose == v5decision.finalDose here (the exemption made
            // inMealState true), so X→Y is the delivered truth.
            val vbUplift = if (context.velocityBudgetRequested) v5decision.velocityBudgetWouldAdd ?: 0.0 else 0.0
            if (vbUplift > 0.0) {
                it.reason.append("velocity-budget floor applied: ${Round.roundTo(v5decision.finalDose - vbUplift, 0.001)}→${Round.roundTo(v5decision.finalDose, 0.001)} U (base insulinReq≈0); ")
            }
            // ===== 2026-07-20 V1-acceleration early primer delivery + breadcrumb =====
            if (v5decision.primerBolusU > 0.0) {
                if (v5decision.primerUseTempBasal) {
                    // RETRACTABLE temp-basal fallback (hypo-prone routing): deliver ~primerBolusU over
                    // a short window as a raise ABOVE scheduled basal — additive-only. NEVER fires when
                    // the base engine is suspending/reducing (it.rate < current_basal): a protective
                    // low/zero temp always wins. Never lowers what the base engine already planned
                    // (max), and never shortens its duration. Expires (retracts) if the meal fades.
                    val primerTbrDurationMin = 30
                    val curBasal = oapsProfile.current_basal
                    // A null proposed rate can mean the already-running protective temp is unchanged.
                    val baseRate = effectiveBaseTempRate(it.rate, currentTemp.rate, currentTemp.duration)
                    val extraRate = v5decision.primerBolusU * (60.0 / primerTbrDurationMin)
                    val primerRate = curBasal + extraRate
                    when (primerTbrAction(baseRate, curBasal, primerRate)) {
                        // Preserve an already-running protective temp basal.
                        PrimerTbrAction.SKIP_PROTECTIVE ->
                            it.reason.append("primer=tbr-skipped(base-temp ${Round.roundTo(baseRate ?: 0.0, 0.001)}<basal ${Round.roundTo(curBasal, 0.001)}); ")
                        // A running temp already delivering as much as primer must not be extended.
                        PrimerTbrAction.SUBSUMED ->
                            it.reason.append("primer=tbr-subsumed(base ${Round.roundTo(baseRate ?: 0.0, 0.001)}≥primer ${Round.roundTo(primerRate, 0.001)}U/h); ")
                        // Apply only if the primer raises delivery above the current plan.
                        PrimerTbrAction.APPLY -> {
                            it.rate = primerRate
                            it.duration = max(it.duration ?: 0, primerTbrDurationMin)
                            it.reason.append("primer=tbr,${Round.roundTo(v5decision.primerBolusU, 0.001)}U→${Round.roundTo(primerRate, 0.001)}U/h×${it.duration}m; ")
                        }
                    }
                } else {
                    // Bolus mode: already folded into the SMB above (finalDose) + exempted from the
                    // non-meal cap. Just log the reclaimed early insulin.
                    // 2026-07-30: if auto-config RECOMMENDED the retractable temp-basal route and the
                    // user has overridden it to a bolus, say so in the reason. The override is always
                    // honoured — auto-config never makes a setting unreachable — but an overridden
                    // routing must be VISIBLE in the data, because it is the difference between a
                    // primer the loop can unwind and one it cannot. Both live primer users had
                    // silently overridden, which is why the 2026-07-29 incident could not be undone.
                    val routeOverridden = context.primerTbrFallback &&
                        context.primerBolusMode
                    it.reason.append("primer=bolus,${Round.roundTo(v5decision.primerBolusU, 0.001)}U" +
                        (if (routeOverridden) ";primerRoute=bolus-USER-OVERRIDE(recommended=tbr)" else "") + "; ")

                }
            }
            // 2026-07-30 primer sizing telemetry. Emitted whenever the primer GATE opened — including
            // when the state factors sized it to nothing and it rounded to 0U (primerBolusU == 0, so
            // the block above is skipped). Without this the shadow cannot tell "gate never opened"
            // from "gate opened, correctly sized to zero", which is the whole point of the rework.
            if (v5decision.primerScaleDebug.isNotEmpty()) {
                it.reason.append("primerScale=${v5decision.primerScaleDebug}" +
                    (if (v5decision.primerBolusU <= 0.0) ",ROUNDED_TO_ZERO" else "") + "; ")
            }
        } else if (v5Active && v5decision != null && cumulativeCapReached) {
            it.units = 0.0
            it.reason.append("V6 suppressed (cumulative SMB cap ${Round.roundTo(recentSmbVolume60Min, 0.01)}U/${Round.roundTo(cumulativeSmbCap60Min, 0.01)}U reached); ")
        } else if (v5Active && v5Asleep && v5decision != null) {
            it.reason.append("V6 suppressed (SLEEPING) — base SMB ${Round.roundTo(it.units ?: 0.0, 0.001)}U; ")
                    } else if (v5Active && v5decision == null) {
            // Fail closed: a V6 error must not fall back to Boost's amplified V1 tier dose.
            val orefSmb = baseOrefSmb(
                insulinReq = it.insulinReq ?: 0.0,
                iob = iobArray.firstOrNull()?.iob ?: 0.0,
                currentBasal = oapsProfile.current_basal,
                maxUamSmbBasalMinutes = oapsProfile.maxUAMSMBBasalMinutes,
                maxSmbBasalMinutes = oapsProfile.maxSMBBasalMinutes,
                boostInsulinReqPct = oapsProfile.Boost_InsulinReq,
                bolusIncrement = oapsProfile.bolus_increment
            )
            val capped = v6UnavailableSmb(it.units, orefSmb)
            if (capped != it.units) {
                it.reason.append("V6 unavailable: base SMB capped to ${Round.roundTo(orefSmb, 0.001)}U; ")
                it.units = capped
            }
        }

        // A hold is invalid once the V6 override is skipped for any reason.
        if (!trancheRan && confirmTranche.heldU() > 0.0) {
            it.reason.append("trancheGate=dropped:seam-skipped; ")
            confirmTranche.reset()
        }


        return it
    }

    internal data class V6OverrideCaps(val dose: Double, val capNote: String)

    /**
     * V6-override dose caps (pure — unit-tested directly):
     *  - non-meal-state cap (2026-07-02): in IDLE/OBSERVING/RECOVERING V6 never out-doses V1;
     *  - post-rescue meal-state cap (2026-07-04): inside the post-rescue window
     *    (recentLowBG45Min < [DetermineBasalBoost.POST_RESCUE_LOW_THRESHOLD_MGDL]) the meal-state
     *    exemption is suppressed, so CONFIRMED/COMMITTED are ALSO capped at V1's would-dose.
     *
     * Incident 2026-07-03 19:47 BST: severe hypo (nadir 40) → unannounced rescue carbs → violent
     * rebound. V6 CONFIRMED at BG 119 delivered 2.7U while V1's 45-min post-rescue tier guard had
     * restrained the base engine to 1.05U — the meal-state exemption discarded that restraint. BG
     * then ran 181 → nadir 81 with zero margin, and the 2.7U tripped the 2.5U cumulative cap,
     * silencing V6 for the following hour.
     *
     * DB backtest (2026-07-04): 20.4% of meal-state cycles are post-rescue; 27% of the insulin this
     * cap removes sits directly ahead of a second low < 70 (vs 14-19% for every other lever
     * evaluated). Cost side: 10% genuine post-hypo meals, median 0.15U under-delivery, zero
     * double-dips. Verdict SHIP.
     *
     * WHY inherit V1 (alignment is load-bearing): the 75 mg/dL / 45-min window is deliberately the
     * SAME constant + source value as V1's post-rescue tier guard (DetermineBasalBoost Fix A v2),
     * so whenever this cap binds, v1WouldDose is by construction the hypo-restrained dose — the cap
     * inherits V1's restraint instead of inventing a second, divergent notion of "post-rescue".
     */
    internal fun applyV6OverrideCaps(
        inMealState: Boolean,
        inPostRescueWindow: Boolean,
        v5FinalDose: Double,
        v1WouldDose: Double,
        recentLowBG45Min: Double
    ): V6OverrideCaps {
        val dose = if (inMealState && !inPostRescueWindow) v5FinalDose else minOf(v5FinalDose, v1WouldDose)
        val capNote = when {
            dose >= v5FinalDose -> ""
            inMealState         -> ", post-rescue capped from ${Round.roundTo(v5FinalDose, 0.001)}U to V1's ${Round.roundTo(v1WouldDose, 0.001)}U (45-min low ${Round.roundTo(recentLowBG45Min, 1.0)})"
            else                -> ", non-meal-capped from ${Round.roundTo(v5FinalDose, 0.001)}U"
        }
        return V6OverrideCaps(dose, capNote)
    }
    /** Remaining release headroom under both IOB and confirm-shot limits. */
    internal fun trancheReleaseCeiling(cycleDoseU: Double, maxIobU: Double, iobU: Double, confirmedCapU: Double): Double {
        if (!cycleDoseU.isFinite() || !maxIobU.isFinite() || !iobU.isFinite() || !confirmedCapU.isFinite()) return 0.0
        return maxOf(0.0, minOf(maxIobU - iobU - cycleDoseU, confirmedCapU - cycleDoseU))
    }


    /** Protective low temp wins; an existing high temp subsumes a V6 primer. */
    internal enum class PrimerTbrAction { SKIP_PROTECTIVE, SUBSUMED, APPLY }

    internal fun primerTbrAction(baseRate: Double?, currentBasal: Double, primerRate: Double): PrimerTbrAction = when {
        baseRate != null && baseRate < currentBasal -> PrimerTbrAction.SKIP_PROTECTIVE
        baseRate != null && baseRate >= primerRate -> PrimerTbrAction.SUBSUMED
        else -> PrimerTbrAction.APPLY
    }

    /** Use an existing running temp when the base engine proposes no new rate. */
    internal fun effectiveBaseTempRate(baseRate: Double?, currentTempRate: Double, currentTempDurationMin: Int): Double? =
        baseRate ?: if (currentTempDurationMin > 0) currentTempRate else null

    /** Stock oref-style SMB sizing for when the active V6 decision is unavailable. */
    internal fun baseOrefSmb(
        insulinReq: Double,
        iob: Double,
        currentBasal: Double,
        maxUamSmbBasalMinutes: Int,
        maxSmbBasalMinutes: Int,
        boostInsulinReqPct: Double,
        bolusIncrement: Double
    ): Double {
        if (!insulinReq.isFinite() || !iob.isFinite() || !currentBasal.isFinite() ||
            !boostInsulinReqPct.isFinite() || !bolusIncrement.isFinite() ||
            insulinReq <= 0.0 || boostInsulinReqPct <= 0.0 || bolusIncrement <= 0.0) return 0.0
        val minutes = if (iob > -0.2) maxUamSmbBasalMinutes else maxSmbBasalMinutes
        val maxBolus = (currentBasal * minutes / 60.0 * 10.0).roundToLong() / 10.0
        val roundSmbTo = 1.0 / bolusIncrement
        return kotlin.math.floor(minOf(insulinReq / (100.0 / boostInsulinReqPct), maxBolus) * roundSmbTo) / roundSmbTo
    }

    internal fun v6UnavailableSmb(v1Units: Double?, orefSmb: Double): Double? =
        if (v1Units != null && v1Units > orefSmb) orefSmb else v1Units


}

