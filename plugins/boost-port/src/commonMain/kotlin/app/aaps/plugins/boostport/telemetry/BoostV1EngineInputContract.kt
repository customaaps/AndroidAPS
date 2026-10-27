package app.aaps.plugins.boostport.telemetry

/**
 * KMP-compatible adaptation seam for the original
 * DetermineBasalBoost.determine_basal() argument contract.
 *
 * All values are caller-supplied offline snapshots: the real engine still
 * requires APS-specific GlucoseStatus, CurrentTemp, IobTotal,
 * OapsProfileBoost, AutosensResult, MealData, and RT interfaces.
 * This object cannot construct RT or deliver insulin.
 */
internal object BoostV1EngineInputContract {
    enum class Finding {
        READY_OFFLINE,
        INVALID_CLOCK,
        INVALID_GLUCOSE,
        STALE_GLUCOSE,
        INVALID_IOB,
        INVALID_PROFILE,
        INVALID_TEMP_BASAL,
        INVALID_MEAL,
        INVALID_AUTOSENS
    }
    data class Glucose(val timestampMs: Long, val mgDl: Double, val noise: Int)
    data class Profile(val basalUnitsPerHour: Double, val targetMgDl: Double, val maxIobUnits: Double)
    data class Iob(val units: Double, val activity: Double)
    data class CurrentTemp(val rateUnitsPerHour: Double, val durationMinutes: Int)
    data class Meal(val carbsGrams: Double, val cobGrams: Double)
    data class Inputs(
        val nowMs: Long,
        val glucose: Glucose,
        val profile: Profile,
        val iob: Iob,
        val temp: CurrentTemp,
        val meal: Meal,
        val autosensRatio: Double
    )
    data class Report(
        val finding: Finding,
        val glucoseAgeMinutes: Double? = null,
        val readyToCallRealEngine: Boolean = false,
        val canDeliverInsulin: Boolean = false
    )

    fun inspect(input: Inputs): Report {
        if (input.nowMs <= 0L || input.glucose.timestampMs <= 0L ||
            input.glucose.timestampMs > input.nowMs) return Report(Finding.INVALID_CLOCK)
        val g = input.glucose
        if (!g.mgDl.isFinite() || g.mgDl <= 10.0 || g.mgDl == 38.0 || g.noise >= 3 || g.noise < 0)
            return Report(Finding.INVALID_GLUCOSE)
        val ageMs = input.nowMs - g.timestampMs
        if (ageMs > 12L * 60_000L) return Report(Finding.STALE_GLUCOSE, ageMs / 60_000.0)
        val iob = input.iob
        if (!iob.units.isFinite() || !iob.activity.isFinite())
            return Report(Finding.INVALID_IOB)
        val p = input.profile
        if (!p.basalUnitsPerHour.isFinite() || p.basalUnitsPerHour < 0.0 ||
            !p.targetMgDl.isFinite() || p.targetMgDl <= 0.0 ||
            !p.maxIobUnits.isFinite() || p.maxIobUnits < 0.0)
            return Report(Finding.INVALID_PROFILE)
        if (!input.temp.rateUnitsPerHour.isFinite() || input.temp.rateUnitsPerHour < 0.0 ||
            input.temp.durationMinutes < 0) return Report(Finding.INVALID_TEMP_BASAL)
        if (!input.meal.carbsGrams.isFinite() || input.meal.carbsGrams < 0.0 ||
            !input.meal.cobGrams.isFinite() || input.meal.cobGrams < 0.0)
            return Report(Finding.INVALID_MEAL)
        if (!input.autosensRatio.isFinite() || input.autosensRatio <= 0.0)
            return Report(Finding.INVALID_AUTOSENS)
        return Report(Finding.READY_OFFLINE, ageMs / 60_000.0)
    }
}
