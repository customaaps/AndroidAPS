package app.aaps.plugins.aps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.LongSparseArray
import androidx.core.util.forEach
import androidx.core.util.size
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreference
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
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
import app.aaps.core.objects.extensions.target
import app.aaps.core.utils.MidnightUtils
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveIntentPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.core.validators.preferences.AdaptiveUnitPreference
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import org.joda.time.DateTime
import kotlin.math.floor
import androidx.core.net.toUri

abstract class GenericAPSPluginBase(
    pluginDescription: PluginDescription,
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    val config: Config,
    val persistenceLayer: PersistenceLayer,
    val dateUtil: DateUtil,
    val preferences: Preferences,
    val uiInteraction: UiInteraction,
    val profiler: Profiler,
    val activePlugin: ActivePlugin,
    val glucoseStatusProvider: GlucoseStatusProvider,
    val hardLimits: HardLimits,
    val rxBus: RxBus,
    val profileFunction: ProfileFunction,
    val constraintsChecker: ConstraintsChecker,
    val iobCobCalculator: IobCobCalculator,
    val processedTbrEbData: ProcessedTbrEbData,
): PluginBase(
    pluginDescription
        .mainType(PluginType.APS)
        .fragmentClass(OpenAPSFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_generic_icon)
        .preferencesId(PluginDescription.Companion.PREFERENCE_SCREEN)
        .preferencesVisibleInSimpleMode(false)
        .showInList { config.APS },
    aapsLogger, rh
), APS, PluginConstraints {

    companion object {
        val SMB_SETTINGS_KEYS = arrayOf("smb_settings", "absorption_smb_advanced", "smb_delivery_settings")
    }

    interface VariableISFResult {
        val isf: Double?
        val message: String

        fun isUsable(): Boolean
    }

    class InvokeData(
        val profile: Profile,
        val activePlugin: ActivePlugin,
        val glucoseStatus: GlucoseStatus,
        val inputConstraints: ConstraintObject<Double>,
        val initiator: String,
        val tempBasalFallback: Boolean,
        val variableISFResult: VariableISFResult?,
        val target: Double,
        val minTarget: Double,
        val maxTarget: Double,
        val isTempTarget: Boolean,
        val isSMBEnabled: Boolean,
        val isUAMEnabled: Boolean,
        val isAdvancedFilteringSupported: Boolean,
        val autosensResult: AutosensResult,
        val currentTemp: CurrentTemp,
        val maxIob: Double,
        val maxBasal: Double,
        val maxDailyBasal: Double,
        val smbAllowed: Boolean,
        val uamAllowed: Boolean,
    ) {
        val now: DateTime = DateTime.now()
    }

    /// Lazy invoke() wrapper
    abstract fun invokeLazy(data: InvokeData)

    /// Is any of variable ISF algorithms implemented by this APS?
    open fun isVariableISFImplemented(): Boolean = false

    /// Is there SMB functionality implemented? Required for settings preprocessing.
    open fun areSMBSupported(): Boolean = false

    /// Is there UAM functionality implemented?
    open fun isUAMImplemented(): Boolean = false

    /// Calculate variable ISF. If implemented.
    open fun calculateVariableISF(profile: Profile): VariableISFResult? = null

    open fun getAutosensData(variableISFResult: VariableISFResult?): AutosensResult? {
        if (!constraintsChecker.isAutosensModeEnabled().value()) {
            return AutosensResult().also {
                it.sensResult = "autosens disabled"
            }
        }

        val autosensData = iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish("OpenAPSPlugin")
        if (autosensData == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openaps_no_as_data)))
            return null
        }

        return autosensData.autosensResult
    }

    fun addGenericSMBSettings(preferenceManager: PreferenceManager, category: PreferenceCategory, context: Context) {
        category.apply {
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "smb_settings"
                title = rh.gs(app.aaps.core.ui.R.string.smb_shortname)
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmb, summary = R.string.enable_smb_summary, title = R.string.enable_smb))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.EnableSmbBgThreshold, title = R.string.enable_smb_bg_threshold))
                addPreference(AdaptiveUnitPreference(ctx = context, unitKey = UnitDoubleKey.SmbBgThreshold, title = R.string.smb_bg_threshold_title, dialogMessage = R.string.smb_bg_threshold_summary))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbWithHighTt, summary = R.string.enable_smb_with_high_temp_target_summary, title = R.string.enable_smb_with_high_temp_target))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbAlways, summary = R.string.enable_smb_always_summary, title = R.string.enable_smb_always))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbWithCob, summary = R.string.enable_smb_with_cob_summary, title = R.string.enable_smb_with_cob))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbWithLowTt, summary = R.string.enable_smb_with_temp_target_summary, title = R.string.enable_smb_with_temp_target))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbAfterCarbs, summary = R.string.enable_smb_after_carbs_summary, title = R.string.enable_smb_after_carbs))
                addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsMaxSmbFrequency, title = R.string.smb_interval_summary))
                addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsMaxMinutesOfBasalToLimitSmb, title = R.string.smb_max_minutes_summary))
                addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsUamMaxMinutesOfBasalToLimitSmb, dialogMessage = R.string.uam_smb_max_minutes, title = R.string.uam_smb_max_minutes_summary))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseUam, summary = R.string.enable_uam_summary, title = R.string.enable_uam))
            })
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "absorption_smb_advanced"
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
                    AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsMaxCurrentBasalMultiplier, dialogMessage = R.string.openapsama_current_basal_safety_multiplier_summary, title = R.string.openapsama_current_basal_safety_multiplier)
                )
            })
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "smb_delivery_settings"
                title = rh.gs(R.string.smb_delivery_settings_title)
                summary = rh.gs(R.string.smb_delivery_settings_summary)
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfSmbDeliveryRatio, dialogMessage = R.string.openapsama_smb_delivery_ratio_summary, title = R.string.openapsama_smb_delivery_ratio))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfSmbDeliveryRatioMin, dialogMessage = R.string.openapsama_smb_delivery_ratio_min_summary, title = R.string.openapsama_smb_delivery_ratio_min))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfSmbDeliveryRatioMax, dialogMessage = R.string.openapsama_smb_delivery_ratio_max_summary, title = R.string.openapsama_smb_delivery_ratio_max))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange, dialogMessage = R.string.openapsama_smb_delivery_ratio_bg_range_summary, title = R.string.openapsama_smb_delivery_ratio_bg_range))
            })
        }
    }

    fun getUserSMBRatio(profile: Profile): Double {
        val fixedRatio = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatio)
        val mappedRatioMin = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioMin)
        val mappedRatioMax = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioMax)
        var mappedRatioBG = if (preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange) < 10.0)
            preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange) * GlucoseUnit.Companion.MMOLL_TO_MGDL
        else
            preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange)

        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        if (glucoseStatus == null || mappedRatioBG == 0.0) return fixedRatio

        val target = profile.getTargetMgdl()

        val bg = glucoseStatus.glucose
        if (bg < target) return mappedRatioMin
        if (bg > target + mappedRatioBG) return mappedRatioMax

        val ratio = mappedRatioMin + (mappedRatioMax - mappedRatioMin) * (bg - target) / mappedRatioBG
        aapsLogger.debug(LTag.APS, "SMBVariableRatio($mappedRatioMin - $mappedRatioMax, BG $mappedRatioBG) = $ratio")
        return ratio
    }

    private val varIsfCache = LongSparseArray<Double>()

    private fun loadVariableISFCache() {
        var count = 0
        val apsResults = persistenceLayer.getApsResults(dateUtil.now() - T.Companion.days(1).msecs(), dateUtil.now())
        apsResults.forEach {
            val glucose = it.glucoseStatus?.glucose ?: return@forEach
            val variableSens = it.variableSens ?: return@forEach
            val timestamp = it.date
            val key = timestamp - timestamp % T.Companion.mins(preferences.get(IntKey.ApsMaxSmbFrequency) * 6L).msecs() + glucose.toLong()
            if (variableSens > 0) varIsfCache.put(key, variableSens)
            count++
        }
        aapsLogger.debug(LTag.APS, "Loaded $count variable sensitivity values from database")
    }

    private fun preprocessSmbPreferences(preferenceFragment: PreferenceFragmentCompat) {
        super.preprocessPreferences(preferenceFragment)

        val smbEnabled = preferences.get(BooleanKey.ApsUseSmb)
        val smbAlwaysEnabled = preferences.get(BooleanKey.ApsUseSmbAlways)
        val uamEnabled = preferences.get(BooleanKey.ApsUseUam)
        val advancedFiltering = activePlugin.activeBgSource.advancedFilteringSupported() || preferences.get(BooleanKey.AlwaysPromoteAdvancedFiltering)
        val autoSensOrDynIsfSensEnabled = if (supportsDynamicIsf()) {
            preferences.get(BooleanKey.ApsUseDynamicSensitivity)
        } else {
            preferences.get(BooleanKey.ApsUseAutosens)
        }

        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbAlways.key)?.isVisible = smbEnabled && advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbWithCob.key)?.isVisible = smbEnabled && !smbAlwaysEnabled && advancedFiltering || smbEnabled && !advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbWithLowTt.key)?.isVisible = smbEnabled && !smbAlwaysEnabled && advancedFiltering || smbEnabled && !advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbAfterCarbs.key)?.isVisible = smbEnabled && !smbAlwaysEnabled && advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsResistanceLowersTarget.key)?.isVisible = autoSensOrDynIsfSensEnabled
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsSensitivityRaisesTarget.key)?.isVisible = autoSensOrDynIsfSensEnabled
        preferenceFragment.findPreference<AdaptiveIntPreference>(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb.key)?.isVisible = smbEnabled && uamEnabled
    }

    @Synchronized
    private fun getCachedVariableISF(timestamp: Long, profile: Profile): Pair<String, Double?> {
        if (!supportsDynamicIsf()) return Pair("OFF", null)

        val result = persistenceLayer.getApsResultCloseTo(timestamp)
        if (result?.variableSens != null && result.variableSens != 0.0) {
            return Pair("DB", result.variableSens)
        }

        val glucose = glucoseStatusProvider.glucoseStatusData?.glucose ?: return Pair("GLUC", null)
        val key = timestamp - timestamp % T.Companion.mins(preferences.get(IntKey.ApsMaxSmbFrequency) * 6L).msecs() + glucose.toLong()
        val cached = varIsfCache[key]
        if (cached != null && timestamp < dateUtil.now()) {
            return Pair("HIT", cached)
        }

        val dynIsfResult = calculateVariableISF(profile)
        if (dynIsfResult?.isf == null) return Pair(dynIsfResult?.message ?: "UNK", null)
        varIsfCache.put(key, dynIsfResult.isf)
        if (varIsfCache.size > 1000) varIsfCache.clear()
        return Pair("CALC", dynIsfResult.isf)
    }

    override var lastAPSRun: Long = 0L
    override var lastAPSResult: DetermineBasalResult? = null
    override fun supportsDynamicIsf(): Boolean = isVariableISFImplemented() && preferences.get(BooleanKey.ApsUseDynamicSensitivity)

    override fun getIsfMgdl(profile: Profile, caller: String): Double? {
        if (!isVariableISFImplemented()) return null

        val start = dateUtil.now()
        val result = getCachedVariableISF(start, profile)
        if (result.second == null)
            uiInteraction.addNotificationValidTo(
                Notification.Companion.DYN_ISF_FALLBACK, start,
                rh.gs(R.string.fallback_to_isf_no_tdd, result.first), Notification.Companion.INFO, dateUtil.now() + T.Companion.mins(1).msecs()
            )
        else
            uiInteraction.dismissNotification(Notification.Companion.DYN_ISF_FALLBACK)
        profiler.log(LTag.APS, "getIsfMgdl() msg=${result.first} isf=${result.second} caller=$caller", start)
        return result.second
    }

    override fun getAverageIsfMgdl(timestamp: Long, caller: String): Double? {
        var count = 0
        var sum = 0.0
        val start = timestamp - T.Companion.hours(24).msecs()
        varIsfCache.forEach { key, value ->
            if (key in start..timestamp) {
                count++
                sum += value
            }
        }
        val sensitivity = if (count == 0) null else sum / count
        aapsLogger.debug(LTag.APS, "getAverageIsfMgdl() $sensitivity from $count values ${dateUtil.dateAndTimeAndSecondsString(timestamp)} $caller")
        return sensitivity
    }

    override fun specialEnableCondition(): Boolean {
        return try {
            activePlugin.activePump.pumpDescription.isTempBasalCapable
        } catch (_: Exception) {
            // may fail during initialization
            true
        }
    }

    override fun specialShowInListCondition(): Boolean {
        try {
            val pump = activePlugin.activePump
            return pump.pumpDescription.isTempBasalCapable
        } catch (_: Exception) {
            return true
        }
    }

    override fun preprocessPreferences(preferenceFragment: PreferenceFragmentCompat) {
        super.preprocessPreferences(preferenceFragment)
        if (areSMBSupported()) preprocessSmbPreferences(preferenceFragment)
    }

    override fun onStart() {
        super.onStart()
        if (isVariableISFImplemented()) loadVariableISFCache()
    }

    override fun isAutosensModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        if (supportsDynamicIsf()) {
            // DynISF mode
            if (!preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity))
                value.set(false, rh.gs(R.string.autosens_disabled_in_preferences), this)
        } else if (areSMBSupported()) {
            // SMB mode
            val enabled = preferences.get(BooleanKey.ApsUseAutosens)
            if (!enabled) value.set(false, rh.gs(R.string.autosens_disabled_in_preferences), this)
        }
        return value
    }

    override fun isUAMEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        if (!isUAMImplemented()) return value

        val enabled = preferences.get(BooleanKey.ApsUseUam)
        if (!enabled) value.set(false, rh.gs(R.string.uam_disabled_in_preferences), this)
        return value
    }

    override fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        if (!areSMBSupported()) return value

        val enabled = preferences.get(BooleanKey.ApsUseSmb)
        if (!enabled) value.set(false, rh.gs(R.string.smb_disabled_in_preferences), this)
        return value
    }

    override fun isSuperBolusEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        value.set(false)
        return value
    }

    override fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
        if (isEnabled()) {
            val maxIobPref = preferences.get(DoubleKey.ApsSmbMaxIob)
            maxIob.setIfSmaller(maxIobPref, rh.gs(R.string.limiting_iob, maxIobPref, rh.gs(R.string.maxvalueinpreferences)), this)
            maxIob.setIfSmaller(hardLimits.maxIobSMB(), rh.gs(R.string.limiting_iob, hardLimits.maxIobSMB(), rh.gs(R.string.hardlimit)), this)
        }
        return maxIob
    }

    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        if (isEnabled()) {
            var maxBasal = preferences.get(DoubleKey.ApsMaxBasal)
            if (maxBasal < profile.getMaxDailyBasal()) {
                maxBasal = profile.getMaxDailyBasal()
                absoluteRate.addReason(rh.gs(R.string.increasing_max_basal), this)
            }
            absoluteRate.setIfSmaller(maxBasal, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxBasal, rh.gs(R.string.maxvalueinpreferences)), this)

            // Check percentRate but absolute rate too, because we know real current basal in pump
            val maxBasalMultiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier)
            val maxFromBasalMultiplier = floor(maxBasalMultiplier * profile.getBasal() * 100) / 100
            absoluteRate.setIfSmaller(
                maxFromBasalMultiplier,
                rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxFromBasalMultiplier, rh.gs(R.string.max_basal_multiplier)),
                this
            )
            val maxBasalFromDaily = preferences.get(DoubleKey.ApsMaxDailyMultiplier)
            val maxFromDaily = floor(profile.getMaxDailyBasal() * maxBasalFromDaily * 100) / 100
            absoluteRate.setIfSmaller(maxFromDaily, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxFromDaily, rh.gs(R.string.max_daily_basal_multiplier)), this)
        }
        return absoluteRate
    }

    override fun invoke(initiator: String, tempBasalFallback: Boolean) {
        aapsLogger.debug(LTag.APS, "invoke from $initiator tempBasalFallback: $tempBasalFallback")
        lastAPSResult = null
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        val profile = profileFunction.getProfile()
        val pump = activePlugin.activePump
        if (profile == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(app.aaps.core.ui.R.string.no_profile_set)))
            aapsLogger.debug(LTag.APS, rh.gs(app.aaps.core.ui.R.string.no_profile_set))
            return
        }
        if (!isEnabled()) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_disabled)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_disabled))
            return
        }
        if (glucoseStatus == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_no_glucose_data)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_no_glucose_data))
            return
        }

        val inputConstraints = ConstraintObject(0.0, aapsLogger) // fake. only for collecting all results

        if (!hardLimits.checkHardLimits(profile.dia, app.aaps.core.ui.R.string.profile_dia, hardLimits.minDia(), hardLimits.maxDia())) return
        if (!hardLimits.checkHardLimits(
                profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight()),
                app.aaps.core.ui.R.string.profile_carbs_ratio_value,
                hardLimits.minIC(),
                hardLimits.maxIC()
            )
        ) return
        if (!hardLimits.checkHardLimits(profile.getIsfMgdl("OpenAPSSMBPlugin"), app.aaps.core.ui.R.string.profile_sensitivity_value, HardLimits.MIN_ISF, HardLimits.MAX_ISF)) return
        if (!hardLimits.checkHardLimits(profile.getMaxDailyBasal(), app.aaps.core.ui.R.string.profile_max_daily_basal_value, 0.02, hardLimits.maxBasal())) return
        if (!hardLimits.checkHardLimits(pump.baseBasalRate, app.aaps.core.ui.R.string.current_basal_value, 0.01, hardLimits.maxBasal())) return

        var minBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetLowMgdl(), 0.1), app.aaps.core.ui.R.string.profile_low_target, HardLimits.LIMIT_MIN_BG[0], HardLimits.LIMIT_MIN_BG[1])
        var maxBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetHighMgdl(), 0.1), app.aaps.core.ui.R.string.profile_high_target, HardLimits.LIMIT_MAX_BG[0], HardLimits.LIMIT_MAX_BG[1])
        var targetBg = hardLimits.verifyHardLimits(profile.getTargetMgdl(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TARGET_BG[0], HardLimits.LIMIT_TARGET_BG[1])
        var isTempTarget = false
        persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            isTempTarget = true
            minBg = hardLimits.verifyHardLimits(tempTarget.lowTarget, app.aaps.core.ui.R.string.temp_target_low_target, HardLimits.LIMIT_TEMP_MIN_BG[0], HardLimits.LIMIT_TEMP_MIN_BG[1])
            maxBg = hardLimits.verifyHardLimits(tempTarget.highTarget, app.aaps.core.ui.R.string.temp_target_high_target, HardLimits.LIMIT_TEMP_MAX_BG[0], HardLimits.LIMIT_TEMP_MAX_BG[1])
            targetBg = hardLimits.verifyHardLimits(tempTarget.target(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TEMP_TARGET_BG[0], HardLimits.LIMIT_TEMP_TARGET_BG[1])
        }

        val now = dateUtil.now()
        val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
        val currentTemp = CurrentTemp(
            duration = tb?.plannedRemainingMinutes ?: 0,
            rate = tb?.convertedToAbsolute(now, profile) ?: 0.0,
            minutesrunning = tb?.getPassedDurationToTimeInMinutes(now)
        )

        val varIsfResult: VariableISFResult? = if (supportsDynamicIsf())
            calculateVariableISF(profile)
        else
            null

        val autosensResult = getAutosensData(varIsfResult) ?: return

        val smbAllowed = if (areSMBSupported())
            constraintsChecker.isSMBModeEnabled(ConstraintObject(tempBasalFallback.not(), aapsLogger)).also { inputConstraints.copyReasons(it) }.value()
        else
            false

        val uamAllowed = if (isUAMImplemented())
            constraintsChecker.isUAMEnabled().also { inputConstraints.copyReasons(it) }.value()
        else
            false


        invokeLazy(InvokeData(
            initiator = initiator,
            tempBasalFallback = tempBasalFallback,
            profile = profile,
            glucoseStatus = glucoseStatus,
            activePlugin = activePlugin,
            variableISFResult = varIsfResult,
            minTarget = minBg,
            maxTarget = maxBg,
            target = targetBg,
            isTempTarget = isTempTarget,
            isSMBEnabled = areSMBSupported() && preferences.get(BooleanKey.ApsUseSmb),
            isUAMEnabled = isUAMImplemented() && preferences.get(BooleanKey.ApsUseUam),
            isAdvancedFilteringSupported = constraintsChecker.isAdvancedFilteringEnabled().also { inputConstraints.copyReasons(it) }.value(),
            inputConstraints = inputConstraints,
            autosensResult = autosensResult,
            currentTemp = currentTemp,
            maxBasal = constraintsChecker.getMaxBasalAllowed(profile).also { inputConstraints.copyReasons(it) }.value(),
            maxIob = constraintsChecker.getMaxIOBAllowed().also { inputConstraints.copyReasons(it) }.value(),
            maxDailyBasal = profile.getMaxDailyBasal(),
            smbAllowed = smbAllowed,
            uamAllowed = uamAllowed,
        ))
    }
}