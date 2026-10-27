package app.aaps.plugins.boostport.telemetry

import kotlin.math.exp

/**
 * Inert KMP adaptation of V1 BoostIsfShadow's V4.4.2-style TDD EMA.
 *
 * This is an offline comparison formula, not an ISF override. It has no
 * preferences, persistence, dependency injection, background reader, APS,
 * SMB, profile, pump or dosing interface. In particular it MUST NOT change
 * the existing order/timing of Dmitry's ISF correction.
 */
internal object BoostIsfEmaShadowDiagnostics {
    const val TAU_MS = 3L * 60L * 60L * 1000L
    const val WARMUP_MS = 5L * 24L * 60L * 60L * 1000L

    data class State(
        val ema: Double = 1.0,
        val lastUpdateMs: Long? = null,
        val firstSeenMs: Long? = null,
    )

    data class Comparison(
        val originalRawRatio: Double,
        val warmupFraction: Double,
        val warmedRatio: Double,
        val emaUnclamped: Double,
        val shadowRatio: Double,
        val nextState: State,
    )

    /**
     * Returns null for invalid inputs, timestamps or restored state rather
     * than silently reinterpreting an invalid ratio as safe for dosing.
     *
     * For valid chronological data this mirrors the original formula:
     * raw = clamp(tdd24/tdd7), cold-start five-day interpolation toward 1,
     * time-aware alpha = 1 - exp(-dt/3h), then final autosens clamp.
     */
    fun compare(
        tddLast24H: Double?,
        tdd7D: Double?,
        autosensMin: Double,
        autosensMax: Double,
        nowMs: Long,
        state: State = State(),
    ): Comparison? {
        if (tddLast24H == null || tdd7D == null ||
            !tddLast24H.isFinite() || !tdd7D.isFinite() ||
            tddLast24H <= 0.0 || tdd7D <= 0.0 ||
            !autosensMin.isFinite() || !autosensMax.isFinite() ||
            autosensMin <= 0.0 || autosensMax < autosensMin ||
            nowMs <= 0L || !state.ema.isFinite() ||
            state.ema <= 0.0 || state.lastUpdateMs?.let { it > nowMs || it < 0L } == true ||
            state.firstSeenMs?.let { it > nowMs || it < 0L } == true
        ) return null

        val rawUnclamped = tddLast24H / tdd7D
        if (!rawUnclamped.isFinite()) return null
        val raw = rawUnclamped.coerceIn(autosensMin, autosensMax)
        val firstSeen = state.firstSeenMs ?: nowMs
        val warmup = ((nowMs - firstSeen).toDouble() / WARMUP_MS).coerceIn(0.0, 1.0)
        val warmed = 1.0 + (raw - 1.0) * warmup
        val ema = if (state.lastUpdateMs == null) warmed else {
            val deltaMs = nowMs - state.lastUpdateMs
            val alpha = 1.0 - exp(-deltaMs.toDouble() / TAU_MS.toDouble())
            state.ema + alpha * (warmed - state.ema)
        }
        if (!ema.isFinite()) return null
        return Comparison(
            originalRawRatio = raw,
            warmupFraction = warmup,
            warmedRatio = warmed,
            emaUnclamped = ema,
            shadowRatio = ema.coerceIn(autosensMin, autosensMax),
            nextState = State(ema = ema, lastUpdateMs = nowMs, firstSeenMs = firstSeen),
        )
    }
}
