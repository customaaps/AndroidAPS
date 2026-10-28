package app.aaps.plugins.boostport.aps

import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.plugins.aps.openAPSBoost.NightWindow
import app.aaps.plugins.aps.openAPSBoost.activity.SleepStateDetector

/** Source isNightModeActiveImpl policy over one captured cycle, in canonical mg/dL. */
internal object BoostNightModePolicy {
    data class Settings(
        val enabled: Boolean = false,
        val startOfDayMs: Long = 0L,
        val endOfDayMs: Long = 0L,
        val autoBySleep: Boolean = false,
        val disableWithCob: Boolean = false,
        val disableWithLowTempTarget: Boolean = false,
        val bgOffsetMgDl: Double = 0.0,
    )

    data class Context(
        val nowMs: Long,
        val dayStartMs: Long,
        val glucoseMgDl: Double?,
        val profileTargetMgDl: Double?,
        val temporaryTargetMgDl: Double?,
        val mealCob: Double,
        val sleepState: SleepStateDetector.SleepState,
        val lieInActive: Boolean = false,
    )

    fun isActive(settings: Settings, context: Context): Boolean {
        if (!settings.enabled) return false
        val bg = context.glucoseMgDl ?: return false
        val active = NightWindow.contains(context.nowMs,
            context.dayStartMs + settings.startOfDayMs, context.dayStartMs + settings.endOfDayMs)
        val sleepActive = settings.autoBySleep && context.sleepState != SleepStateDetector.SleepState.AWAKE
        if (!active && !sleepActive && !context.lieInActive) return false
        if (settings.disableWithCob && context.mealCob > 0.0) return false
        if (settings.disableWithLowTempTarget) {
            val profileTarget = context.profileTargetMgDl ?: return false
            context.temporaryTargetMgDl?.let {
                if (it.coerceIn(HardLimits.LIMIT_TEMP_TARGET_BG) < profileTarget) return false
            }
        }
        val profileTarget = context.profileTargetMgDl ?: return false
        return bg < profileTarget + settings.bgOffsetMgDl
    }
}
