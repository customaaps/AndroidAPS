package app.aaps.plugins.boostport.telemetry

import kotlin.math.exp
import kotlin.math.max

/**
 * Read-only KMP port of ConfirmTrancheController's fitted release evidence.
 *
 * This does not hold, schedule, release, calculate, or recommend insulin.
 * The historic rule's coefficients are exposed solely to compare a supplied
 * glucose trajectory with the source V5 offline. No permission, I/O, APS,
 * SMB or pump access; the result must never be connected to a therapy action.
 */
internal object BoostV5TrancheEvidenceDiagnostics {
    const val HOLD_MINUTES = 10.0
    const val HOLD_SLACK_MINUTES = 2.5
    const val EXPIRY_MINUTES = 30.0

    data class Observation(
        val bgAtConfirmMgdl: Double,
        val latestBgMgdl: Double,
        val previousBgMgdl: Double?,
        val maximumBgSinceConfirmMgdl: Double,
    )

    enum class Window { TOO_EARLY, EVIDENCE_WINDOW, EXPIRED, INVALID }

    /**
     * Historic 10m hold with 2.5m slack (covers the 9.991m cadence case).
     * Classification only: no pending amount and no release action.
     */
    fun window(confirmTimestampMs: Long, currentTimestampMs: Long): Window {
        if (confirmTimestampMs < 0L || currentTimestampMs < confirmTimestampMs ||
            currentTimestampMs - confirmTimestampMs < 0L) return Window.INVALID
        val elapsed = (currentTimestampMs - confirmTimestampMs) / 60_000.0
        return when {
            elapsed < HOLD_MINUTES - HOLD_SLACK_MINUTES -> Window.TOO_EARLY
            elapsed > EXPIRY_MINUTES -> Window.EXPIRED
            else -> Window.EVIDENCE_WINDOW
        }
    }

    /**
     * Mirrors legacy fitted logistic:
     * B0 + BG_CONFIRM * bgConfirm + RISE_SINCE * deltaSince +
     * MAX_RISE_SINCE * maxRise + SLOPE_NOW * slope + BG_NOW * bgNow.
     */
    fun probability(obs: Observation): Double? {
        val previous = obs.previousBgMgdl
        if (!obs.bgAtConfirmMgdl.isFinite() || !obs.latestBgMgdl.isFinite() ||
            !obs.maximumBgSinceConfirmMgdl.isFinite() ||
            previous?.isFinite() == false ||
            obs.bgAtConfirmMgdl <= 0.0 || obs.latestBgMgdl <= 0.0 ||
            obs.maximumBgSinceConfirmMgdl <= 0.0 ||
            (previous != null && previous <= 0.0)
        ) return null
        val slope = if (previous != null) obs.latestBgMgdl - previous else 0.0
        val z = 2.48631 -
            0.014480 * obs.bgAtConfirmMgdl -
            0.003822 * (obs.latestBgMgdl - obs.bgAtConfirmMgdl) +
            0.138821 * (max(obs.maximumBgSinceConfirmMgdl, obs.latestBgMgdl) - obs.bgAtConfirmMgdl) +
            0.072499 * slope -
            0.018302 * obs.latestBgMgdl
        if (!z.isFinite()) return null
        val result = 1.0 / (1.0 + exp(-z))
        return result.takeIf { it.isFinite() && it in 0.0..1.0 }
    }
}
