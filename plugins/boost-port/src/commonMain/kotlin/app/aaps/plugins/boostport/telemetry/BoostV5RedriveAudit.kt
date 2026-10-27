package app.aaps.plugins.boostport.telemetry

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Offline KMP adaptation of the MOVEMENT-tracking part of V5 auto-config
 * periodic re-derivation (2026-08-03 schema v2).
 *
 * This computes an hypothetical update for inspection only. It never writes
 * preferences, recalibrates an active pump, changes Dmitry's ISF/SMB or
 * participates in an APS cycle. Cumulative/primer computed knobs remain
 * outside this particular four-knob tracked-policy helper.
 */
internal object BoostV5RedriveAudit {
    private const val STEP_FRACTION = 0.25
    private const val EPS = 1e-4

    enum class Outcome {
        BASELINE_RECORDED, NO_CHANGE, INSIDE_DEADBAND,
        AWAITING_CONFIRMATION, WITHHELD_CAP_RAISE, WOULD_UPDATE,
    }

    data class Proposal(
        val outcome: Outcome,
        val operativeValue: Double,
        val candidateValue: Double,
        val nextDerivedBaseline: Double?,
        val nextPendingValue: Double?,
    )

    data class Input(
        val knob: BoostV5ConfigResolutionAudit.Knob,
        val current: Double,
        val derivedNow: Double,
        val previousDerivedBaseline: Double?,
        val pendingCandidate: Double? = null,
        val minimum: Double,
        val maximum: Double,
        val tbrBelow70Pct: Double,
        val timeBelow54Pct: Double,
    )

    /** No writes; caller holds all history and decides whether to inspect it. */
    fun inspect(input: Input): Proposal? {
        val kind = input.knob
        val ratioKey = kind == BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP ||
            kind == BoostV5ConfigResolutionAudit.Knob.COMMITTED_CAP
        val offsetKey = kind == BoostV5ConfigResolutionAudit.Knob.AGGRESSION ||
            kind == BoostV5ConfigResolutionAudit.Knob.HYPO_CAUTION
        if (!ratioKey && !offsetKey) return null
        if (!input.current.isFinite() || input.current < 0.0 ||
            !input.derivedNow.isFinite() || input.derivedNow <= 0.0 ||
            !input.minimum.isFinite() || !input.maximum.isFinite() ||
            input.minimum < 0.0 || input.maximum < input.minimum ||
            input.current !in input.minimum..input.maximum ||
            !input.tbrBelow70Pct.isFinite() || !input.timeBelow54Pct.isFinite() ||
            input.tbrBelow70Pct !in 0.0..100.0 ||
            input.timeBelow54Pct !in 0.0..100.0 ||
            input.previousDerivedBaseline?.let { !it.isFinite() || it < 0.0 } == true ||
            input.pendingCandidate?.let { !it.isFinite() } == true
        ) return null

        val baseline = input.previousDerivedBaseline
        if (baseline == null || baseline <= 0.0) {
            return Proposal(Outcome.BASELINE_RECORDED, input.current, input.current,
                input.derivedNow, null)
        }

        val raw = if (ratioKey) input.current * (input.derivedNow / baseline)
        else input.current + (input.derivedNow - baseline)
        if (!raw.isFinite()) return null
        val maxStep = abs(input.current) * STEP_FRACTION
        val bounded = raw.coerceIn(input.current - maxStep, input.current + maxStep)
            .coerceIn(input.minimum, input.maximum)
        val candidate = (bounded * 100.0).roundToInt() / 100.0
        val delta = candidate - input.current
        if (!candidate.isFinite() || !delta.isFinite()) return null

        if (abs(delta) <= EPS) {
            return Proposal(Outcome.NO_CHANGE, input.current, candidate, baseline, null)
        }
        if (offsetKey &&
            (input.pendingCandidate == null || abs(input.pendingCandidate - candidate) > EPS)
        ) return Proposal(Outcome.AWAITING_CONFIRMATION, input.current, candidate,
            baseline, candidate)

        val band = when (kind) {
            BoostV5ConfigResolutionAudit.Knob.COMMITTED_CAP -> 0.07
            BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP -> 0.47
            else -> 0.0
        }
        if (ratioKey && abs(delta) <= band) {
            return Proposal(Outcome.INSIDE_DEADBAND, input.current, candidate,
                baseline, input.pendingCandidate)
        }

        val guard = input.tbrBelow70Pct > BoostV5ConfigResolutionAudit.TBR70_RAISE_GUARD ||
            input.timeBelow54Pct >= BoostV5ConfigResolutionAudit.SEV54_RAISE_GUARD
        if (ratioKey && delta > 0.0 && guard) {
            return Proposal(Outcome.WITHHELD_CAP_RAISE, input.current, candidate,
                baseline, null)
        }

        // The baseline only moves as far as the bounded *hypothetical* move;
        // a clipped 25% step preserves the remainder for later inspection.
        val nextBaseline = if (ratioKey) {
            if (abs(input.current) > EPS) baseline * (candidate / input.current)
            else input.derivedNow
        } else baseline + delta
        if (!nextBaseline.isFinite() || nextBaseline <= 0.0) return null
        return Proposal(Outcome.WOULD_UPDATE, candidate, candidate,
            nextBaseline, null)
    }
}
