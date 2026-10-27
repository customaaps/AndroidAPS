package app.aaps.plugins.boostport.telemetry

/**
 * Isolated, read-only KMP adaptation of the legacy V1 HrActivityCalculator.
 *
 * Inputs are immutable copies of HR records and a known step count. No AAPS
 * database, APS plugin, profile/target changes, pump commands or persistence.
 * A missing step feed is explicitly unknown: it must never be interpreted as
 * sedentary. Classification here is observational, NOT a treatment decision.
 */
internal object HrActivityDiagnostics {
    enum class Zone { NONE, VERY_LIGHT, LIGHT, MODERATE, HARD, MAXIMUM }
    enum class Confidence { HIGH, MEDIUM, LOW }
    enum class ExerciseState {
        VIGOROUS_AEROBIC, MODERATE_AEROBIC, LIGHT_AEROBIC,
        RESISTANCE, STRESS, RESTING, INACTIVE
    }
    enum class SignalStatus { AVAILABLE, MISSING_HR, FROZEN_HR, MISSING_STEPS }

    data class Reading(
        val timestampMs: Long,
        val durationMs: Long,
        val bpm: Double,
        val valid: Boolean = true,
    )

    data class Classification(
        val state: ExerciseState,
        val zone: Zone,
        val averageBpm: Double?,
        val hrrPercent: Double?,
        val confidence: Confidence,
        val status: SignalStatus,
    )

    private const val FROZEN_MIN_READINGS = 4
    private const val HIGH_STEPS = 300
    private const val MODERATE_STEPS = 100
    private const val LOW_STEPS = 30

    /**
     * Valid sample boundary matches legacy: timestamp > cutoff and <= now.
     * Invalid duration/non-finite BPM is missing, not a 0 BPM measurement.
     */
    private fun inWindow(readings: List<Reading>, nowMs: Long, windowMinutes: Int): List<Reading> {
        if (nowMs < 0L || windowMinutes <= 0 || windowMinutes > 24 * 60) return emptyList()
        val windowMs = windowMinutes.toLong() * 60_000L
        val cutoff = nowMs - windowMs
        return readings.filter {
            it.valid && it.timestampMs > cutoff && it.timestampMs <= nowMs &&
                it.durationMs > 0L && it.bpm.isFinite() && it.bpm > 0.0
        }
    }

    fun weightedAverage(readings: List<Reading>, nowMs: Long, windowMinutes: Int): Double? {
        val samples = inWindow(readings, nowMs, windowMinutes)
        if (samples.isEmpty()) return null
        val duration = samples.sumOf { it.durationMs.toDouble() }
        if (!duration.isFinite() || duration <= 0.0) return null
        val numerator = samples.sumOf { it.bpm * it.durationMs.toDouble() }
        val average = numerator / duration
        return average.takeIf { it.isFinite() }
    }

    fun frozen(readings: List<Reading>, nowMs: Long, windowMinutes: Int): Boolean {
        val samples = inWindow(readings, nowMs, windowMinutes)
        if (samples.size < FROZEN_MIN_READINGS) return false
        val first = samples.first().bpm
        return samples.all { it.bpm == first }
    }

    fun zoneFor(bpm: Double, hrMax: Int, hrResting: Int): Zone {
        if (!bpm.isFinite() || bpm <= 0.0 || hrMax <= 0 || hrResting <= 0) return Zone.NONE
        val reserve = (hrMax.toLong() - hrResting.toLong()).coerceAtLeast(1L).toDouble()
        val percent = (bpm - hrResting) / reserve * 100.0
        return when {
            percent < 30.0 -> Zone.VERY_LIGHT
            percent < 40.0 -> Zone.LIGHT
            percent < 60.0 -> Zone.MODERATE
            percent < 80.0 -> Zone.HARD
            else -> Zone.MAXIMUM
        }
    }

    private fun unavailable(status: SignalStatus) = Classification(
        state = ExerciseState.RESTING,
        zone = Zone.NONE,
        averageBpm = null,
        hrrPercent = null,
        confidence = Confidence.LOW,
        status = status,
    )

    fun classify(
        readings: List<Reading>,
        nowMs: Long,
        windowMinutes: Int,
        hrMax: Int,
        hrResting: Int,
        stepsLast15Min: Int?,
        stressDetection: Boolean = false,
    ): Classification {
        if (stepsLast15Min == null || stepsLast15Min < 0)
            return unavailable(SignalStatus.MISSING_STEPS)
        val average = weightedAverage(readings, nowMs, windowMinutes)
            ?: return unavailable(SignalStatus.MISSING_HR)
        if (frozen(readings, nowMs, windowMinutes))
            return unavailable(SignalStatus.FROZEN_HR)

        val zone = zoneFor(average, hrMax, hrResting)
        if (zone == Zone.NONE) return unavailable(SignalStatus.MISSING_HR)
        val reserve = (hrMax.toLong() - hrResting.toLong()).coerceAtLeast(1L).toDouble()
        val hrrPercent = (average - hrResting) / reserve * 100.0
        val high = stepsLast15Min >= HIGH_STEPS
        val moderate = stepsLast15Min >= MODERATE_STEPS
        val low = stepsLast15Min < LOW_STEPS

        // Match the legacy V1 branch precedence, including the 30-99 steps
        // resistance dead-zone regression fix. No results are wired to insulin.
        val (state, confidence) = when {
            high && zone >= Zone.MODERATE ->
                ExerciseState.VIGOROUS_AEROBIC to Confidence.HIGH
            moderate && zone >= Zone.LIGHT ->
                ExerciseState.MODERATE_AEROBIC to
                    if (high || zone >= Zone.MODERATE) Confidence.HIGH else Confidence.MEDIUM
            !low && zone <= Zone.LIGHT ->
                ExerciseState.LIGHT_AEROBIC to Confidence.MEDIUM
            !moderate && zone in Zone.MODERATE..Zone.HARD ->
                ExerciseState.RESISTANCE to Confidence.MEDIUM
            stressDetection && low && zone in Zone.LIGHT..Zone.MODERATE ->
                ExerciseState.STRESS to Confidence.LOW
            low && zone == Zone.VERY_LIGHT ->
                ExerciseState.INACTIVE to Confidence.HIGH
            else -> ExerciseState.RESTING to Confidence.MEDIUM
        }
        return Classification(state, zone, average, hrrPercent, confidence, SignalStatus.AVAILABLE)
    }
}
