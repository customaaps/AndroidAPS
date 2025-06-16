package app.aaps.plugins.aps.openAPSSMB

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.LongSparseArray
import androidx.core.util.forEach
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.IntentKey
import app.aaps.core.keys.Preferences
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.objects.aps.DetermineBasalResult
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.getPassedDurationToTimeInMinutes
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.core.objects.extensions.target
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.utils.MidnightUtils
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveIntentPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.core.validators.preferences.AdaptiveUnitPreference
import app.aaps.plugins.aps.OpenAPSFragment
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.plugins.aps.isf.DynamicISF
import app.aaps.plugins.aps.GenericAPSPluginBase
import app.aaps.plugins.aps.openAPS.TddStatus
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor
import kotlin.math.ln

@Singleton
open class OpenAPSSMBPlugin @Inject constructor(
    private val injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    rxBus: RxBus,
    constraintsChecker: ConstraintsChecker,
    rh: ResourceHelper,
    profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    config: Config,
    activePlugin: ActivePlugin,
    iobCobCalculator: IobCobCalculator,
    hardLimits: HardLimits,
    preferences: Preferences,
    dateUtil: DateUtil,
    processedTbrEbData: ProcessedTbrEbData,
    persistenceLayer: PersistenceLayer,
    glucoseStatusProvider: GlucoseStatusProvider,
    private val bgQualityCheck: BgQualityCheck,
    uiInteraction: UiInteraction,
    profiler: Profiler,
    private val determineBasalSMB: DetermineBasalSMB,
    private val dynamicISF: DynamicISF,
) : GenericAPSPluginBase(
    aapsLogger, rh, config, persistenceLayer, dateUtil,
    preferences, uiInteraction, profiler, activePlugin,
    glucoseStatusProvider, hardLimits, rxBus, profileFunction,
    constraintsChecker, iobCobCalculator, processedTbrEbData
) {
    override val algorithm = APSResult.Algorithm.SMB

    override fun isVariableISFImplemented() = true
    override fun areSMBSupported() = true
    override fun isUAMImplemented() = true

    override fun calculateVariableISF(profile: Profile): DynamicISF.DynamicISFResult
        = dynamicISF.calculateVariableISF(profile)

    override fun getAutosensData(variableISFResult: VariableISFResult?): AutosensResult?
        = dynamicISF.getAutosensData(variableISFResult) ?: super.getAutosensData(variableISFResult)

    override fun invokeLazy(data: InvokeData) {
        // End of check, start gathering data
        val dynIsfResult = data.variableISFResult?.let { it as DynamicISF.DynamicISFResult }
        val dynIsfUsable = dynIsfResult?.isUsable() == true

        if (!dynIsfUsable && dynIsfResult?.nonTdd != true) {
            uiInteraction.addNotificationValidTo(
                Notification.SMB_FALLBACK, dateUtil.now(),
                rh.gs(R.string.fallback_smb_no_tdd), Notification.INFO, dateUtil.now() + T.mins(1).msecs()
            )
            data.inputConstraints.copyReasons(
                ConstraintObject(false, aapsLogger).also {
                    it.set(false, rh.gs(R.string.fallback_smb_no_tdd), this)
                }
            )
            data.inputConstraints.copyReasons(
                ConstraintObject(false, aapsLogger).apply {
                    set(true, "tdd1D=${dynIsfResult?.tdd1D} tdd7D=${dynIsfResult?.tdd7D} tddLast4H=${dynIsfResult?.tddLast4H} tddLast8to4H=${dynIsfResult?.tddLast8to4H} tddLast24H=${dynIsfResult?.tddLast24H}", this)
                }
            )
        }

        @Suppress("KotlinConstantConditions")
        val iobArray = iobCobCalculator.calculateIobArrayForSMB(
            data.autosensResult,
            SMBDefaults.exercise_mode,
            preferences.get(IntKey.ApsAutoIsfHalfBasalExerciseTarget),
            data.isTempTarget
        )
        val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()

        @Suppress("KotlinConstantConditions")
        val oapsProfile = OapsProfile(
            dia = 0.0, // not used
            min_5m_carbimpact = 0.0, // not used
            max_iob = constraintsChecker.getMaxIOBAllowed().also { data.inputConstraints.copyReasons(it) }.value(),
            max_daily_basal = data.profile.getMaxDailyBasal(),
            max_basal = constraintsChecker.getMaxBasalAllowed(data.profile).also { data.inputConstraints.copyReasons(it) }.value(),
            min_bg = data.minTarget,
            max_bg = data.maxTarget,
            target_bg = data.target,
            carb_ratio = data.profile.getIc(),
            sens = data.profile.getIsfMgdl("OpenAPSSMBPlugin"),
            autosens_adjust_targets = false, // not used
            max_daily_safety_multiplier = preferences.get(DoubleKey.ApsMaxDailyMultiplier),
            current_basal_safety_multiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier),
            lgsThreshold = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsLgsThreshold)).toInt(),
            high_temptarget_raises_sensitivity = preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens),
            low_temptarget_lowers_sensitivity = preferences.get(BooleanKey.ApsAutoIsfLowTtLowersSens),
            sensitivity_raises_target = preferences.get(BooleanKey.ApsSensitivityRaisesTarget),
            resistance_lowers_target = preferences.get(BooleanKey.ApsResistanceLowersTarget),
            adv_target_adjustments = SMBDefaults.adv_target_adjustments,
            exercise_mode = SMBDefaults.exercise_mode,
            half_basal_exercise_target = preferences.get(IntKey.ApsAutoIsfHalfBasalExerciseTarget),
            maxCOB = SMBDefaults.maxCOB,
            skip_neutral_temps = data.activePlugin.activePump.setNeutralTempAtFullHour(),
            remainingCarbsCap = SMBDefaults.remainingCarbsCap,
            enableUAM = constraintsChecker.isUAMEnabled().also { data.inputConstraints.copyReasons(it) }.value(),
            A52_risk_enable = SMBDefaults.A52_risk_enable,
            SMBInterval = preferences.get(IntKey.ApsMaxSmbFrequency),
            enableSMB_with_COB = data.isSMBEnabled && preferences.get(BooleanKey.ApsUseSmbWithCob),
            enableSMB_with_temptarget = data.isSMBEnabled && preferences.get(BooleanKey.ApsUseSmbWithLowTt),
            allowSMB_with_high_temptarget = data.isSMBEnabled && preferences.get(BooleanKey.ApsUseSmbWithHighTt),
            enableSMB_always = data.isSMBEnabled && preferences.get(BooleanKey.ApsUseSmbAlways) && data.isAdvancedFilteringSupported,
            enableSMB_after_carbs = data.isSMBEnabled && preferences.get(BooleanKey.ApsUseSmbAfterCarbs) && data.isAdvancedFilteringSupported,
            maxSMBBasalMinutes = preferences.get(IntKey.ApsMaxMinutesOfBasalToLimitSmb),
            maxUAMSMBBasalMinutes = preferences.get(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb),
            bolus_increment = activePlugin.activePump.pumpDescription.bolusStep,
            carbsReqThreshold = preferences.get(IntKey.ApsCarbsRequestThreshold),
            current_basal = activePlugin.activePump.baseBasalRate,
            temptargetSet = data.isTempTarget,
            autosens_max = preferences.get(DoubleKey.AutosensMax),
            out_units = if (profileFunction.getUnits() == GlucoseUnit.MMOL) "mmol/L" else "mg/dl",
            variable_sens = if (dynIsfUsable) dynIsfResult.isf!! else 0.0,
            insulinDivisor = if (dynIsfUsable) dynIsfResult.insulinDivisor else 0,
            TDD = dynIsfResult?.tdd ?: 0.0,
            use_TDD_for_predictions = dynIsfUsable && !dynIsfResult.nonTdd
        )
        val microBolusAllowed = constraintsChecker.isSMBModeEnabled(ConstraintObject(data.tempBasalFallback.not(), aapsLogger)).also { data.inputConstraints.copyReasons(it) }.value()
        val flatBGsDetected = bgQualityCheck.state == BgQualityCheck.State.FLAT

        aapsLogger.debug(LTag.APS, ">>> Invoking determine_basal SMB <<<")
        aapsLogger.debug(LTag.APS, "Glucose status:     ${data.glucoseStatus}")
        aapsLogger.debug(LTag.APS, "Current temp:       ${data.currentTemp}")
        aapsLogger.debug(LTag.APS, "IOB data:           ${iobArray.joinToString()}")
        aapsLogger.debug(LTag.APS, "Profile:            $oapsProfile")
        aapsLogger.debug(LTag.APS, "Autosens data:      ${data.autosensResult}")
        aapsLogger.debug(LTag.APS, "Meal data:          $mealData")
        aapsLogger.debug(LTag.APS, "MicroBolusAllowed:  $microBolusAllowed")
        aapsLogger.debug(LTag.APS, "flatBGsDetected:    $flatBGsDetected")
        aapsLogger.debug(LTag.APS, "DynIsfNonTDD:       ${preferences.get(BooleanKey.ApsDynIsfUseProfileSens)}")
        aapsLogger.debug(LTag.APS, "DynIsfUsable:       $dynIsfUsable")

        determineBasalSMB.determine_basal(
            glucose_status = data.glucoseStatus,
            currenttemp = data.currentTemp,
            iob_data_array = iobArray,
            profile = oapsProfile,
            autosens_data = data.autosensResult,
            meal_data = mealData,
            microBolusAllowed = microBolusAllowed,
            currentTime = data.now.millis,
            flatBGsDetected = flatBGsDetected,
            dynIsfMode = dynIsfUsable,
            smb_ratio = getUserSMBRatio(data.profile),
        ).also {
            val determineBasalResult = DetermineBasalResult(injector, it)
            // Preserve input data
            determineBasalResult.inputConstraints = data.inputConstraints
            determineBasalResult.autosensResult = data.autosensResult
            determineBasalResult.iobData = iobArray
            determineBasalResult.glucoseStatus = data.glucoseStatus
            determineBasalResult.currentTemp = data.currentTemp
            determineBasalResult.oapsProfile = oapsProfile
            determineBasalResult.mealData = mealData
            lastAPSResult = determineBasalResult
            lastAPSRun = data.now.millis
            aapsLogger.debug(LTag.APS, "Result: $it")
            rxBus.send(EventAPSCalculationFinished())
        }
        rxBus.send(EventOpenAPSUpdateGui())
    }

    override fun configuration(): JSONObject =
        JSONObject()
            .put(BooleanKey.ApsUseDynamicSensitivity, preferences)
            .put(IntKey.ApsDynIsfAdjustmentFactor, preferences)
            .put(IntKey.ApsDynIsfVelocity, preferences)
            .put(BooleanKey.ApsDynIsfUseProfileSens, preferences)

    override fun applyConfiguration(configuration: JSONObject) {
        configuration
            .store(BooleanKey.ApsUseDynamicSensitivity, preferences)
            .store(IntKey.ApsDynIsfAdjustmentFactor, preferences)
            .store(IntKey.ApsDynIsfVelocity, preferences)
            .store(BooleanKey.ApsDynIsfUseProfileSens, preferences)
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null && requiredKey != "dynisf_settings" && requiredKey !in GenericAPSPluginBase.SMB_SETTINGS_KEYS) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "openapssmb_settings"
            title = rh.gs(R.string.openapssmb)
            initialExpandedChildrenCount = 0
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsMaxBasal, dialogMessage = R.string.openapsma_max_basal_summary, title = R.string.openapsma_max_basal_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsSmbMaxIob, dialogMessage = R.string.openapssmb_max_iob_summary, title = R.string.openapssmb_max_iob_title))
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "dynisf_settings"
                title = rh.gs(R.string.dynisf_settings_title)
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseDynamicSensitivity, summary = R.string.use_dynamic_sensitivity_summary, title = R.string.use_dynamic_sensitivity_title))
                addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsDynIsfAdjustmentFactor, dialogMessage = R.string.dyn_isf_adjust_summary, title = R.string.dyn_isf_adjust_title))
                addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsDynIsfVelocity, dialogMessage = R.string.dynisf_velocity_summary, title = R.string.dynisf_velocity))
                addPreference(AdaptiveUnitPreference(ctx = context, unitKey = UnitDoubleKey.ApsDynIsfBgCap, dialogMessage = R.string.dynisf_bg_cap_summary, title = R.string.dynisf_bg_cap))
                addPreference(AdaptiveUnitPreference(ctx = context, unitKey = UnitDoubleKey.ApsLgsThreshold, dialogMessage = R.string.lgs_threshold_summary, title = R.string.lgs_threshold_title))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsDynIsfAdjustSensitivity, summary = R.string.dynisf_adjust_sensitivity_summary, title = R.string.dynisf_adjust_sensitivity))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsDynIsfUseProfileSens, summary = R.string.dynisf_use_profile_sens_summary, title = R.string.dynisf_use_profile_sens))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsDynIsfProfilePercentage, title = R.string.dynisf_use_profile_percentage))
            })
            addGenericSMBSettings(preferenceManager, category, context)
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseAutosens, title = R.string.openapsama_use_autosens))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSensitivityRaisesTarget, summary = R.string.sensitivity_raises_target_summary, title = R.string.sensitivity_raises_target_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsResistanceLowersTarget, summary = R.string.resistance_lowers_target_summary, title = R.string.resistance_lowers_target_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfHighTtRaisesSens, summary = R.string.high_temptarget_raises_sensitivity_summary, title = R.string.high_temptarget_raises_sensitivity_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfLowTtLowersSens, summary = R.string.low_temptarget_lowers_sensitivity_summary, title = R.string.low_temptarget_lowers_sensitivity_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsAutoIsfHalfBasalExerciseTarget, dialogMessage = R.string.half_basal_exercise_target_summary, title = R.string.half_basal_exercise_target_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsCarbsRequestThreshold, dialogMessage = R.string.carbs_req_threshold_summary, title = R.string.carbs_req_threshold))
        }
    }

    init {
        pluginDescription
            .pluginName(R.string.openapssmb)
            .shortName(app.aaps.core.ui.R.string.smb_shortname)
            .description(R.string.description_smb)
            .setDefault()
    }
}