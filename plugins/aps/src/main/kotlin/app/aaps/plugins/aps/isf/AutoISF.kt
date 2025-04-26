package app.aaps.plugins.aps.isf

import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.OapsProfileAutoIsf
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.Preferences
import app.aaps.core.objects.extensions.target
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.plugins.aps.GenericAPSPluginBase
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.div
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.times

@Singleton
class AutoISF @Inject constructor(
    val profileUtil: ProfileUtil,
    val preferences: Preferences,
    val activePlugin: ActivePlugin,
    val glucoseStatusProvider: GlucoseStatusProvider,
    val aapsLogger: AAPSLogger,
    val persistenceLayer: PersistenceLayer,
    val dateUtil: DateUtil,
    val hardLimits: HardLimits,
    val uiInteraction: UiInteraction,
    val iobCobCalculator: IobCobCalculator,
    val constraintsChecker: ConstraintsChecker,
    val profileFunction: ProfileFunction,
) {
    companion object {
        const val VERSION = "3.0.1"
    }

    class AutoISFResult: GenericAPSPluginBase.VariableISFResult {
        override var isf: Double? = null
        override var message: String = "NOT_INIT"
        override fun isUsable(): Boolean = isf != null

        var consoleError = mutableListOf<String>()
        var consoleLog = mutableListOf<String>()
    }

    private val autoIsfWeights get() = preferences.get(BooleanKey.ApsUseAutoIsfWeights)
    private val normalTarget = 100.0

    private val consoleError = mutableListOf<String>()
    private val consoleLog = mutableListOf<String>()

    // Rounds value to 'digits' decimal places
    // different for negative numbers fun round(value: Double, digits: Int): Double = BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
    private fun round(value: Double, digits: Int): Double {
        if (value.isNaN()) return Double.NaN
        val scale = 10.0.pow(digits.toDouble())
        return Math.round(value * scale) / scale
    }

    private fun convert_bg(value: Double): String =
        profileUtil.fromMgdlToStringInUnits(value).replace("-0.0", "0.0")

    private fun convert_bg_to_units(value: Double, profile: OapsProfileAutoIsf): Double =
        if (profile.out_units == "mmol/L") value * Constants.MGDL_TO_MMOLL else value

    private fun autoISF(profile: Profile): Pair<String, Double?> {
        val sens = profile.getProfileIsfMgdl()
        val glucose_status = glucoseStatusProvider.glucoseStatusData

        val high_temptarget_raises_sensitivity = preferences.get(BooleanKey.ApsAutoIsfExerciseMode) || preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens)
        var target_bg = hardLimits.verifyHardLimits(profile.getTargetMgdl(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TARGET_BG[0], HardLimits.LIMIT_TARGET_BG[1])
        var isTempTarget = false
        persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            isTempTarget = true
            target_bg = hardLimits.verifyHardLimits(tempTarget.target(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TEMP_TARGET_BG[0], HardLimits.LIMIT_TEMP_TARGET_BG[1])
        }

        var sensitivityRatio: Double
        var origin_sens = ""
        val low_temptarget_lowers_sensitivity = preferences.get(BooleanKey.ApsAutoIsfLowTtLowersSens)
        if (high_temptarget_raises_sensitivity && isTempTarget && target_bg > normalTarget
            || low_temptarget_lowers_sensitivity && isTempTarget && target_bg < normalTarget
        ) {
            // w/ target 100, temp target 110 = .89, 120 = 0.8, 140 = 0.67, 160 = .57, and 200 = .44
            // e.g.: Sensitivity ratio set to 0.8 based on temp target of 120; Adjusting basal from 1.65 to 1.35; ISF from 58.9 to 73.6
            //sensitivityRatio = 2/(2+(target_bg-normalTarget)/40);
            val halfBasalTarget = preferences.get(IntKey.ApsAutoIsfHalfBasalExerciseTarget)
            val c = (halfBasalTarget - normalTarget).toDouble()
            if (c * (c + target_bg - normalTarget) <= 0.0) {
                sensitivityRatio = preferences.get(DoubleKey.AutosensMax)
                // consoleError.add("Sensitivity decrease for temp target of $target_bg limited by Autosens_max; ")

            } else {
                sensitivityRatio = c / (c + target_bg - normalTarget)
                // limit sensitivityRatio to profile.autosens_max (1.2x by default)
                sensitivityRatio = min(sensitivityRatio, preferences.get(DoubleKey.AutosensMax))
                sensitivityRatio = round(sensitivityRatio, 2)
                origin_sens = " from low TT modifier"
                // consoleError.add("Sensitivity ratio set to $sensitivityRatio based on temp target of $target_bg; ")
            }
        } else {
            var autosensResult = AutosensResult()

            if (constraintsChecker.isAutosensModeEnabled().value()) {
                iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish("OpenAPSAutoISFPlugin")?.also {
                    autosensResult = it.autosensResult
                }
            } else autosensResult.sensResult = "autosens disabled"
            sensitivityRatio = autosensResult.ratio
            // consoleError.add("Autosens ratio: $sensitivityRatio; ")
        }
        if (!preferences.get(BooleanKey.ApsUseDynamicSensitivity) || !autoIsfWeights) {
            consoleError.add("autoISF weights disabled in Preferences")
            consoleError.add("----------------------------------")
            consoleError.add("end AutoISF")
            consoleError.add("----------------------------------")
            return Pair("DISABLED", null)
        }

        if (glucose_status == null) {
            consoleError.add("Glucose is null")
            consoleError.add("----------------------------------")
            consoleError.add("end AutoISF")
            consoleError.add("----------------------------------")
            return Pair("GLUCOSE", null)
        }

        val dura05: Double = glucose_status.duraISFminutes
        val avg05: Double = glucose_status.duraISFaverage
        val maxISFReduction: Double = preferences.get(DoubleKey.ApsAutoIsfMax)
        var sens_modified = false
        var pp_ISF = 1.0
        var acce_ISF = 1.0
        var acce_weight = 1.0
        val bg_off = target_bg + 10.0 - glucose_status.glucose                      // move from central BG=100 to target+10 as virtual BG'=100

        // calculate acce_ISF from bg acceleration and adapt ISF accordingly
        val fit_corr: Double = glucose_status.corrSqu
        val bg_acce: Double = glucose_status.bgAcceleration
        consoleError.add("Parabola fit results were acceleration:${round(bg_acce, 2)}, correlation:$fit_corr, duration:${glucose_status.parabolaMinutes}m")
        if (glucose_status.a2 != 0.0 && fit_corr >= 0.9) {
            var minmax_delta: Double = -glucose_status.a1 / 2 / glucose_status.a2 * 5      // back from 5min block to 1 min
            var minmax_value: Double = round(glucose_status.a0 - minmax_delta * minmax_delta / 25 * glucose_status.a2, 1)
            minmax_delta = round(minmax_delta, 1)
            if (minmax_delta > 0 && bg_acce < 0) {
                consoleError.add("Parabolic fit extrapolates a maximum of ${convert_bg(minmax_value)} in about $minmax_delta minutes")
            } else if (minmax_delta > 0 && bg_acce > 0.0) {

                consoleError.add("Parabolic fit extrapolates a minimum of ${convert_bg(minmax_value)} in about $minmax_delta minutes")
                if (minmax_delta <= 30 && minmax_value < target_bg) {   // start braking
                    acce_weight = -preferences.get(DoubleKey.ApsAutoIsfBgBrakeWeight)
                    consoleError.add("extrapolation below target soon: use bgBrake_ISF_weight instead")
                }
            }
        }
        if (fit_corr < 0.9) {
            consoleError.add("acce_ISF adaptation by-passed as correlation ${round(fit_corr, 3)} is too low")
        } else {
            val fit_share = 10 * (fit_corr - 0.9)                            // 0 at correlation 0.9, 1 at 1.00
            var cap_weight = 1.0                                             // full contribution above target
            if (acce_weight == 1.0 && glucose_status.glucose < target_bg) {  // below target acce goes towards target
                if (bg_acce > 0) {
                    if (bg_acce > 1) {
                        cap_weight = 0.5
                    }            // halve the effect below target
                    acce_weight = preferences.get(DoubleKey.ApsAutoIsfBgBrakeWeight)
                } else if (bg_acce < 0) {
                    acce_weight = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
                }
            } else if (acce_weight == 1.0) {                                 // above target acce goes away from target
                if (bg_acce < 0.0) {
                    acce_weight = preferences.get(DoubleKey.ApsAutoIsfBgBrakeWeight)
                } else if (bg_acce > 0.0) {
                    acce_weight = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
                }
            }
            acce_ISF = 1.0 + bg_acce * cap_weight * acce_weight * fit_share
            consoleError.add("acce_ISF adaptation is ${round(acce_ISF, 2)}")
            if (acce_ISF != 1.0) {
                sens_modified = true
            }
        }

        val bg_ISF = 1 + interpolate(100 - bg_off)
        consoleError.add("bg_ISF adaptation is ${round(bg_ISF, 2)}")
        var liftISF: Double
        var final_ISF: Double
        if (bg_ISF < 1.0) {
            liftISF = min(bg_ISF, acce_ISF)
            if (acce_ISF > 1.0) {
                liftISF = bg_ISF * acce_ISF                                 // bg_ISF could become > 1 now
                consoleError.add("bg_ISF adaptation lifted to ${round(liftISF, 2)} as bg accelerates already")
            }
            final_ISF = withinISFlimits(liftISF, preferences.get(DoubleKey.ApsAutoIsfMin), maxISFReduction, sensitivityRatio, origin_sens, isTempTarget, high_temptarget_raises_sensitivity, target_bg)
            return Pair("EARLY", min(720.0, round(sens / final_ISF, 1)))
        } else if (bg_ISF > 1.0) {
            sens_modified = true
        }

        val bg_delta = glucose_status.delta
        val deltaType = "pp"
        when {
            bg_off > 0.0                     -> {
                consoleError.add(deltaType + "_ISF adaptation by-passed as average glucose < $target_bg+10")
            }

            glucose_status.shortAvgDelta < 0 -> {
                consoleError.add(deltaType + "_ISF adaptation by-passed as no rise or too short lived")
            }

            else                             -> {
                pp_ISF = 1.0 + max(0.0, bg_delta * preferences.get(DoubleKey.ApsAutoIsfPpWeight))
                consoleError.add("pp_ISF adaptation is ${round(pp_ISF, 2)}")
                if (pp_ISF != 1.0) {
                    sens_modified = true
                }

            }
        }

        var dura_ISF = 1.0
        val weightISF: Double = preferences.get(DoubleKey.ApsAutoIsfDuraWeight)
        when {
            dura05 < 10.0      -> {
                consoleError.add("dura_ISF by-passed; bg is only $dura05 m at level $avg05")
            }

            avg05 <= target_bg -> {
                consoleError.add("dura_ISF by-passed; avg. glucose $avg05 below target $target_bg")
            }

            else               -> {
                // fight the resistance at high levels
                val dura05Weight = dura05 / 60
                val avg05Weight = weightISF / target_bg
                dura_ISF += dura05Weight * avg05Weight * (avg05 - target_bg)
                sens_modified = true
                consoleError.add("dura_ISF adaptation is ${round(dura_ISF, 2)} because ISF ${round(sens, 1)} did not do it for ${round(dura05, 1)}m")
            }
        }
        if (sens_modified) {
            liftISF = max(dura_ISF, max(bg_ISF, max(acce_ISF, pp_ISF)))
            if (acce_ISF < 1.0) {
                consoleError.add("strongest autoISF factor ${round(liftISF, 2)} weakened to ${round(liftISF * acce_ISF, 2)} as bg decelerates already")
                liftISF = liftISF * acce_ISF
            }
            final_ISF = withinISFlimits(liftISF, preferences.get(DoubleKey.ApsAutoIsfMin), maxISFReduction, sensitivityRatio, origin_sens, isTempTarget, high_temptarget_raises_sensitivity, target_bg)
            return Pair("FULL", min(720.0, round(sens / final_ISF, 1)))
        }
        consoleError.add("----------------------------------")
        consoleError.add("end AutoISF")
        consoleError.add("----------------------------------")
        return Pair("PASS", round(sens / sensitivityRatio, 1))    // nothing changed
    }

    private fun interpolate(xdata: Double): Double {   // interpolate ISF behaviour based on polygons defining nonlinear functions defined by value pairs for ...
        //  ...         <----------------------  glucose  ---------------------->
        val polyX = arrayOf(50.0, 60.0, 80.0, 90.0, 100.0, 110.0, 150.0, 180.0, 200.0)
        val polyY = arrayOf(-0.5, -0.5, -0.3, -0.2, 0.0, 0.0, 0.5, 0.7, 0.7)
        val polymax: Int = polyX.size - 1
        var step = polyX[0]
        var sVal = polyY[0]
        var stepT = polyX[polymax]
        var sValold = polyY[polymax]

        var newVal = 1.0
        var lowVal = 1.0
        val topVal: Double
        val lowX: Double
        val topX: Double
        val myX: Double
        var lowLabl = step

        if (step > xdata) {
            // extrapolate backwards
            stepT = polyX[1]
            sValold = polyY[1]
            lowVal = sVal
            topVal = sValold
            lowX = step
            topX = stepT
            myX = xdata
            newVal = lowVal + (topVal - lowVal) / (topX - lowX) * (myX - lowX)
        } else if (stepT < xdata) {
            // extrapolate forwards
            step = polyX[polymax - 1]
            sVal = polyY[polymax - 1]
            lowVal = sVal
            topVal = sValold
            lowX = step
            topX = stepT
            myX = xdata
            newVal = lowVal + (topVal - lowVal) / (topX - lowX) * (myX - lowX)
        } else {
            // interpolate
            for (i: Int in 0..polymax) {
                step = polyX[i]
                sVal = polyY[i]
                if (step == xdata) {
                    newVal = sVal
                    break
                } else if (step > xdata) {
                    topVal = sVal
                    lowX = lowLabl
                    myX = xdata
                    topX = step
                    newVal = lowVal + (topVal - lowVal) / (topX - lowX) * (myX - lowX)
                    break
                }
                lowVal = sVal
                lowLabl = step
            }
        }
        newVal = if (xdata > 100) {
            newVal * preferences.get(DoubleKey.ApsAutoIsfHighBgWeight)
        } else {
            newVal * preferences.get(DoubleKey.ApsAutoIsfLowBgWeight)
        }
        return newVal
    }

    private fun withinISFlimits(
        liftISF: Double, minISFReduction: Double, maxISFReduction: Double, sensitivityRatio: Double, origin_sens: String, temptargetSet: Boolean,
        high_temptarget_raises_sensitivity: Boolean, target_bg: Double
    ): Double {
        var liftISFlimited: Double = liftISF
        if (liftISF < minISFReduction) {
            consoleError.add("weakest autoISF factor ${round(liftISF, 2)} limited by autoISF_min $minISFReduction")
            liftISFlimited = minISFReduction
        } else if (liftISF > maxISFReduction) {
            consoleError.add("strongest autoISF factor ${round(liftISF, 2)} limited by autoISF_max $maxISFReduction")
            liftISFlimited = maxISFReduction
        }
        val finalISF: Double
        var originSensFinal = origin_sens
        if (high_temptarget_raises_sensitivity && temptargetSet && target_bg > normalTarget) {
            finalISF = liftISFlimited * sensitivityRatio
            originSensFinal = " including exercise mode impact"
        } else if (liftISFlimited >= 1) {
            finalISF = max(liftISFlimited, sensitivityRatio)
            originSensFinal = if (liftISFlimited >= sensitivityRatio) "" else "from low TT modifier"
        } else {
            finalISF = min(liftISFlimited, sensitivityRatio)
            if (liftISFlimited <= sensitivityRatio) {
                originSensFinal = ""                                        // low TT lowers sensitivity dominates
            }
        }
        consoleError.add("final ISF factor is ${round(finalISF, 2)} " + originSensFinal)
        consoleError.add("----------------------------------")
        consoleError.add("end AutoISF")
        consoleError.add("----------------------------------")
        return finalISF
    }

    fun calculateVariableISF(profile: Profile): AutoISFResult {
        consoleError.clear()
        consoleLog.clear()

        val result = autoISF(profile)
        return AutoISFResult().also {
            it.isf = result.second
            it.message = result.first
            it.consoleError = consoleError
            it.consoleLog = consoleLog
        }
    }

    fun loop_smb(microBolusAllowed: Boolean, iob: Double, maxIob: Double, data: GenericAPSPluginBase.InvokeData): String {
        var exerciseRatio = 1.0
        // TODO eliminate
        if ((preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens) || preferences.get(BooleanKey.ApsAutoIsfExerciseMode)) && data.isTempTarget && data.target > normalTarget
            || preferences.get(BooleanKey.ApsAutoIsfLowTtLowersSens) && data.isTempTarget && data.target < normalTarget
        ) {
            // w/ target 100, temp target 110 = .89, 120 = 0.8, 140 = 0.67, 160 = .57, and 200 = .44
            // e.g.: Sensitivity ratio set to 0.8 based on temp target of 120; Adjusting basal from 1.65 to 1.35; ISF from 58.9 to 73.6
            //sensitivityRatio = 2/(2+(target_bg-normalTarget)/40);
            val c = (preferences.get(IntKey.ApsAutoIsfHalfBasalExerciseTarget) - normalTarget).toDouble()
            if (c * (c + data.target - normalTarget) > 0.0) {
                var sensitivityRatio = c / (c + data.target - normalTarget)
                // limit sensitivityRatio to profile.autosens_max (1.2x by default)
                sensitivityRatio = min(sensitivityRatio, preferences.get(DoubleKey.AutosensMax))
                sensitivityRatio = round(sensitivityRatio, 2)
                exerciseRatio = sensitivityRatio
            }
        }
        val profilePercentage = if (data.profile is ProfileSealed.EPS) data.profile.value.originalPercentage else 100
        var iobTH_reduction_ratio = 1.0
        var use_iobTH = false
        if (preferences.get(IntKey.ApsAutoIsfIobThPercent) != 100) {
            iobTH_reduction_ratio = profilePercentage / 100.0 * exerciseRatio
            use_iobTH = true
        }
        val iobTHtolerance = 130.0
        val iobTHvirtual = preferences.get(IntKey.ApsAutoIsfIobThPercent) * iobTHtolerance / 10000.0 * maxIob * iobTH_reduction_ratio
        val iobThEffective = iobTHvirtual / iobTHtolerance * 100.0

        val iobThUser = preferences.get(IntKey.ApsAutoIsfIobThPercent)  //iobThresholdPercent
        if (use_iobTH) {
            val iobThPercent = round(iobThEffective / maxIob * 100.0, 0)
            if (iobThPercent == iobThUser.toDouble()) {
                consoleLog.add("User setting iobTH=$iobThUser% not modulated")
            } else {
                consoleLog.add("User setting iobTH=$iobThUser% modulated to ${iobThPercent.toInt()}% or ${round(iobThEffective, 2)}U")
                consoleLog.add("  due to profile %, exercise mode or similar")
            }
        } else {
            consoleLog.add("User setting iobTH=100% disables iobTH method")
        }

        if (!microBolusAllowed) {
            return "AAPS"                                                 // see message in enable_smb
        }
        if (preferences.get(BooleanKey.ApsAutoIsfSmbOnEvenTarget)) {
            var target = profileUtil.fromMgdlToUnits(data.target, profileFunction.getUnits())
            // val msgType: String
            val evenTarget: Boolean
            val msgUnits: String
            val msgTail: String
            if (profileFunction.getUnits() == GlucoseUnit.MMOL) {
                evenTarget = round(target * 10.0, 0).toInt() % 2 == 0
                target = round(target, 1)
                msgUnits = "has"
                msgTail = "decimal"
            } else {
                evenTarget = round(target, 0).toInt() % 2 == 0
                target = round(target, 0)
                msgUnits = "is"
                msgTail = "number"
            }
            val msgEven: String = if (evenTarget) "even" else "odd"

            if (!evenTarget) {
                consoleLog.add("SMB disabled; current target $target $msgUnits $msgEven $msgTail")
                consoleLog.add("Loop allows minimal power")
                return "blocked"
            } else if (maxIob == 0.0) {
                consoleLog.add("SMB disabled because of max_iob=0")
                return "blocked"
            } else if (use_iobTH && iobThEffective < iob) {
                consoleLog.add("SMB disabled by Full Loop logic: iob $iob is above effective iobTH $iobThEffective")
                consoleLog.add("Loop power level temporarily capped")
                return "iobTH"
            } else {
                consoleLog.add("SMB enabled; current target $target $msgUnits $msgEven $msgTail")
                return if (data.target < 100) {     // indirect assessment; later set it in GUI
                    consoleLog.add("Loop allows maximum power")
                    "fullLoop"                                      // even number
                } else {
                    consoleLog.add("Loop allows medium power")
                    "enforced"                                      // even number
                }
            }
        }
        consoleLog.add("Loop allows AAPS power level")
        return "AAPS"                                                      // leave it to standard AAPS
    }
}