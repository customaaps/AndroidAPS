package app.aaps.plugins.aps.openAPSBoost

import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.getPassedDurationToTimeInMinutes
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock as withOwnerLock
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import app.aaps.core.objects.extensions.target
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.icons.IcPluginOpenAPS
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.utils.MidnightUtils
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.plugins.aps.keys.ApsIntentKey
import app.aaps.plugins.aps.openAPSSMB.GlucoseStatusCalculatorSMB
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import app.aaps.plugins.boostport.aps.BoostApsCalculator
import app.aaps.plugins.boostport.aps.BoostApsCalculatorFactory
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.plugin.EnforcedState
import app.aaps.core.interfaces.db.observeChanges
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.CAL
import app.aaps.core.data.model.RM
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.interfaces.rx.collectResilient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.min

@SingleIn(AppScope::class)
@Inject
class OpenAPSBoostPlugin(
    aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val constraintsChecker: ConstraintsChecker,
    override val rh: TextResolver,
    private val config: Config,
    private val profileFunction: ProfileFunction,
    private val activePlugin: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val hardLimits: HardLimits,
    private val dateUtil: DateUtil,
    private val persistenceLayer: PersistenceLayer,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    preferences: Preferences,
    private val calculatorFactory: BoostApsCalculatorFactory,
    private val profileUtil: ProfileUtil,
    private val bgQualityCheck: BgQualityCheck,
    private val glucoseStatusCalculatorSMB: GlucoseStatusCalculatorSMB,
    private val apsResultProvider: () -> APSResult,
    private val ch: ConcentrationHelper,
    private val fabricPrivacy: FabricPrivacy,
    notificationManager: NotificationManager
) : PluginBaseWithPreferences(
    PluginDescription()
        .mainType(PluginType.APS)
        .enforce(EnforcedState.Disabled)
        .composeContent { plugin ->
            app.aaps.plugins.aps.compose.OpenAPSComposeContent(
                apsPlugin = plugin as APS,
                rxBus = rxBus,
                rh = rh,
                dateUtil = dateUtil
            )
        }
        .icon(IcPluginOpenAPS)
        .pluginName(ApsStrings.openapsboost)
        .preferencesVisibleInSimpleMode(false)
        .showInList { false }   // Remains unavailable until source policy owners are connected.
        .description(ApsStrings.description_boost),
    ownPreferences = calculatorFactory.ownedKeys,
    aapsLogger, rh, preferences, notificationManager
), APS, PluginConstraints {

    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.BOOST
    override var lastAPSResult: APSResult? = null
    private val invokeMutex = Mutex()
    private val ownerLock = AapsLock()
    @Volatile private var generation: Any = Any()
    @Volatile private var calculator: BoostApsCalculator? = null
    @Volatile private var calibrationBlockedUntil: Long = 0
    private var observers: CoroutineScope? = null
    private var previousProfileSwitch: Long? = null

    override fun usingDynamicIsf() = true
    override fun offersDynamicSensitivity() = true
    override fun getIsfMgdl(profile: Profile, caller: String): Double? = null
    override fun getAverageIsfMgdl(timestamp: Long, caller: String): Double? = null

    private fun invalidateOwner() = ownerLock.withOwnerLock {
        generation = Any()
        calculator?.close()
        calculator = null
        lastAPSResult = null
        lastAPSRun = 0
        previousProfileSwitch = null
    }

    override suspend fun onStart() {
        super.onStart()
        invalidateOwner()
        observers?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        observers = scope
        persistenceLayer.observeChanges<TE>().collectResilient(scope, aapsLogger, LTag.APS, start = CoroutineStart.UNDISPATCHED) { entries ->
            if (entries.any { it.type == TE.Type.FINGER_STICK_BG_VALUE }) calibrationBlockedUntil = dateUtil.now() + 15 * 60_000L
        }
        persistenceLayer.observeChanges<CAL>().collectResilient(scope, aapsLogger, LTag.APS, start = CoroutineStart.UNDISPATCHED) { entries ->
            if (entries.isNotEmpty()) calibrationBlockedUntil = dateUtil.now() + 15 * 60_000L
        }
    }

    override suspend fun onStop() {
        observers?.cancel()
        observers = null
        invalidateOwner()
        super.onStop()
    }

    override fun beforeImport() = invalidateOwner()
    override fun afterImport() = invalidateOwner()

    override suspend fun invoke(initiator: String, tempBasalFallback: Boolean) = withContext(Dispatchers.Default) {
        invokeMutex.withLock {
            val ownerGeneration = generation
            aapsLogger.debug(LTag.APS, "invoke from $initiator tempBasalFallback: $tempBasalFallback")
            lastAPSResult = null
            val glucoseStatus = glucoseStatusProvider.glucoseStatusData
            val profile = profileFunction.getProfile()
            val pump = activePlugin.activePump
            if (profile == null) {
                rxBus.send(EventResetOpenAPSGui(rh.gs(CoreUiStrings.no_profile_set)))
                aapsLogger.debug(LTag.APS, rh.gs(CoreUiStrings.no_profile_set))
                return@withLock
            }
            if (!isEnabled()) {
                rxBus.send(EventResetOpenAPSGui(rh.gs(ApsStrings.openapsma_disabled)))
                aapsLogger.debug(LTag.APS, rh.gs(ApsStrings.openapsma_disabled))
                return@withLock
            }
            if (glucoseStatus == null) {
                rxBus.send(EventResetOpenAPSGui(rh.gs(ApsStrings.openapsma_no_glucose_data)))
                aapsLogger.debug(LTag.APS, rh.gs(ApsStrings.openapsma_no_glucose_data))
                return@withLock
            }
            val inputConstraints = ConstraintObject(0.0, aapsLogger) // fake. only for collecting all results

            if (!hardLimits.checkHardLimits(profile.iCfg.dia, InterfacesStrings.profile_dia, hardLimits.diaRange())) return@withLock
            if (!hardLimits.checkHardLimits(
                    profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight()),
                    InterfacesStrings.profile_carbs_ratio_value,
                    hardLimits.icRange()
                )
            ) return@withLock
            if (!hardLimits.checkHardLimits(profile.getProfileIsfMgdl(), InterfacesStrings.profile_sensitivity_value, HardLimits.LIMIT_ISF)) return@withLock
            if (!hardLimits.checkHardLimits(profile.getMaxDailyBasal(), CoreUiStrings.profile_max_daily_basal_value, 0.02, hardLimits.maxBasal())) return@withLock
            if (!hardLimits.checkHardLimits(ch.fromPump(pump.baseBasalRate), CoreUiStrings.current_basal_value, 0.01, hardLimits.maxBasal())) return@withLock

            val now = dateUtil.now()
            val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
            val currentTemp = CurrentTemp(
                duration = tb?.plannedRemainingMinutes ?: 0,
                rate = tb?.convertedToAbsolute(now, profile) ?: 0.0,
                minutesrunning = tb?.getPassedDurationToTimeInMinutes(now)
            )
            var minBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetLowMgdl(), 0.1), InterfacesStrings.profile_low_target, HardLimits.LIMIT_MIN_BG)
            var maxBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetHighMgdl(), 0.1), InterfacesStrings.profile_high_target, HardLimits.LIMIT_MAX_BG)
            val originalTargetMgDl = profile.getTargetMgdl()
            var targetBg = hardLimits.verifyHardLimits(originalTargetMgDl, CoreUiStrings.temp_target_value, HardLimits.LIMIT_TARGET_BG)

            var isTempTarget = false
            persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
                isTempTarget = true
                minBg = hardLimits.verifyHardLimits(tempTarget.lowTarget, CoreUiStrings.temp_target_low_target, HardLimits.LIMIT_TEMP_MIN_BG)
                maxBg = hardLimits.verifyHardLimits(tempTarget.highTarget, CoreUiStrings.temp_target_high_target, HardLimits.LIMIT_TEMP_MAX_BG)
                targetBg = hardLimits.verifyHardLimits(tempTarget.target(), CoreUiStrings.temp_target_value, HardLimits.LIMIT_TEMP_TARGET_BG)
            }

            val autosensResult =
                if (constraintsChecker.isAutosensModeEnabled().value()) {
                    val autosensData = iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish("OpenAPSBoostPlugin")
                    if (autosensData == null) {
                        rxBus.send(EventResetOpenAPSGui(rh.gs(ApsStrings.openaps_no_as_data)))
                        return@withLock
                    }
                    autosensData.autosensResult
                } else AutosensResult(sensResult = "autosens disabled")

            val iobArray = iobCobCalculator.calculateIobArrayForSMB(autosensResult, SMBDefaults.exercise_mode, SMBDefaults.half_basal_exercise_target, isTempTarget)
            val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()

            val originalCr = profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight(now))
            val originalIsf = profile.getProfileIsfMgdl()
            val smbEnabled = preferences.get(BooleanKey.ApsUseSmb)
            val advancedFiltering = preferences.get(BooleanKey.ApsIgnoreBgSource) ||
                constraintsChecker.isAdvancedFilteringEnabled().also { inputConstraints.copyReasons(it) }.value()
            val oapsProfile = OapsProfile(
                dia = 0.0, // not used
                min_5m_carbimpact = 0.0, // not used
                max_iob = constraintsChecker.getMaxIOBAllowed().also { inputConstraints.copyReasons(it) }.value(),
                max_daily_basal = profile.getMaxDailyBasal(),
                max_basal = constraintsChecker.getMaxBasalAllowed(profile).also { inputConstraints.copyReasons(it) }.value(),
                min_bg = minBg,
                max_bg = maxBg,
                target_bg = targetBg,
                carb_ratio = originalCr,
                sens = originalIsf,
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
                half_basal_exercise_target = SMBDefaults.half_basal_exercise_target,
                maxCOB = SMBDefaults.maxCOB,
                skip_neutral_temps = pump.setNeutralTempAtFullHour(),
                remainingCarbsCap = SMBDefaults.remainingCarbsCap,
                enableUAM = constraintsChecker.isUAMEnabled().also { inputConstraints.copyReasons(it) }.value(),
                A52_risk_enable = SMBDefaults.A52_risk_enable,
                SMBInterval = preferences.get(IntKey.ApsMaxSmbFrequency),
                enableSMB_with_COB = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithCob),
                enableSMB_with_temptarget = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithLowTt),
                allowSMB_with_high_temptarget = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithHighTt),
                enableSMB_always = smbEnabled && preferences.get(BooleanKey.ApsUseSmbAlways) && advancedFiltering,
                enableSMB_after_carbs = smbEnabled && preferences.get(BooleanKey.ApsUseSmbAfterCarbs) && advancedFiltering,
                maxSMBBasalMinutes = preferences.get(IntKey.ApsMaxMinutesOfBasalToLimitSmb),
                maxUAMSMBBasalMinutes = preferences.get(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb),
                bolus_increment = pump.pumpDescription.bolusStep,
                carbsReqThreshold = preferences.get(IntKey.ApsCarbsRequestThreshold),
                current_basal = ch.fromPump(activePlugin.activePump.baseBasalRate),
                temptargetSet = isTempTarget,
                autosens_max = preferences.get(DoubleKey.AutosensMax),
                out_units = if (profileFunction.getUnits() == GlucoseUnit.MMOL) "mmol/L" else "mg/dl",
                variable_sens = 0.0,
                insulinDivisor = 0,
                TDD = 0.0
            )
            val allowed = !tempBasalFallback && constraintsChecker.isSMBModeEnabled(ConstraintObject(!tempBasalFallback, aapsLogger))
                .also { inputConstraints.copyReasons(it) }.value() && now >= calibrationBlockedUntil
            val runningMode = persistenceLayer.getRunningModeActiveAt(now).mode
            val profileSwitch = persistenceLayer.getProfileSwitchActiveAt(now)?.let { if (it.id != 0L) it.id else it.timestamp } ?: 0L
            val switched = previousProfileSwitch?.let { it != profileSwitch } ?: false
            previousProfileSwitch = profileSwitch
            val owner = ownerLock.withOwnerLock {
                if (generation !== ownerGeneration) null
                else calculator ?: calculatorFactory.create().also { calculator = it }
            } ?: return@withLock
            val request = BoostApsCalculator.Input(glucoseStatus, currentTemp, iobArray, mealData, oapsProfile,
                originalCr, originalIsf, autosensResult, (profile as? ProfileSealed.EPS)?.value?.originalPercentage ?: 100,
                profile.iCfg.peak, now, allowed, bgQualityCheck.state == BgQualityCheck.State.FLAT,
                runningMode == RM.Mode.DISCONNECTED_PUMP || runningMode == RM.Mode.SUSPENDED_BY_PUMP,
                runningMode.pausesLoopExecution() && runningMode != RM.Mode.DISCONNECTED_PUMP && runningMode != RM.Mode.SUSPENDED_BY_PUMP,
                switched, originalProfileTargetMgDl = originalTargetMgDl)
            when (val calculated = owner.calculate(request)) {
                is BoostApsCalculator.Result.Rejected -> {
                    aapsLogger.error(LTag.APS, calculated.reason)
                    rxBus.send(EventResetOpenAPSGui(calculated.reason))
                }
                is BoostApsCalculator.Result.Calculated -> {
                    if (calculated.nightModeActive) calculated.result.reason.append(rh.gs(ApsStrings.boost_night_mode_smb_disabled))
                    val result = apsResultProvider().with(calculated.result)
                    result.inputConstraints = inputConstraints
                    result.autosensResult = calculated.autosens
                    result.iobData = iobArray
                    result.glucoseStatus = glucoseStatus
                    result.currentTemp = currentTemp
                    result.oapsProfile = calculated.profile
                    result.mealData = mealData
                    val published = ownerLock.withOwnerLock {
                        if (generation !== ownerGeneration || calculator !== owner || !isEnabled()) false
                        else {
                            lastAPSResult = result
                            lastAPSRun = now
                            true
                        }
                    }
                    if (!published) return@withLock
                    rxBus.send(EventAPSCalculationFinished())
                }
            }
            rxBus.send(EventOpenAPSUpdateGui())
        }
    }

    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? = glucoseStatusCalculatorSMB.getGlucoseStatusData(allowOldData)

    override fun isSuperBolusEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        value.set(false)
        return value
    }

    override suspend fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
        if (isEnabled()) {
            val maxIobPref = preferences.get(DoubleKey.ApsSmbMaxIob)
            maxIob.setIfSmaller(maxIobPref, rh.gs(ApsStrings.limiting_iob, maxIobPref, rh.gs(ApsStrings.maxvalueinpreferences)), this)
            maxIob.setIfSmaller(hardLimits.maxIobSMB(), rh.gs(ApsStrings.limiting_iob, hardLimits.maxIobSMB(), rh.gs(ApsStrings.hardlimit)), this)
        }
        return maxIob
    }

    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        if (isEnabled()) {
            var maxBasal = preferences.get(DoubleKey.ApsMaxBasal)
            if (maxBasal < profile.getMaxDailyBasal()) {
                maxBasal = profile.getMaxDailyBasal()
                absoluteRate.addReason(rh.gs(ApsStrings.increasing_max_basal), this)
            }
            absoluteRate.setIfSmaller(maxBasal, rh.gs(CoreUiStrings.limitingbasalratio, maxBasal, rh.gs(ApsStrings.maxvalueinpreferences)), this)

            // Check percentRate but absolute rate too, because we know real current basal in pump
            val maxBasalMultiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier)
            val maxFromBasalMultiplier = floor(maxBasalMultiplier * profile.getBasal() * 100) / 100
            absoluteRate.setIfSmaller(
                maxFromBasalMultiplier,
                rh.gs(CoreUiStrings.limitingbasalratio, maxFromBasalMultiplier, rh.gs(ApsStrings.max_basal_multiplier)),
                this
            )
            val maxBasalFromDaily = preferences.get(DoubleKey.ApsMaxDailyMultiplier)
            val maxFromDaily = floor(profile.getMaxDailyBasal() * maxBasalFromDaily * 100) / 100
            absoluteRate.setIfSmaller(maxFromDaily, rh.gs(CoreUiStrings.limitingbasalratio, maxFromDaily, rh.gs(ApsStrings.max_daily_basal_multiplier)), this)
        }
        return absoluteRate
    }

    override suspend fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseSmb)
        if (!enabled) value.set(false, rh.gs(ApsStrings.smb_disabled_in_preferences), this)
        return value
    }

    override fun isUAMEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseUam)
        if (!enabled) value.set(false, rh.gs(ApsStrings.uam_disabled_in_preferences), this)
        return value
    }

    override fun isAutosensModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        if (!preferences.get(BooleanKey.ApsUseAutosens)) value.set(false, rh.gs(ApsStrings.autosens_disabled_in_preferences), this)
        return value
    }
}
