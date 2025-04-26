package app.aaps.plugins.aps.openAPSAutoISF

import android.content.Context
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.OapsProfileAutoIsf
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.Preferences
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.objects.aps.DetermineBasalResult
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.plugins.aps.GenericAPSPluginBase
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.isf.AutoISF
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class OpenAPSAutoISFPlugin @Inject constructor(
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
    private val determineBasalAutoISF: DetermineBasalAutoISF,
    profiler: Profiler,
    private val autoISF: AutoISF,
) : GenericAPSPluginBase(
    PluginDescription()
        .pluginName(R.string.openaps_auto_isf)
        .shortName(R.string.autoisf_shortname)
        .description(R.string.description_auto_isf)
        .setDefault(),
    aapsLogger, rh, config, persistenceLayer, dateUtil,
    preferences, uiInteraction, profiler, activePlugin,
    glucoseStatusProvider, hardLimits, rxBus, profileFunction,
    constraintsChecker, iobCobCalculator, processedTbrEbData
) {

    // last values
    override val algorithm = APSResult.Algorithm.AUTO_ISF

    override fun isVariableISFImplemented() = true
    override fun areSMBSupported() = true
    override fun isUAMImplemented() = true

    override fun calculateVariableISF(profile: Profile): AutoISF.AutoISFResult?
        = autoISF.calculateVariableISF(profile)

    override fun invokeLazy(data: InvokeData) {
        val autoIsfResult = data.variableISFResult?.let { it as AutoISF.AutoISFResult }
        val autoIsfUsable = autoIsfResult != null && autoIsfResult.isUsable()
        val sens = autoIsfResult?.isf ?: data.profile.getProfileIsfMgdl()

        val iobThresholdPercent = preferences.get(IntKey.ApsAutoIsfIobThPercent)

        val profilePercentage = if (data.profile is ProfileSealed.EPS) data.profile.value.originalPercentage else 100
        val iobArray = iobCobCalculator.calculateIobArrayForSMB(data.autosensResult, preferences.get(BooleanKey.ApsAutoIsfExerciseMode), preferences.get(IntKey.ApsAutoIsfHalfBasalExerciseTarget), data.isTempTarget)
        val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()
        val iobData = iobArray[0]

        val microBolusAllowed = constraintsChecker.isSMBModeEnabled(ConstraintObject(data.tempBasalFallback.not(), aapsLogger)).also { data.inputConstraints.copyReasons(it) }.value()

        val oapsProfile = OapsProfileAutoIsf(
            dia = 0.0, // not used
            min_5m_carbimpact = 0.0, // not used
            max_iob = data.maxIob,
            max_daily_basal = data.maxDailyBasal,
            max_basal = data.maxBasal,
            min_bg = data.minTarget,
            max_bg = data.maxTarget,
            target_bg = data.target,
            carb_ratio = data.profile.getIc(),
            sens = sens,
            autosens_adjust_targets = false, // not used
            max_daily_safety_multiplier = preferences.get(DoubleKey.ApsMaxDailyMultiplier),
            current_basal_safety_multiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier),
            lgsThreshold = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsLgsThreshold)).toInt(),
            high_temptarget_raises_sensitivity = preferences.get(BooleanKey.ApsAutoIsfExerciseMode) || preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens), //was false,
            low_temptarget_lowers_sensitivity = preferences.get(BooleanKey.ApsAutoIsfLowTtLowersSens), // was false,
            sensitivity_raises_target = preferences.get(BooleanKey.ApsSensitivityRaisesTarget),
            resistance_lowers_target = preferences.get(BooleanKey.ApsResistanceLowersTarget),
            adv_target_adjustments = SMBDefaults.adv_target_adjustments,
            exercise_mode = preferences.get(BooleanKey.ApsAutoIsfExerciseMode),
            half_basal_exercise_target = preferences.get(IntKey.ApsAutoIsfHalfBasalExerciseTarget),
            maxCOB = SMBDefaults.maxCOB,
            skip_neutral_temps = activePlugin.activePump.setNeutralTempAtFullHour(),
            remainingCarbsCap = SMBDefaults.remainingCarbsCap,
            enableUAM = data.uamAllowed,
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
            variable_sens = sens,
            autoISF_version = AutoISF.VERSION,
            enable_autoISF = autoIsfUsable,
            autoISF_max = preferences.get(DoubleKey.ApsAutoIsfMax),
            autoISF_min = preferences.get(DoubleKey.ApsAutoIsfMin),
            bgAccel_ISF_weight = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight),
            bgBrake_ISF_weight = preferences.get(DoubleKey.ApsAutoIsfBgBrakeWeight),
            pp_ISF_weight = preferences.get(DoubleKey.ApsAutoIsfPpWeight),
            lower_ISFrange_weight = preferences.get(DoubleKey.ApsAutoIsfLowBgWeight),
            higher_ISFrange_weight = preferences.get(DoubleKey.ApsAutoIsfHighBgWeight),
            dura_ISF_weight = preferences.get(DoubleKey.ApsAutoIsfDuraWeight),
            smb_delivery_ratio = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatio),
            smb_delivery_ratio_min = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioMin),
            smb_delivery_ratio_max = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioMax),
            smb_delivery_ratio_bg_range = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange),   //smb_delivery_ratio_bg_range was always in mg/dL
            smb_max_range_extension = 1.0,
            enableSMB_EvenOn_OddOff_always = preferences.get(BooleanKey.ApsAutoIsfSmbOnEvenTarget),
            iob_threshold_percent = preferences.get(IntKey.ApsAutoIsfIobThPercent),
            profile_percentage = profilePercentage
        )
        //done calculate exercise ratio
        val loopWantedSmb = autoISF.loop_smb(microBolusAllowed, iobData.iob, data.maxIob, data)
        val flatBGsDetected = bgQualityCheck.state == BgQualityCheck.State.FLAT

        aapsLogger.debug(LTag.APS, ">>> Invoking determine_basal AutoISF <<<")
        aapsLogger.debug(LTag.APS, "Glucose status:     ${data.glucoseStatus}")
        aapsLogger.debug(LTag.APS, "Current temp:       ${data.currentTemp}")
        aapsLogger.debug(LTag.APS, "IOB data:           ${iobArray.joinToString()}")
        aapsLogger.debug(LTag.APS, "Profile:            $oapsProfile")
        aapsLogger.debug(LTag.APS, "Autosens data:      ${data.autosensResult}")
        aapsLogger.debug(LTag.APS, "Meal data:          $mealData")
        aapsLogger.debug(LTag.APS, "MicroBolusAllowed:  $microBolusAllowed")
        aapsLogger.debug(LTag.APS, "flatBGsDetected:    $flatBGsDetected")
        aapsLogger.debug(LTag.APS, "AutoIsfUsable:      $autoIsfUsable")
        //aapsLogger.debug(LTag.APS, "AutoISF extras:     ${Json.encodeToString(OapsProfile.serializer(), oapsProfile)}")

        determineBasalAutoISF.determine_basal(
            glucose_status = data.glucoseStatus,
            currenttemp = data.currentTemp,
            iob_data_array = iobArray,
            profile = oapsProfile,
            autosens_data = data.autosensResult,
            meal_data = mealData,
            microBolusAllowed = data.smbAllowed,
            currentTime = data.now.millis,
            flatBGsDetected = flatBGsDetected,
            autoIsfMode = autoIsfUsable,
            loop_wanted_smb = loopWantedSmb,
            profile_percentage = profilePercentage,
            smb_ratio = getUserSMBRatio(data.profile),
            smb_max_range_extension = 1.0,
            iob_threshold_percent = iobThresholdPercent,
            auto_isf_consoleError = autoIsfResult?.consoleError ?: mutableListOf(),
            auto_isf_consoleLog = autoIsfResult?.consoleLog ?: mutableListOf(),
        ).also {
            val determineBasalResult = DetermineBasalResult(injector, it)
            // Preserve input data
            determineBasalResult.inputConstraints = data.inputConstraints
            determineBasalResult.autosensResult = data.autosensResult
            determineBasalResult.iobData = iobArray
            determineBasalResult.glucoseStatus = data.glucoseStatus
            determineBasalResult.currentTemp = data.currentTemp
            determineBasalResult.oapsProfileAutoIsf = oapsProfile
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

    override fun applyConfiguration(configuration: JSONObject) {
        configuration
            .store(BooleanKey.ApsUseDynamicSensitivity, preferences)
            .store(IntKey.ApsDynIsfAdjustmentFactor, preferences)
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null && requiredKey != "auto_isf_settings" && requiredKey !in SMB_SETTINGS_KEYS) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "openapsautoisf_settings"
            title = rh.gs(R.string.openaps_auto_isf)
            initialExpandedChildrenCount = 0
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsMaxBasal, dialogMessage = R.string.openapsma_max_basal_summary, title = R.string.openapsma_max_basal_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsSmbMaxIob, dialogMessage = R.string.openapssmb_max_iob_summary, title = R.string.openapssmb_max_iob_title))
            addGenericSMBSettings(preferenceManager, category, context)
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "auto_isf_settings"
                title = rh.gs(R.string.autoISF_settings_title)
                summary = rh.gs(R.string.autoISF_settings_summary)
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseAutoIsfWeights, summary = R.string.openapsama_enable_autoISF, title = R.string.openapsama_enable_autoISF))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfMin, dialogMessage = R.string.openapsama_autoISF_min_summary, title = R.string.openapsama_autoISF_min))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfMax, dialogMessage = R.string.openapsama_autoISF_max_summary, title = R.string.openapsama_autoISF_max))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfBgAccelWeight, dialogMessage = R.string.openapsama_bgAccel_ISF_weight_summary, title = R.string.openapsama_bgAccel_ISF_weight))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfBgBrakeWeight, dialogMessage = R.string.openapsama_bgBrake_ISF_weight_summary, title = R.string.openapsama_bgBrake_ISF_weight))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfLowBgWeight, dialogMessage = R.string.openapsama_lower_ISFrange_weight_summary, title = R.string.openapsama_lower_ISFrange_weight))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfHighBgWeight, dialogMessage = R.string.openapsama_higher_ISFrange_weight_summary, title = R.string.openapsama_higher_ISFrange_weight))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfPpWeight, dialogMessage = R.string.openapsama_pp_ISF_weight_summary, title = R.string.openapsama_pp_ISF_weight))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfDuraWeight, dialogMessage = R.string.openapsama_dura_ISF_weight_summary, title = R.string.openapsama_dura_ISF_weight))
                addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsAutoIsfIobThPercent, dialogMessage = R.string.openapsama_iob_threshold_percent_summary, title = R.string.openapsama_iob_threshold_percent))
            })
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseAutosens, title = R.string.openapsama_use_autosens))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSensitivityRaisesTarget, summary = R.string.sensitivity_raises_target_summary, title = R.string.sensitivity_raises_target_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsResistanceLowersTarget, summary = R.string.resistance_lowers_target_summary, title = R.string.resistance_lowers_target_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfHighTtRaisesSens, summary = R.string.high_temptarget_raises_sensitivity_summary, title = R.string.high_temptarget_raises_sensitivity_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfLowTtLowersSens, summary = R.string.low_temptarget_lowers_sensitivity_summary, title = R.string.low_temptarget_lowers_sensitivity_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsAutoIsfHalfBasalExerciseTarget, dialogMessage = R.string.half_basal_exercise_target_summary, title = R.string.half_basal_exercise_target_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsCarbsRequestThreshold, dialogMessage = R.string.carbs_req_threshold_summary, title = R.string.carbs_req_threshold))
        }
    }
}