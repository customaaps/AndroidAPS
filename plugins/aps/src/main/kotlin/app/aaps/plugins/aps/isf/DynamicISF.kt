package app.aaps.plugins.aps.isf

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.Preferences
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.objects.extensions.target
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.ui.R
import app.aaps.plugins.aps.GenericAPSPluginBase.VariableISFResult
import app.aaps.plugins.aps.openAPS.TddStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

@Singleton
class DynamicISF @Inject constructor(
    val profileUtil: ProfileUtil,
    val preferences: Preferences,
    val activePlugin: ActivePlugin,
    val glucoseStatusProvider: GlucoseStatusProvider,
    val tddCalculator: TddCalculator,
    val aapsLogger: AAPSLogger,
    val persistenceLayer: PersistenceLayer,
    val dateUtil: DateUtil,
    val hardLimits: HardLimits,
    val uiInteraction: UiInteraction,
) {
    class DynamicISFResult: VariableISFResult {

        override var isf: Double? = null
        override var message: String = "NOT_INIT"
        var nonTdd: Boolean = false
        var usingQuickTdd: Boolean = false

        override fun isUsable(): Boolean {
            if (!nonTdd && (tddPartsCalculated() || tddQuickCalculated())) return false
            return isf != null
        }

        var tdd1D: Double? = null
        var tdd7D: Double? = null
        var tddLast24H: Double? = null
        var tddLast4H: Double? = null
        var tddLast8to4H: Double? = null
        var tdd: Double? = null
        var tddLast24HCarbs = 0.0
        var tdd7DDataCarbs = 0.0
        var tdd7DAllDaysHaveCarbs = false
        var tddBasedIsf: Double? = null

        var insulinDivisor: Int = 0

        fun tddPartsCalculated() = tdd1D != null && tdd7D != null && tddLast24H != null && tddLast4H != null && tddLast8to4H != null
        fun tddQuickCalculated() = tddLast4H != null && tddLast8to4H != null

        fun log() =
            "DynIsfResult: tdd1D=$tdd1D tdd7D=$tdd7D tddLast24H=$tddLast24H tddLast4H=$tddLast4H tddLast8to4H=$tddLast8to4H tdd=$tdd isf=$isf insulinDivisor=$insulinDivisor tdd7DDataCarbs=$tdd7DDataCarbs tdd7DAllDaysHaveCarbs=$tdd7DAllDaysHaveCarbs"
    }

    fun calculateTDD(dynIsfResult: DynamicISFResult, profile: Profile) {
        dynIsfResult.nonTdd = preferences.get(BooleanKey.ApsDynIsfUseProfileSens)
        dynIsfResult.tdd1D = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = false))?.data?.totalAmount
        tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = false))?.let {
            dynIsfResult.tdd7D = it.data.totalAmount
            dynIsfResult.tdd7DDataCarbs = it.data.carbs
            dynIsfResult.tdd7DAllDaysHaveCarbs = it.allDaysHaveCarbs
        }
        tddCalculator.calculateDaily(-24, 0)?.also {
            dynIsfResult.tddLast24H = it.totalAmount
            dynIsfResult.tddLast24HCarbs = it.carbs
        }
        dynIsfResult.tddLast4H = tddCalculator.calculateDaily(-4, 0)?.totalAmount
        dynIsfResult.tddLast8to4H = tddCalculator.calculateDaily(-8, -4)?.totalAmount

        if (dynIsfResult.tddPartsCalculated()) {
            val tddStatus = TddStatus(dynIsfResult.tdd1D!!, dynIsfResult.tdd7D!!, dynIsfResult.tddLast24H!!, dynIsfResult.tddLast4H!!, dynIsfResult.tddLast8to4H!!)
            val tddWeightedFromLast8H = ((1.4 * tddStatus.tddLast4H) + (0.6 * tddStatus.tddLast8to4H)) * 3
            dynIsfResult.tdd = (tddWeightedFromLast8H * 0.33) + (tddStatus.tdd7D * 0.34) + (tddStatus.tdd1D * 0.33)
        } else if (dynIsfResult.tddQuickCalculated()) {
            aapsLogger.warn(LTag.APS, "Using quick TDD")
            dynIsfResult.usingQuickTdd = true
            dynIsfResult.tdd = ((1.4 * dynIsfResult.tddLast4H!!) + (0.6 * dynIsfResult.tddLast8to4H!!)) * 3
        }

        val adjFactor = preferences.get(IntKey.ApsDynIsfAdjustmentFactor) / 100.0
        if (dynIsfResult.tdd != null)
            dynIsfResult.tdd = dynIsfResult.tdd!! * adjFactor
        else {
            aapsLogger.error(LTag.APS, "No TDD for TDD-based ISF")
            //dynIsfResult.nonTdd = true
            return
        }

        val profileMultiplier = if (preferences.get(BooleanKey.ApsDynIsfProfilePercentage))
            100.0 / (profile as ProfileSealed.EPS).value.originalPercentage
        else
            1.0

        aapsLogger.debug(LTag.APS, "Using TDD base sensitivity")
        dynIsfResult.tddBasedIsf = Round.roundTo(1800.0 / (dynIsfResult.tdd!! * (ln((100.0 / dynIsfResult.insulinDivisor) + 1))), 0.1)
        if (preferences.get(BooleanKey.ApsDynIsfProfilePercentage)) {
            dynIsfResult.tddBasedIsf = dynIsfResult.tddBasedIsf?.times(profileMultiplier)
            aapsLogger.debug(LTag.APS, "Scaling TDD sensitivity by profile% - $profileMultiplier")
        }
    }

    fun calculateInsulinDivisor(dynIsfResult: DynamicISFResult) {
        val insulin = activePlugin.activeInsulin
        dynIsfResult.insulinDivisor = when {
            insulin.peak > 65 -> 55 // rapid peak: 75
            insulin.peak > 50 -> 65 // ultra rapid peak: 55
            else              -> 75 // lyumjev peak: 45
        }
    }

    fun calculateVariableISF(profile: Profile): DynamicISFResult {
        val dynIsfResult = DynamicISFResult()

        if (!hardLimits.checkHardLimits(preferences.get(IntKey.ApsDynIsfAdjustmentFactor).toDouble(), app.aaps.plugins.aps.R.string.dyn_isf_adjust_title, IntKey.ApsDynIsfAdjustmentFactor.min.toDouble(), IntKey.ApsDynIsfAdjustmentFactor.max.toDouble()) ||
            !hardLimits.checkHardLimits(preferences.get(IntKey.ApsDynIsfVelocity).toDouble(), app.aaps.plugins.aps.R.string.dynisf_velocity, IntKey.ApsDynIsfVelocity.min.toDouble(), IntKey.ApsDynIsfVelocity.max.toDouble())) {
            aapsLogger.error(LTag.APS, "DynamicISF fail - hard limits check failed")
            dynIsfResult.message = "LIMITS"
            return dynIsfResult
        }

        calculateInsulinDivisor(dynIsfResult)
        calculateTDD(dynIsfResult, profile)

        // DynamicISF specific
        // without these values DynISF doesn't work properly
        val bgCap = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsDynIsfBgCap))
        val glucose = glucoseStatusProvider.glucoseStatusData?.let {
            if (it.glucose > bgCap)
                bgCap + ((it.glucose - bgCap) / 3)
            else
                it.glucose
        }

        val normalTarget = 100.0
        var baseSensitivity = if (dynIsfResult.nonTdd) profile.getProfileIsfMgdl() else dynIsfResult.tddBasedIsf
        if (glucose == null) {
            aapsLogger.error(LTag.APS, "Glucose is null")
            dynIsfResult.message = "GLUCOSE"
            return dynIsfResult
        }

        if (baseSensitivity == null) {
            aapsLogger.error(LTag.APS, "TDD based sens is null")
            dynIsfResult.message = "No TDD"
            return dynIsfResult
        }

        // Scale base sensitivity by TT if needed
        var isTempTarget = false
        var targetBg = profile.getTargetMgdl()
        persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            isTempTarget = true
            targetBg = hardLimits.verifyHardLimits(tempTarget.target(), R.string.temp_target_value, HardLimits.Companion.LIMIT_TEMP_TARGET_BG[0], HardLimits.Companion.LIMIT_TEMP_TARGET_BG[1])
        }
        if (isTempTarget) {
            if ((preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens) && targetBg > normalTarget)
                || (preferences.get(BooleanKey.ApsAutoIsfLowTtLowersSens) && targetBg < normalTarget)) {
                val c = preferences.get(IntKey.ApsAutoIsfHalfBasalExerciseTarget) - normalTarget
                if (c * (c + targetBg - normalTarget) > 0.0) {
                    val sensitivityRatio = Round.roundTo((c / (c + targetBg - normalTarget)).apply {
                        coerceAtLeast(preferences.get(DoubleKey.AutosensMin))
                        coerceAtMost(preferences.get(DoubleKey.AutosensMax))
                    }, 0.01)
                    aapsLogger.debug(LTag.APS, "Scaling sensitivity by TT ratio: $sensitivityRatio")
                    baseSensitivity /= sensitivityRatio
                }
            }
        }

        // Calculate variable sensitivity
        val velocity = preferences.get(IntKey.ApsDynIsfVelocity) / 100.0
        val sbg = ln((glucose / dynIsfResult.insulinDivisor) + 1)
        val scaler = ln((normalTarget / dynIsfResult.insulinDivisor) + 1) / sbg
        val ratio = 1 - (1 - scaler) * velocity
        val isf = baseSensitivity * ratio

        if (!hardLimits.checkHardLimits(isf, app.aaps.plugins.aps.R.string.dynisf_settings_title, HardLimits.MIN_ISF, HardLimits.MAX_ISF)) {
            aapsLogger.error(LTag.APS, "DynamicISF fail - ISF hard limit check failed")
            dynIsfResult.message = "ISF_LIMITS"
            return dynIsfResult
        }

        dynIsfResult.isf = isf
        aapsLogger.debug(LTag.APS, "glucose=$glucose tdd=${dynIsfResult.tdd} baseSens=${baseSensitivity} velocity=$velocity nonTdd=${dynIsfResult.nonTdd} -> sensRatio=${ratio} sens=${dynIsfResult.isf}")
        return dynIsfResult
    }

    fun getAutosensData(variableISFResult: VariableISFResult?): AutosensResult? {
        val dynIsfResult = variableISFResult?.let { it as DynamicISFResult }
        val dynIsfUsable = dynIsfResult?.isUsable() == true
        if (dynIsfUsable && dynIsfResult.tddPartsCalculated()) {
            uiInteraction.dismissNotification(Notification.SMB_FALLBACK)
            val useTddSens = preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity)
            // Compare insulin consumption of last 24h with last 7 days average
            val tddRatio = if (useTddSens) dynIsfResult.tddLast24H!! / dynIsfResult.tdd7D!! else 1.0
            // Because consumed carbs affects total amount of insulin compensate final ratio by consumed carbs ratio
            // take only 60% (expecting 40% basal). We cannot use bolus/total because of SMBs
            val carbsRatio = if (
                useTddSens &&
                dynIsfResult.tddLast24HCarbs != 0.0 &&
                dynIsfResult.tdd7DDataCarbs != 0.0 &&
                dynIsfResult.tdd7DAllDaysHaveCarbs
            ) ((dynIsfResult.tddLast24HCarbs / dynIsfResult.tdd7DDataCarbs - 1.0) * 0.6) + 1.0 else 1.0
            return AutosensResult(
                ratio = tddRatio / carbsRatio,
                ratioFromTdd = tddRatio,
                ratioFromCarbs = carbsRatio
            )
        }
        return null
    }
}