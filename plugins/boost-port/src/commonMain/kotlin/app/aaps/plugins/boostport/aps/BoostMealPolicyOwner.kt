package app.aaps.plugins.boostport.aps

import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.plugins.aps.openAPSBoost.activity.MealTimeLearner
import app.aaps.plugins.aps.openAPSBoost.activity.SleepStateDetector
import app.aaps.plugins.aps.openAPSBoost.replay.BoostProfileContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Source pre-meal filtering, shadow/active target and confirmed-session learning. */
internal class BoostMealPolicyOwner(private val storage: KeyValueStore) {
    data class Settings(val enabled: Boolean, val targetMgdl: Double, val leadMinutes: Int,
        val nightStartMin: Int, val nightEndMin: Int)
    data class Decision(val context: BoostProfileContext, val reason: String?, val inNightWindow: Boolean)
    private var history: MealTimeLearner.History? = null

    private fun load(): MealTimeLearner.History {
        history?.let { return it }
        val raw = storage.getString(BoostApsSettingKeys.ApsBoostMealTimeHistory.key, "")
        val decoded = MealTimeLearner.History.deserialize(raw)
        if (raw.isNotEmpty()) {
            require(raw.isNotBlank() && raw.length <= 1_048_576) { "invalid Boost meal history" }
            val supplied = Json.parseToJsonElement(raw).jsonObject
            val canonical = Json.parseToJsonElement(decoded.serialize()).jsonObject
            require(supplied.all { (key, value) -> canonical[key] == value } && decoded.events.all { it > 0 }) {
                "corrupt or unsupported Boost meal history"
            }
        }
        return decoded.also { history = it }
    }

    fun prepare(settings: Settings, context: BoostProfileContext, offsetMs: Long,
        sleep: SleepStateDetector.SleepState, tempTargetActive: Boolean, recentLow45: Double): Decision {
        require(settings.targetMgdl.isFinite() && settings.targetMgdl in 65.0..90.0 && settings.leadMinutes in 30..90 &&
            settings.nightStartMin in 0..1439 && settings.nightEndMin in 0..1439)
        val minute = (((context.atMs + offsetMs) / 60_000L % 1440 + 1440) % 1440).toInt()
        val night = MealTimeLearner.inNightMinutes(minute, settings.nightStartMin, settings.nightEndMin)
        val old = load()
        val filtered = MealTimeLearner.withoutNightEvents(old, settings.nightStartMin, settings.nightEndMin, offsetMs)
        if (filtered.events.size != old.events.size) save(filtered)
        val hit = MealTimeLearner.preMealWindow(filtered, minute, offsetMs, settings.leadMinutes)
            ?: return Decision(context, null, night)
        val exercise = context.activityState in setOf("ACTIVE", "VIGOROUS_AEROBIC", "MODERATE_AEROBIC", "LIGHT_AEROBIC", "RESISTANCE", "STRESS")
        val recovery = context.recoveryEnabled && context.atMs < context.recoveryEndMs
        if (exercise || recovery) return Decision(context,
            "V6 pre-meal SUPPRESSED (${if (exercise) "exercise" else "recovery"}); ", night)
        val blocked = when {
            night -> "night window"
            sleep != SleepStateDetector.SleepState.AWAKE -> "asleep"
            tempTargetActive -> "temp target"
            recentLow45 < 75.0 -> "post-rescue"
            else -> null
        }
        if (blocked != null) return Decision(context, "V6 pre-meal SUPPRESSED ($blocked); ", night)
        val clock = (hit.mode.centreMin / 60).toString().padStart(2, '0') + ":" +
            (hit.mode.centreMin % 60).toString().padStart(2, '0')
        val target = settings.targetMgdl
        if (!settings.enabled) return Decision(context,
            "V6 pre-meal WOULD apply ${target.toInt()} (learned ~$clock, ${hit.minutesBeforeMeal}min before, ${hit.mode.distinctDays}d); ", night)
        if (target >= context.targetBg) return Decision(context,
            "V6 pre-meal skipped (target ${target.toInt()} ≥ current ${context.targetBg.toInt()}); ", night)
        return Decision(context.copy(minBg = minOf(context.minBg, target), maxBg = minOf(context.maxBg, target), targetBg = target),
            "V6 pre-meal ACTIVE target=${target.toInt()} (learned ~$clock, ${hit.minutesBeforeMeal}min before, ${hit.mode.distinctDays}d); ", night)
    }

    fun completeCycle(atMs: Long, mealSessionStarted: Boolean, inNightWindow: Boolean,
        previousSleep: SleepStateDetector.SleepState) {
        if (mealSessionStarted && !inNightWindow && previousSleep == SleepStateDetector.SleepState.AWAKE)
            save(MealTimeLearner.record(load(), atMs))
    }
    private fun save(value: MealTimeLearner.History) {
        storage.putString(BoostApsSettingKeys.ApsBoostMealTimeHistory.key, value.serialize())
        history = value
    }
}
