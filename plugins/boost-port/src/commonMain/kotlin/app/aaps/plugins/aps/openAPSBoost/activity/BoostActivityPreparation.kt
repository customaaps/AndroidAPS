package app.aaps.plugins.aps.openAPSBoost.activity

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSBoost.NightWindow
import app.aaps.plugins.aps.openAPSBoost.StepFeed
import app.aaps.plugins.aps.openAPSBoost.activity.BoostActivitySettings
import app.aaps.plugins.aps.openAPSBoost.activity.BoostActivitySnapshot
import kotlinx.coroutines.CancellationException

/** Complete source activity preparation; services and the captured cycle state are explicit. */
internal class BoostActivityPreparation(
    private val settings: BoostActivitySettings,
    private val snapshot: BoostActivitySnapshot,
    private val persistenceLayer: PersistenceLayer,
    private val aapsLogger: AAPSLogger? = null,
    private val formatHr: (Double?) -> String,
) {
    private val boostStartTime get() = settings.boostStartTime
    private val boostEndTime get() = settings.boostEndTime
    private val sleepInHours get() = settings.sleepInHours
    private val sleepInSteps get() = settings.sleepInSteps
    private val allowBoostWithHighTt get() = settings.allowBoostWithHighTt
    private val dynIsfNormalTarget get() = settings.dynIsfNormalTarget
    private val inactivitySteps get() = settings.inactivitySteps
    private val inactivityPct get() = settings.inactivityPct
    private val activitySteps5 get() = settings.activitySteps5
    private val activitySteps15 get() = settings.activitySteps15
    private val activitySteps30 get() = settings.activitySteps30
    private val activitySteps60 get() = settings.activitySteps60
    private val activityPct get() = settings.activityPct
    private val hrIntegrationEnabled get() = settings.hrIntegrationEnabled
    private val hrWindowMinutes get() = settings.hrWindowMinutes
    private val hrMaxBpm get() = settings.hrMaxBpm
    private val hrRestingBpm get() = settings.hrRestingBpm
    private val hrStressDetection get() = settings.hrStressDetection
    data class BoostActivityResult(
        val boostActive: Boolean,
        val profileSwitch: Int,
        val minBg: Double,
        val maxBg: Double,
        val targetBg: Double,
        val activityState: String = "none",
        val debugReason: String = "",
        // True when the step-based sleep-in (lie-in) gate is suppressing Boost this cycle. (2026-07-02)
        val sleepInActive: Boolean = false,
        // Which step feeds are live this cycle (F1, 2026-07-07): "phone+wear" | "phone" | "wear" |
        // "none" — written to RT.boostSteps_feed every cycle so a dark feed is visible in NS.
        val stepsFeed: String = "none",
        val steps5: Int = 0, val steps15: Int = 0,
        val steps30: Int = 0, val steps60: Int = 0
    )

    suspend fun calculate(
        now: Long,
        tempTargetSet: Boolean,
        targetBg: Double,
        minBg: Double,
        maxBg: Double,
        profilePercent: Int
    ): BoostActivityResult {
        val debug = StringBuilder()
        val midnight = snapshot.dayStartMs
        val sleepInMillis = (3600000.0 * sleepInHours).toLong()

        var boostStart = midnight + settings.boostStartOfDayMs
        var boostEnd = midnight + settings.boostEndOfDayMs

        debug.append("Boost window: $boostStartTime–$boostEndTime | Now: ${snapshot.localTimeText}")

        if (boostStart > boostEnd) {
            if (now > boostEnd) boostEnd += 86400000L
            else boostStart -= 86400000L
            debug.append(" (overnight wrap)")
        }

        var boostActive = now in boostStart until boostEnd
        var disableReason = ""

        if (!boostActive) {
            disableReason = "Outside boost time window"
        }

        // Disable boost if high temp target and not allowed
        if (boostActive && tempTargetSet && !allowBoostWithHighTt && targetBg > dynIsfNormalTarget) {
            boostActive = false
            disableReason = "High temp target (${kotlin.math.floor(targetBg + 0.5).toLong()} > ${kotlin.math.floor(dynIsfNormalTarget + 0.5).toLong()})"
            aapsLogger?.debug(LTag.APS, "Boost disabled due to high temptarget of $targetBg")
        }

        var recentSteps5Min = snapshot.phoneSteps5
        var recentSteps15Min = snapshot.phoneSteps15
        var recentSteps30Min = snapshot.phoneSteps30
        var recentSteps60Min = snapshot.phoneSteps60
        // Blend the freshest SC rows (phone-sampler / wear / Garmin) additively (max) so the engine
        // sees the same steps the overview graph shows — the live StepService counter is starved on
        // some OEMs (ColorOS/MIUI) while the sampler's SC rows keep flowing. The sampler writes one
        // row per window duration at the same timestamp (each with only its own window field), so
        // take the per-window MAX across all rows at the newest timestamp.
        run {
            val recentSc = try {
                persistenceLayer.getStepsCountFromTimeToTime(now - 15 * 60_000L, now)
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
            val newestTs = recentSc.maxOfOrNull { it.timestamp }
            if (newestTs != null) {
                val rows = recentSc.filter { it.timestamp == newestTs }
                recentSteps5Min = maxOf(recentSteps5Min, rows.maxOf { it.steps5min })
                recentSteps15Min = maxOf(recentSteps15Min, rows.maxOf { it.steps15min })
                recentSteps30Min = maxOf(recentSteps30Min, rows.maxOf { it.steps30min })
                recentSteps60Min = maxOf(recentSteps60Min, rows.maxOf { it.steps60min })
            }
        }

        debug.append("\nSteps: 5m=$recentSteps5Min 15m=$recentSteps15Min 30m=$recentSteps30Min 60m=$recentSteps60Min")

        // ── Step-source availability guard (F1, 2026-07-07) ──
        // 0 steps from a feed that never reported is NOT sedentary — it's unknown. Available =
        // phone pedometer LIVE this boot OR a fresh SC row (wear or phone-sampler) within the
        // WearStepSource freshness window. When unavailable: no INACTIVE profile drop, no
        // steps-based sleep-in, and the outage is breadcrumbed (debug + boostSteps_feed RT).
        val wearScRecent = try {
            persistenceLayer.getStepsCountFromTimeToTime(now - WearStepSource.FRESH_MS, now)
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
            emptyList()
        }
        val stepFeed = StepFeed.State(
            phoneLive = snapshot.phoneFeedLive,
            wearAgeMs = WearStepSource.latest(wearScRecent)?.let { now - it.timestamp }
        )
        val stepsAvailable = stepFeed.available
        if (!stepsAvailable) debug.append("\n${stepFeed.unavailableNote()}")

        // Sleep-in (lie-in) detection. UNLIKE the INACTIVE branch, the lie-in DISABLES Boost —
        // that removes insulin (the safe direction) — so it is deliberately NOT gated on step-feed
        // availability: a dark feed / zero steps during the lie-in window must still disable Boost,
        // or a dawn rise could be amplified into a meal-SMB while the user is in bed. (StepFeed's
        // availability guard applies to the insulin-ADDING branches only; tim's StepFeed.sleepInActive
        // gates this for the night-mode-tracking model — our fixed-window model keeps the protection.
        // For the same reason this fork does NOT use StepFeed.lieInFailsafeEngages: the fixed window
        // never stands down for the detector, so the 2026-07-31 "failsafe disarmed by a SLEEPING
        // detector" defeat path cannot exist here.)
        val inSleepInWindow = now in boostStart until (boostStart + sleepInMillis)
        val sleepInActive = inSleepInWindow && recentSteps60Min < sleepInSteps
        // Sleep as the INACTIVE branch must see it (2026-07-31). SLEEPING is confirmed sleep;
        // PRE_SLEEP is the wind-down before it, and the user is in bed for both. This covers the
        // CORE NIGHT, which the lie-in window cannot reach — a sleeping user has near-zero steps by
        // definition, so the INACTIVE branch would otherwise read "asleep" as "sedentary" and RAISE
        // the profile (ApsBoostInactivityPct), adding insulin overnight. snapshot.sleep is the current
        // cycle's state, evaluated before this activity preparation.
        val boundaryHold = sleepBoundaryHoldActive(
            now, snapshot.lastSleepBoundaryExitMs, settings.sleepHysteresisMin
        )
        val detectorAsleep = snapshot.sleep.state == SleepStateDetector.SleepState.SLEEPING ||
            snapshot.sleep.state == SleepStateDetector.SleepState.PRE_SLEEP || boundaryHold
        // The configured night window as a clock fact, read WHATEVER ApsBoostNightModeEnabled says.
        // This is what makes "INACTIVE never fires overnight" hold by default instead of depending
        // on the user having enabled night mode, and it needs no HR, no steps and no detector.
        val nightStartMs = midnight + settings.nightStartOfDayMs
        val nightEndMs = midnight + settings.nightEndOfDayMs
        val inNightWindow = NightWindow.contains(now, nightStartMs, nightEndMs)
        // Independently of the night-mode toggle, suppress Boost in the configured
        // night window and while the sleep detector reports PRE_SLEEP or SLEEPING.
        if (boostActive && !boostNightGateOpen(inNightWindow, detectorAsleep)) {
            boostActive = false
            disableReason = if (inNightWindow) "Night window (clock)" else "Sleeping (Boost gate closed)"
        }
        // Audit trail for the 2026-07-31 fix: record when the step test alone WOULD have raised the
        // profile but sleep blocked it, so the suppression is visible in NS rather than silent.
        if (StepFeed.inactivityStepsMet(stepsAvailable, profilePercent, recentSteps60Min, inactivitySteps) &&
            (sleepInActive || detectorAsleep || inNightWindow)
        ) {
            debug.append("\nInactivity SUPPRESSED by sleep (60m steps $recentSteps60Min < $inactivitySteps; " +
                "nightWindow=$inNightWindow sleepIn=$sleepInActive detector=${snapshot.sleep.state}) → profile held at 100%")
        }
        if (boostActive && sleepInActive) {
            boostActive = false
            disableReason = "Sleep-in (60m steps $recentSteps60Min < threshold $sleepInSteps, within ${sleepInHours}h of start)"
            aapsLogger?.debug(LTag.APS, "Boost disabled due to lie-in")
        } else if (inSleepInWindow && boostActive) {
            debug.append("\nSleep-in window active but overridden (steps $recentSteps60Min >= $sleepInSteps)")
        }

        var activityMinBg = minBg
        var activityMaxBg = maxBg
        var activityTargetBg = targetBg
        var currentProfileSwitch = profilePercent
        var activityState = "none"

        // Exercise protection is independent of the Boost dose-raising gate.
        run {
            val activityBgTarget = 150.0
            val isActive = boostExerciseStepsMet(
                activitySteps5, activitySteps15, activitySteps30, activitySteps60,
                recentSteps5Min, recentSteps15Min, recentSteps30Min, recentSteps60Min
            )

            // ---- HR-augmented classification (opt-in, additive only) ----
            val hrClassification: HrActivityCalculator.HrClassificationResult? =
                if (hrIntegrationEnabled) {
                    val windowMs = hrWindowMinutes * 60_000L
                    val hrReadings = persistenceLayer.getHeartRatesFromTime(now - windowMs)
                    HrActivityCalculator.classify(
                        hrReadings = hrReadings,
                        nowMillis = now,
                        hrWindowMinutes = hrWindowMinutes,
                        hrMax = hrMaxBpm,
                        // Use learned daytime baseline if banked (≥7 nights); fallback to configured.
                        // This gives a more accurate Karvonen HRR for exercise classification.
                        hrResting = snapshot.learnedDaytimeHrBpm ?: hrRestingBpm,
                        stepsLast15Min = recentSteps15Min,
                        stressDetection = hrStressDetection,
                        aapsLogger = aapsLogger,
                    ).takeIf { it.hrZone != HrActivityCalculator.HrZone.NONE } // null if no HR data
                } else null

            if (hrClassification != null) {
                debug.append("\nHR: ${hrClassification.debugInfo}")
            }

            if (isActive) {
                // Step-only path detected activity; use HR to refine classification
                when (hrClassification?.exerciseState) {
                    HrActivityCalculator.ExerciseState.VIGOROUS_AEROBIC -> {
                        // High intensity aerobic: reduce profile more aggressively, raise target
                        activityState = "VIGOROUS_AEROBIC"
                        if (currentProfileSwitch == 100) {
                            // Use a more conservative profile reduction (cap at activityPct - 10, min 50%)
                            currentProfileSwitch = (activityPct - 10.0).coerceAtLeast(50.0).toInt()
                            aapsLogger?.debug(LTag.APS, "Profile changed to $currentProfileSwitch% due to vigorous aerobic (HR z${hrClassification.hrZone.label})")
                        }
                        if (!tempTargetSet) {
                            activityMinBg = activityBgTarget
                            activityMaxBg = activityBgTarget
                            activityTargetBg = activityBgTarget
                        }
                        debug.append("\nVigorous aerobic (HR ${formatHr(hrClassification.averageHrBpm)} bpm, ${hrClassification.hrZone.label}) → profile ${currentProfileSwitch}%, target $activityTargetBg")
                    }
                    HrActivityCalculator.ExerciseState.RESISTANCE -> {
                        // Resistance exercise: raise target BG but do NOT reduce profile
                        // (acute BG rise; delayed hypo risk — don't increase insulin aggressiveness now)
                        activityState = "RESISTANCE"
                        val resistanceBgTarget = 160.0
                        if (!tempTargetSet) {
                            activityMinBg = resistanceBgTarget
                            activityMaxBg = resistanceBgTarget
                            activityTargetBg = resistanceBgTarget
                        }
                        aapsLogger?.debug(LTag.APS, "Resistance exercise detected via HR (${hrClassification.hrZone.label}): raising target, not reducing profile")
                        debug.append("\nResistance exercise (HR ${formatHr(hrClassification.averageHrBpm)} bpm, ${hrClassification.hrZone.label}) → profile unchanged at ${currentProfileSwitch}%, target $activityTargetBg")
                    }
                    null, HrActivityCalculator.ExerciseState.MODERATE_AEROBIC,
                    HrActivityCalculator.ExerciseState.LIGHT_AEROBIC -> {
                        // Default step-only ACTIVE behaviour
                        activityState = "ACTIVE"
                        if (currentProfileSwitch == 100) {
                            currentProfileSwitch = activityPct.toInt()
                            aapsLogger?.debug(LTag.APS, "Profile changed to $activityPct% due to activity")
                        }
                        if (!tempTargetSet) {
                            activityMinBg = activityBgTarget
                            activityMaxBg = activityBgTarget
                            activityTargetBg = activityBgTarget
                            aapsLogger?.debug(LTag.APS, "TargetBG changed to $activityBgTarget due to activity")
                        }
                        debug.append("\nActivity detected → profile ${currentProfileSwitch}%, target ${activityTargetBg}")
                    }
                    else -> {
                        // HR signal contradicts steps (LOW confidence) — fall back to step-only ACTIVE
                        activityState = "ACTIVE"
                        if (currentProfileSwitch == 100) {
                            currentProfileSwitch = activityPct.toInt()
                            aapsLogger?.debug(LTag.APS, "Profile changed to $activityPct% due to activity (HR inconclusive)")
                        }
                        if (!tempTargetSet) {
                            activityMinBg = activityBgTarget
                            activityMaxBg = activityBgTarget
                            activityTargetBg = activityBgTarget
                        }
                        debug.append("\nActivity detected (HR inconclusive: ${hrClassification.exerciseState}) → profile ${currentProfileSwitch}%, target $activityTargetBg")
                    }
                }
            } else if (boostActive && StepFeed.inactivityEligible(
                    stepsAvailable, currentProfileSwitch, recentSteps60Min, inactivitySteps,
                    sleepInActive = sleepInActive, asleep = detectorAsleep,
                    inNightWindow = inNightWindow
                )) {
                // Inactivity confirmed on a LIVE feed — check HR for stress. (F1 2026-07-07: a dark
                // feed can no longer reach this branch — "no steps" must not mean "sedentary".)
                if (hrIntegrationEnabled && HrActivityCalculator.inactivitySuppressedByElevatedHr(hrClassification)) {
                    // 2026-07-21 CRITICAL SAFETY: an elevated HR (zone ≥ 3) with a low step count is
                    // probable NON-STEP exercise (cycling, rowing, resistance). The inactivity branch
                    // adds insulin (profile → inactivityPct); a real incident raised profile 130% at
                    // zone3 while BG fell 12 mg/dL per 5 min because 64 steps landed in the classifier
                    // dead zone and came back RESTING. Keying on the HR ZONE directly (not the fused
                    // state), suppress the profile-raise entirely and raise the target instead.
                    activityState = "RESISTANCE"
                    val elevatedHrTarget = 160.0
                    if (!tempTargetSet) {
                        activityMinBg = elevatedHrTarget
                        activityMaxBg = elevatedHrTarget
                        activityTargetBg = elevatedHrTarget
                    }
                    aapsLogger?.debug(LTag.APS, "Inactivity SUPPRESSED — HR elevated (${hrClassification?.hrZone?.label}, ${formatHr(hrClassification?.averageHrBpm ?: 0.0)} bpm) with low steps → non-step exercise; raising target to $elevatedHrTarget, profile UNCHANGED (NOT adding insulin)")
                    debug.append("\nInactivity SUPPRESSED (HR ${hrClassification?.hrZone?.label} elevated, 60m steps $recentSteps60Min) → target $activityTargetBg, profile unchanged")
                } else if (hrStressDetection &&
                    hrClassification?.exerciseState == HrActivityCalculator.ExerciseState.STRESS &&
                    hrClassification.confidence != HrActivityCalculator.Confidence.LOW
                ) {
                    // Stress detected: raise target BG without changing profile
                    activityState = "STRESS"
                    val stressBgTarget = 160.0
                    if (!tempTargetSet) {
                        activityMinBg = stressBgTarget
                        activityMaxBg = stressBgTarget
                        activityTargetBg = stressBgTarget
                    }
                    aapsLogger?.debug(LTag.APS, "Stress/illness detected (HR ${formatHr(hrClassification.averageHrBpm)} bpm, no steps): raising target to $stressBgTarget, profile unchanged")
                    debug.append("\nStress/illness (HR ${formatHr(hrClassification.averageHrBpm)} bpm, ${hrClassification.hrZone.label}, no movement) → target $activityTargetBg, profile unchanged")
                } else if (hrIntegrationEnabled && HrActivityCalculator.inactivityRaiseBlockedByHr(hrClassification)) {
                    // Do not increase insulin during low-step zone-2 exercise such as cycling.
                    activityState = "HR_ELEVATED"
                    debug.append("\nInactivity raise WITHHELD (HR ${hrClassification?.hrZone?.label}, 60m steps $recentSteps60Min) → profile unchanged")
                } else {
                    activityState = "INACTIVE"
                    currentProfileSwitch = inactivityPct.toInt()
                    debug.append("\nInactivity detected (60m steps $recentSteps60Min < $inactivitySteps) → profile ${currentProfileSwitch}%")
                    aapsLogger?.debug(LTag.APS, "Profile changed to $inactivityPct% due to inactivity")
                }
            } else if (!isActive &&
                hrIntegrationEnabled &&
                hrClassification?.exerciseState == HrActivityCalculator.ExerciseState.RESISTANCE &&
                hrClassification.confidence != HrActivityCalculator.Confidence.LOW
            ) {
                // HR-only resistance detection (steps don't detect this)
                activityState = "RESISTANCE"
                val resistanceBgTarget = 160.0
                if (!tempTargetSet) {
                    activityMinBg = resistanceBgTarget
                    activityMaxBg = resistanceBgTarget
                    activityTargetBg = resistanceBgTarget
                }
                aapsLogger?.debug(LTag.APS, "Resistance exercise detected via HR only (${hrClassification.hrZone.label}): raising target")
                debug.append("\nResistance (HR-only, ${hrClassification.hrZone.label}) → target $activityTargetBg, profile unchanged")
            } else if (!isActive &&
                hrStressDetection &&
                hrClassification?.exerciseState == HrActivityCalculator.ExerciseState.STRESS &&
                hrClassification.confidence != HrActivityCalculator.Confidence.LOW
            ) {
                activityState = "STRESS"
                val stressBgTarget = 160.0
                if (!tempTargetSet) {
                    activityMinBg = stressBgTarget
                    activityMaxBg = stressBgTarget
                    activityTargetBg = stressBgTarget
                }
                aapsLogger?.debug(LTag.APS, "Stress detected via HR (${hrClassification.hrZone.label}): raising target")
                debug.append("\nStress (HR-only, ${hrClassification.hrZone.label}) → target $activityTargetBg, profile unchanged")
            } else if (!stepsAvailable) {
                // F1 (2026-07-07): feed dark and no HR-only classification fired — profile stays
                // 100%, no target change. isActive is necessarily false here (no step data), so
                // this is exactly the cycle set that previously mis-read as INACTIVE.
                activityState = "steps-unknown"
                debug.append("\nActivity: steps-unknown (feed unavailable — no INACTIVE, profile unchanged)")
            } else {
                activityState = "normal"
                debug.append("\nActivity: normal (no adjustment)")
            }
        }

        if (boostActive) {
            debug.append("\n✓ BOOST ACTIVE ($activityState)")
        } else {
            debug.append("\n✗ BOOST INACTIVE: $disableReason")
        }

        return BoostActivityResult(
            boostActive = boostActive,
            profileSwitch = currentProfileSwitch,
            minBg = activityMinBg,
            maxBg = activityMaxBg,
            targetBg = activityTargetBg,
            activityState = activityState,
            debugReason = debug.toString(),
            sleepInActive = sleepInActive,
            stepsFeed = stepFeed.label,
            steps5 = recentSteps5Min, steps15 = recentSteps15Min,
            steps30 = recentSteps30Min, steps60 = recentSteps60Min
        )
    }


}

internal fun sleepBoundaryHoldActive(nowMs: Long, boundaryExitMs: Long?, hysteresisMin: Int): Boolean {
    if (boundaryExitMs == null || nowMs < boundaryExitMs) return false
    val holdMin = hysteresisMin.coerceAtLeast(0).toLong() + 5L
    return nowMs - boundaryExitMs < holdMin * 60_000L
}

internal fun boostExerciseStepsMet(
    threshold5: Int, threshold15: Int, threshold30: Int, threshold60: Int,
    steps5: Int, steps15: Int, steps30: Int, steps60: Int
): Boolean =
    (threshold5 > 0 && steps5 > threshold5) ||
        (threshold15 > 0 && steps15 > threshold15) ||
        (threshold30 > 0 && steps30 > threshold30) ||
        (threshold60 > 0 && steps60 > threshold60)

internal fun boostNightGateOpen(inNightWindow: Boolean, detectorAsleep: Boolean): Boolean =
            !inNightWindow && !detectorAsleep

