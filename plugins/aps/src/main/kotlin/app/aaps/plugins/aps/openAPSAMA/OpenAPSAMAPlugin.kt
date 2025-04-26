package app.aaps.plugins.aps.openAPSAMA

import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntentKey
import app.aaps.core.keys.Preferences
import app.aaps.core.objects.aps.DetermineBasalResult
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveIntentPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.plugins.aps.GenericAPSPluginBase
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import androidx.core.net.toUri

@Singleton
class OpenAPSAMAPlugin @Inject constructor(
    private val injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    rxBus: RxBus,
    constraintsChecker: ConstraintsChecker,
    rh: ResourceHelper,
    config: Config,
    profileFunction: ProfileFunction,
    activePlugin: ActivePlugin,
    iobCobCalculator: IobCobCalculator,
    processedTbrEbData: ProcessedTbrEbData,
    hardLimits: HardLimits,
    dateUtil: DateUtil,
    persistenceLayer: PersistenceLayer,
    glucoseStatusProvider: GlucoseStatusProvider,
    preferences: Preferences,
    uiInteraction: UiInteraction,
    profiler: Profiler,
    private val determineBasalAMA: DetermineBasalAMA
) : GenericAPSPluginBase(
    PluginDescription()
    .pluginName(R.string.openapsama)
    .shortName(R.string.oaps_shortname)
    .description(R.string.description_ama)
    .setDefault(),
    aapsLogger, rh, config, persistenceLayer, dateUtil,
    preferences, uiInteraction, profiler, activePlugin,
    glucoseStatusProvider, hardLimits, rxBus, profileFunction,
    constraintsChecker, iobCobCalculator, processedTbrEbData
) {

    // last values
    override val algorithm = APSResult.Algorithm.AMA

    override fun invokeLazy(data: InvokeData) {
        val iobArray = iobCobCalculator.calculateIobArrayInDia(data.profile)
        val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()

        val oapsProfile = OapsProfile(
            dia = min(data.profile.dia, 3.0),
            min_5m_carbimpact = if (mealData.usedMinCarbsImpact > 0) mealData.usedMinCarbsImpact else preferences.get(DoubleKey.ApsAmaMin5MinCarbsImpact),
            max_iob = data.maxIob,
            max_daily_basal = data.maxDailyBasal,
            max_basal = data.maxBasal,
            min_bg = data.minTarget,
            max_bg = data.maxTarget,
            target_bg = data.target,
            carb_ratio = data.profile.getIc(),
            sens = data.profile.getIsfMgdl("OpenAPSAMAPlugin"),
            autosens_adjust_targets = preferences.get(BooleanKey.ApsAmaAutosensAdjustTargets),
            max_daily_safety_multiplier = preferences.get(DoubleKey.ApsMaxDailyMultiplier),
            current_basal_safety_multiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier),
            lgsThreshold = 0, // not used
            high_temptarget_raises_sensitivity = false, // not used
            low_temptarget_lowers_sensitivity = false, // not used
            sensitivity_raises_target = false, // not used
            resistance_lowers_target = false, // not used
            adv_target_adjustments = false, // not used
            exercise_mode = false, // not used
            half_basal_exercise_target = 0, // not used
            maxCOB = 0, // not used
            skip_neutral_temps = activePlugin.activePump.setNeutralTempAtFullHour(),
            remainingCarbsCap = 0, // not used
            enableUAM = false, // not used
            A52_risk_enable = SMBDefaults.A52_risk_enable,
            SMBInterval = 0, // not used
            enableSMB_with_COB = false, // not used
            enableSMB_with_temptarget = false, // not used
            allowSMB_with_high_temptarget = false, // not used
            enableSMB_always = false, // not used
            enableSMB_after_carbs = false, // not used
            maxSMBBasalMinutes = 0, // not used
            maxUAMSMBBasalMinutes = 0, // not used
            bolus_increment = activePlugin.activePump.pumpDescription.bolusStep, // not used
            carbsReqThreshold = 0, // not used
            current_basal = activePlugin.activePump.baseBasalRate,
            temptargetSet = data.isTempTarget,
            autosens_max = preferences.get(DoubleKey.AutosensMax), // not used
            out_units = if (profileFunction.getUnits() == GlucoseUnit.MMOL) "mmol/L" else "mg/dl",
            variable_sens = 0.0, // not used
            insulinDivisor = 0, // not used
            TDD = 0.0, // not used
            use_TDD_for_predictions = null // not used
        )

        aapsLogger.debug(LTag.APS, ">>> Invoking determine_basal AMA <<<")
        aapsLogger.debug(LTag.APS, "Glucose status:     ${data.glucoseStatus}")
        aapsLogger.debug(LTag.APS, "Current temp:       ${data.currentTemp}")
        aapsLogger.debug(LTag.APS, "IOB data:           ${iobArray.joinToString()}")
        aapsLogger.debug(LTag.APS, "Profile:            $oapsProfile")
        aapsLogger.debug(LTag.APS, "Autosens data:      ${data.autosensResult}")
        aapsLogger.debug(LTag.APS, "Meal data:          $mealData")

        determineBasalAMA.determine_basal(
            glucose_status = data.glucoseStatus,
            currenttemp = data.currentTemp,
            iob_data_array = iobArray,
            profile = oapsProfile,
            autosens_data = data.autosensResult,
            meal_data = mealData,
            currentTime = data.now.millis
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

    override fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
        if (isEnabled()) {
            val maxIobPref: Double = preferences.get(DoubleKey.ApsAmaMaxIob)
            maxIob.setIfSmaller(maxIobPref, rh.gs(R.string.limiting_iob, maxIobPref, rh.gs(R.string.maxvalueinpreferences)), this)
            maxIob.setIfSmaller(hardLimits.maxIobAMA(), rh.gs(R.string.limiting_iob, hardLimits.maxIobAMA(), rh.gs(R.string.hardlimit)), this)
        }
        return maxIob
    }

    // Needed only for dynamic ISF so far
    override fun configuration(): JSONObject = JSONObject()
    override fun applyConfiguration(configuration: JSONObject) {}

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null && requiredKey != "absorption_ama_advanced") return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "openapsma_settings"
            title = rh.gs(R.string.openapsama)
            initialExpandedChildrenCount = 0
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsMaxBasal, dialogMessage = R.string.openapsma_max_basal_summary, title = R.string.openapsma_max_basal_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAmaMaxIob, dialogMessage = R.string.openapsma_max_iob_summary, title = R.string.openapsma_max_iob_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseAutosens, title = R.string.openapsama_use_autosens))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAmaAutosensAdjustTargets, summary = R.string.openapsama_autosens_adjust_targets_summary, title = R.string.openapsama_autosens_adjust_targets))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAmaMin5MinCarbsImpact, dialogMessage = R.string.openapsama_min_5m_carb_impact_summary, title = R.string.openapsama_min_5m_carb_impact))
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "absorption_ama_advanced"
                title = rh.gs(app.aaps.core.ui.R.string.advanced_settings_title)
                addPreference(
                    AdaptiveIntentPreference(
                        ctx = context,
                        intentKey = IntentKey.ApsLinkToDocs,
                        intent = Intent().apply { action = Intent.ACTION_VIEW; data = rh.gs(R.string.openapsama_link_to_preference_json_doc).toUri() },
                        summary = R.string.openapsama_link_to_preference_json_doc_txt
                    )
                )
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAlwaysUseShortDeltas, summary = R.string.always_use_short_avg_summary, title = R.string.always_use_short_avg))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsMaxDailyMultiplier, dialogMessage = R.string.openapsama_max_daily_safety_multiplier_summary, title = R.string.openapsama_max_daily_safety_multiplier))
                addPreference(
                    AdaptiveDoublePreference(
                        ctx = context,
                        doubleKey = DoubleKey.ApsMaxCurrentBasalMultiplier,
                        dialogMessage = R.string.openapsama_current_basal_safety_multiplier_summary,
                        title = R.string.openapsama_current_basal_safety_multiplier
                    )
                )
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAmaBolusSnoozeDivisor, dialogMessage = R.string.openapsama_bolus_snooze_dia_divisor_summary, title = R.string.openapsama_bolus_snooze_dia_divisor))
            })
        }
    }
}