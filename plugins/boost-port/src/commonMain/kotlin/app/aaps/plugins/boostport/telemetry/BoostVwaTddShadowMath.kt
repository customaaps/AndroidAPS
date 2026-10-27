package app.aaps.plugins.boostport.telemetry

/**
 * Passive KMP port of the arithmetic in legacy BoostVwaTddShadow.
 *
 * This stages ONLY the pure projection, curve folding and day-bucket rules.
 * Historical database access, state persistence, background execution,
 * APS connection and insulin dosing are deliberately absent. The source
 * explicitly describes VWA-TDD as an experimental, non-dosing candidate.
 */
internal object BoostVwaTddShadowMath {
    const val BUCKETS = 288
    const val SHRINK_DAYS = 10.0
    const val FRACTION_FLOOR = 0.10
    const val SANITY_LO = 0.5
    const val SANITY_HI = 2.0
    const val WEIGHT = 0.5
    const val MIN_DAY_UNITS = 1.0
    private const val DAY_MS = 86_400_000L
    private const val HOUR_MS = 3_600_000L
    private const val BUCKET_MS = DAY_MS / BUCKETS

    data class Projection(
        val unboundedProjection: Double,
        val boundedProjection: Double,
        val comparisonBlend: Double,
        val fraction: Double,
        val usedPreviousDay: Boolean,
    )

    data class Fold(
        val curve: List<Double>,
        val completedDayTotal: Double,
        val curveDays: Int,
    )

    /** Mirrors the shadow's fallbacks, two-sided clamp and 50/50 blend. */
    fun project(
        fraction: Double,
        deliveredToday: Double?,
        tdd7D: Double?,
        previousDayTotal: Double? = null,
    ): Projection? {
        if (!fraction.isFinite() || fraction !in 0.0..1.0 ||
            deliveredToday == null || !deliveredToday.isFinite() || deliveredToday < 0.0 ||
            tdd7D == null || !tdd7D.isFinite() || tdd7D <= 0.0 ||
            (previousDayTotal != null && (!previousDayTotal.isFinite() || previousDayTotal < 0.0))
        ) return null
        val usedPreviousDay = fraction < FRACTION_FLOOR
        val estimate = when {
            !usedPreviousDay -> deliveredToday / fraction
            previousDayTotal != null && previousDayTotal > 0.0 -> previousDayTotal
            else -> tdd7D
        }
        if (!estimate.isFinite()) return null
        val bounded = estimate.coerceIn(SANITY_LO * tdd7D, SANITY_HI * tdd7D)
        val blend = (1.0 - WEIGHT) * tdd7D + WEIGHT * bounded
        if (!bounded.isFinite() || !blend.isFinite()) return null
        return Projection(estimate, bounded, blend, fraction, usedPreviousDay)
    }

    /** The legacy quiet-hour anchor, with exact integer floor division. */
    fun dayAnchorMs(nowMs: Long, anchorHour: Int = 3): Long? {
        if (nowMs < 0L || anchorHour !in 0..23) return null
        val offset = anchorHour.toLong() * HOUR_MS
        val shifted = nowMs - offset
        val quotient = shifted / DAY_MS
        val index = if (shifted < 0L && shifted % DAY_MS != 0L) quotient - 1L else quotient
        return index * DAY_MS + offset
    }

    fun bucket(nowMs: Long, dayAnchorMs: Long): Int? {
        if (nowMs < 0L || dayAnchorMs > nowMs || nowMs - dayAnchorMs >= DAY_MS) return null
        return ((nowMs - dayAnchorMs) / BUCKET_MS).toInt().coerceIn(0, BUCKETS - 1)
    }

    /**
     * Reproduce the original day-finish normalisation and shrinkage step.
     * The input is 288 supplied observations; no database or population
     * curve is fetched or written. Reject malformed input instead of
     * interpreting an incomplete day as evidence of a changed insulin need.
     */
    fun foldCompletedDay(
        existingCurve: List<Double>,
        cumulativeByBucket: List<Double>,
        previousCurveDays: Int,
    ): Fold? {
        if (existingCurve.size != BUCKETS || cumulativeByBucket.size != BUCKETS ||
            previousCurveDays < 0 || previousCurveDays == Int.MAX_VALUE ||
            existingCurve.any { !it.isFinite() || it !in 0.0..1.0 } ||
            cumulativeByBucket.any { !it.isFinite() || it < 0.0 } ||
            existingCurve.zipWithNext().any { it.second < it.first } ||
            existingCurve.last() <= 0.0
        ) return null

        val total = cumulativeByBucket.maxOrNull() ?: return null
        if (total < MIN_DAY_UNITS) return null
        val days = previousCurveDays + 1
        val weight = days.toDouble() / (days.toDouble() + SHRINK_DAYS)
        val blended = MutableList(BUCKETS) { 0.0 }
        var maximum = 0.0
        var previous = 0.0
        for (i in 0 until BUCKETS) {
            maximum = maxOf(maximum, cumulativeByBucket[i])
            val observed = (maximum / total).coerceIn(0.0, 1.0)
            val sample = weight * observed + (1.0 - weight) * existingCurve[i]
            previous = maxOf(previous, sample).coerceIn(0.0, 1.0)
            blended[i] = previous
        }
        val finalFraction = blended.last()
        if (finalFraction <= 0.0 || !finalFraction.isFinite()) return null
        return Fold(blended.map { it / finalFraction }, total, days)
    }
}
