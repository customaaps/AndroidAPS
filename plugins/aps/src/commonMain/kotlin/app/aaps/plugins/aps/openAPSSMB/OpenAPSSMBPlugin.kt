package app.aaps.plugins.aps.openAPSSMB

import androidx.collection.LongSparseArray
import androidx.collection.forEach
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.getPassedDurationToTimeInMinutes
import app.aaps.core.interfaces.aps.DynamicCarbRatioPolicy
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.configuration.Config
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
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef.Companion.withArgs
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import app.aaps.core.objects.extensions.target
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.icons.IcPluginOpenAPS
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.utils.MidnightUtils
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.plugins.aps.keys.ApsIntentKey
import app.aaps.plugins.aps.openAPSSMB.ActivityClass
import app.aaps.plugins.aps.openAPSSMB.ActivityClassifier
import app.aaps.plugins.aps.openAPS.TddStatus
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.ln

@SingleIn(AppScope::class)
@Inject
open class OpenAPSSMBPlugin(
    aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val constraintsChecker: ConstraintsChecker,
    override val rh: TextResolver,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val config: Config,
    private val activePlugin: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val hardLimits: HardLimits,
    preferences: Preferences,
    protected val dateUtil: DateUtil,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val persistenceLayer: PersistenceLayer,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val tddCalculator: TddCalculator,
    private val bgQualityCheck: BgQualityCheck,
    notificationManager: NotificationManager,
    private val determineBasalSMB: DetermineBasalSMB,
    private val profiler: Profiler,
    private val glucoseStatusCalculatorSMB: GlucoseStatusCalculatorSMB,
    private val apsResultProvider: () -> APSResult,
    private val ch: ConcentrationHelper,
    private val fabricPrivacy: FabricPrivacy,
    private val dynamicCarbRatio: DynamicCarbRatioPolicy
) : PluginBaseWithPreferences(
    PluginDescription()
        .mainType(PluginType.APS)
        .composeContent { plugin ->
            app.aaps.plugins.aps.compose.OpenAPSComposeContent(
                apsPlugin = plugin as APS,
                rxBus = rxBus,
                rh = rh,
                dateUtil = dateUtil,
                glucoseUnit = profileFunction::getUnits
            )
        }
        .icon(IcPluginOpenAPS)
        .pluginName(ApsStrings.openapssmb)
        .preferencesVisibleInSimpleMode(false)
        .showInList { config.APS || config.AAPSCLIENT }   // AAPSCLIENT: visible so a client can select the master's APS
        .description(ApsStrings.description_smb)
        .setDefault(),
    ownPreferences = ApsIntentKey.entries,
    aapsLogger, rh, preferences, notificationManager
), APS, PluginConstraints {

    override suspend fun onStart() {
        super.onStart()
        var count = 0
        val apsResults = persistenceLayer.getApsResults(dateUtil.now() - T.days(1).msecs(), dateUtil.now())
        apsResults.forEach {
            val glucose = it.glucoseStatus?.glucose ?: return@forEach
            val variableSens = it.variableSens ?: return@forEach
            val timestamp = it.date
            val key = timestamp - timestamp % T.mins(30).msecs() + glucose.toLong()
            if (variableSens > 0) isfCacheLock.withLock { dynIsfCache.put(key, variableSens) }
            count++
        }
        aapsLogger.debug(LTag.APS, "Loaded $count variable sensitivity values from database")
    }

    // last values
    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.SMB
    override var lastAPSResult: APSResult? = null
    override fun usingDynamicIsf(): Boolean = preferences.get(BooleanKey.ApsUseDynamicSensitivity)
    override fun offersDynamicSensitivity(): Boolean = true

    override fun getIsfMgdl(profile: Profile, caller: String): Double? {
        val start = dateUtil.now()
        val epsProfile = profile as ProfileSealed.EPS
        val sensitivity = runBlocking { calculateVariableIsf(epsProfile, start) }
        if (sensitivity.second == null)
            notificationManager.post(
                NotificationId.DYN_ISF_FALLBACK,
                ApsStrings.fallback_to_isf_no_tdd.withArgs(sensitivity.first),
                level = NotificationLevel.INFO,
                date = start,
                validTo = dateUtil.now() + T.mins(1).msecs())
        else
            notificationManager.dismiss(NotificationId.DYN_ISF_FALLBACK)
        profiler.log(LTag.APS, "getIsfMgdl() reason=${sensitivity.first} sensitivity=${sensitivity.second} caller=$caller", start)
        return sensitivity.second
    }

    override fun getAverageIsfMgdl(timestamp: Long, caller: String): Double? {
        var count = 0
        var sum = 0.0
        val start = timestamp - T.hours(24).msecs()
        isfCacheLock.withLock {
            dynIsfCache.forEach { key, value ->
                if (key in start..timestamp) {
                    count++
                    sum += value
                }
            }
        }
        val sensitivity = if (count == 0) null else sum / count
        aapsLogger.debug(LTag.APS, "getAverageIsfMgdl() $sensitivity from $count values ${dateUtil.dateAndTimeAndSecondsString(timestamp)} $caller")
        return sensitivity
    }

    // No temp basal check here. A pump that cannot do temp basals is handled by
    // SafetyPlugin.isLoopInvocationAllowed, which forces the running mode to DISABLED_LOOP with a reason
    // the user can read, is re-evaluated on every run, and cannot be switched off. Repeating it as a
    // plugin condition only added a second source of truth on the wrong axis: enablement is static per
    // build, while pump capability changes when the user switches pumps.

    internal fun getHalfBasalExerciseTargetMgdl(): Int =
        profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsAutoIsfHalfBasalExerciseTarget)).toInt()

    private val dynIsfCache = LongSparseArray<Double>()

    /** Guards [dynIsfCache]. Was `synchronized(dynIsfCache)`, which is JVM only. */
    private val isfCacheLock = AapsLock()

    private suspend fun calculateVariableIsf(profile: EffectiveProfile, timestamp: Long): Pair<String, Double?> {
        if (!preferences.get(BooleanKey.ApsUseDynamicSensitivity)) return Pair("OFF", null)

        val useProfileSensitivity = preferences.get(BooleanKey.ApsDynIsfUseProfileSens)
        if (!useProfileSensitivity) {
            val result = persistenceLayer.getApsResultCloseTo(timestamp)
            if (result?.variableSens != null && result.variableSens != 0.0) {
                //aapsLogger.debug("calculateVariableIsf $caller DB  ${dateUtil.dateAndTimeAndSecondsString(timestamp)} ${result.variableSens}")
                return Pair("DB", result.variableSens)
            }
        }

        val glucose = glucoseStatusProvider.glucoseStatusData?.glucose ?: return Pair("GLUC", null)
        // Round down to 30 min and use it as a key for caching
        // Add BG to key as it affects calculation
        val key = timestamp - timestamp % T.mins(30).msecs() + glucose.toLong()
        val cached = if (useProfileSensitivity) null else isfCacheLock.withLock { dynIsfCache[key] }
        if (cached != null && timestamp < dateUtil.now()) {
            //aapsLogger.debug("calculateVariableIsf $caller HIT ${dateUtil.dateAndTimeAndSecondsString(timestamp)} $cached")
            return Pair("HIT", cached)
        }

        val dynIsfResult = calculateRawDynIsf(profile)
        if (!dynIsfResult.tddPartsCalculated() && !useProfileSensitivity) return Pair("TDD miss", null)
        // no cached result found, let's calculate the value
        //aapsLogger.debug("calculateVariableIsf $caller CAL ${dateUtil.dateAndTimeAndSecondsString(timestamp)} $sensitivity")
        if (!useProfileSensitivity) isfCacheLock.withLock {
            dynIsfResult.variableSensitivity?.let { dynIsfCache.put(key, it) }
            if (dynIsfCache.size() > 1000) dynIsfCache.clear()
        }
        return Pair("CALC", dynIsfResult.variableSensitivity)
    }

    internal class DynIsfResult {

        var tdd1D: Double? = null
        var tdd7D: Double? = null
        var tddLast24H: Double? = null
        var tddLast4H: Double? = null
        var tddLast8to4H: Double? = null
        var tdd: Double? = null
        var variableSensitivity: Double? = null
        var insulinDivisor: Int = 0

        /**
         * ISF at [DynIsfSensitivity.NORMAL_TARGET_MGDL] the velocity blend started from: rounded
         * to 0.1, after the profile-% and temp-target scaling. The activity multiplier (4.0) is
         * applied to [variableSensitivity] only. The raw TDD base the predictions use is derived
         * from [tdd], not from this.
         */
        var baseSensitivity: Double? = null

        var tddLast24HCarbs = 0.0
        var tdd7DDataCarbs = 0.0
        var tdd7DAllDaysHaveCarbs = false

        fun tddPartsCalculated() = tdd1D != null && tdd7D != null && tddLast24H != null && tddLast4H != null && tddLast8to4H != null

        fun tddQuickCalculated() = tddLast4H != null && tddLast8to4H != null

        fun log() =
            "DynIsfResult: tdd1D=$tdd1D tdd7D=$tdd7D tddLast24H=$tddLast24H tddLast4H=$tddLast4H tddLast8to4H=$tddLast8to4H tdd=$tdd variableSensitivity=$variableSensitivity baseSensitivity=$baseSensitivity insulinDivisor=$insulinDivisor tdd7DDataCarbs=$tdd7DDataCarbs tdd7DAllDaysHaveCarbs=$tdd7DAllDaysHaveCarbs"
    }

    private fun capGlucose(glucoseStatus: GlucoseStatus): Double {
        // inspired by justmara
        val bgCap = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsDynIsfBgCap))
        return if (glucoseStatus.glucose > bgCap)
            bgCap + ((glucoseStatus.glucose - bgCap) / 3)
        else
            glucoseStatus.glucose
    }

    private suspend fun calculateRawDynIsf(profile: EffectiveProfile): DynIsfResult {
        val dynIsfResult = DynIsfResult()
        val profileMultiplier = if (preferences.get(BooleanKey.ApsDynIsfProfilePercentage))
            100.0 / (profile as ProfileSealed.EPS).value.originalPercentage
        else
            1.0

        // DynamicISF specific
        // without these values DynISF doesn't work properly
        val glucoseStatus = glucoseStatusCalculatorSMB.getGlucoseStatusData(allowOldData = false)
        val glucose = glucoseStatus?.let { capGlucose(it) }
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

        // 3.x: (120 - activeInsulin.peak), at least 1 - rapid (75) -> 45, Fiasp (55) -> 65,
        // Lyumjev (45) -> 75. Peak comes from the profile this calculation is about, which owns the
        // authoritative (non-null) iCfg. The result is cached per timestamp for ISF replay, so a
        // global "currently active insulin" would be the wrong source even when one is available.
        dynIsfResult.insulinDivisor = (120 - profile.iCfg.peak).coerceAtLeast(1)

        val normalTarget = DynIsfSensitivity.NORMAL_TARGET_MGDL
        var baseSensitivity = profile.getProfileIsfMgdl()

        // Always calculate TDD, it's used not just in sensitivity calculation
        val useTDD = !preferences.get(BooleanKey.ApsDynIsfUseProfileSens)
        if (dynIsfResult.tddPartsCalculated()) {
            val tddStatus = TddStatus(dynIsfResult.tdd1D!!, dynIsfResult.tdd7D!!, dynIsfResult.tddLast24H!!, dynIsfResult.tddLast4H!!, dynIsfResult.tddLast8to4H!!)
            val tddWeightedFromLast8H = ((1.4 * tddStatus.tddLast4H) + (0.6 * tddStatus.tddLast8to4H)) * 3
            dynIsfResult.tdd = (tddWeightedFromLast8H * 0.33) + (tddStatus.tdd7D * 0.34) + (tddStatus.tdd1D * 0.33)
        } else if (dynIsfResult.tddQuickCalculated()) {
            aapsLogger.warn(LTag.APS, "Using quick TDD")
            dynIsfResult.tdd = ((1.4 * dynIsfResult.tddLast4H!!) + (0.6 * dynIsfResult.tddLast8to4H!!)) * 3
        }

        val adjFactor = preferences.get(IntKey.ApsDynIsfAdjustmentFactor) / 100.0
        if (dynIsfResult.tdd != null) dynIsfResult.tdd = dynIsfResult.tdd!! * adjFactor

        if (useTDD) {
            if (dynIsfResult.tdd == null) {
                aapsLogger.error(LTag.APS, "Using TDD-based DynISF, but got no TDD")
                return dynIsfResult
            }
            aapsLogger.debug(LTag.APS, "Using TDD base sensitivity")
            baseSensitivity = Round.roundTo(DynIsfSensitivity.baseFromTdd(dynIsfResult.tdd!!, dynIsfResult.insulinDivisor), 0.1)
            if (preferences.get(BooleanKey.ApsDynIsfProfilePercentage)) {
                baseSensitivity *= profileMultiplier
                aapsLogger.debug(LTag.APS, "Scaling TDD sensitivity by profile% - $profileMultiplier")
            }
        }

        if (glucose == null) {
            aapsLogger.error(LTag.APS, "Glucose is null")
            return dynIsfResult
        }

        // Scale base sensitivity by TT if needed
        var isTempTarget = false
        var targetBg = profile.getTargetMgdl()
        persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            isTempTarget = true
            targetBg = hardLimits.verifyHardLimits(tempTarget.target(), CoreUiStrings.temp_target_value, HardLimits.LIMIT_TEMP_TARGET_BG)
        }
        if (isTempTarget) {
            if ((preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens) && targetBg > normalTarget)
                || (preferences.get(BooleanKey.ApsAutoIsfLowTtLowersSens) && targetBg < normalTarget)) {
                val c = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsAutoIsfHalfBasalExerciseTarget)) - normalTarget
                if (c * (c + targetBg - normalTarget) > 0.0) {
                    // The 3.x clamp here was a no-op (.apply discards the coerced result); the
                    // clamp is kept on purpose as the approved deviation from the reference.
                    val sensitivityRatio = Round.roundTo(
                        (c / (c + targetBg - normalTarget)).coerceIn(
                            preferences.get(DoubleKey.AutosensMin),
                            preferences.get(DoubleKey.AutosensMax)
                        ), 0.01
                    )
                    aapsLogger.debug(LTag.APS, "Scaling sensitivity by TT ratio: $sensitivityRatio")
                    baseSensitivity /= sensitivityRatio
                }
            }
        }

        dynIsfResult.baseSensitivity = baseSensitivity
        // The 3.x velocity blend, final value left unrounded: velocity 100 gives the pure curve,
        // 0 a flat ISF at the base. DetermineBasalSMB rounds where the old code rounded.
        val velocity = preferences.get(IntKey.ApsDynIsfVelocity) / 100.0
        val sbg = ln((glucose / dynIsfResult.insulinDivisor) + 1)
        val scaler = ln((normalTarget / dynIsfResult.insulinDivisor) + 1) / sbg
        val ratio = 1 - (1 - scaler) * velocity
        dynIsfResult.variableSensitivity = baseSensitivity * ratio

        aapsLogger.debug(
            LTag.APS,
            "multiplier=$profileMultiplier gluc=$glucose tdd=${dynIsfResult.tdd} (${adjFactor}x) baseSens=$baseSensitivity velocity=$velocity -> sensRatio=$ratio sens=${dynIsfResult.variableSensitivity}"
        )
        return dynIsfResult
    }

    override suspend fun invoke(initiator: String, tempBasalFallback: Boolean) = withContext(Dispatchers.Default) {
        aapsLogger.debug(LTag.APS, "invoke from $initiator tempBasalFallback: $tempBasalFallback")
        lastAPSResult = null
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        val profile = profileFunction.getProfile()
        val pump = activePlugin.activePump
        if (profile == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(CoreUiStrings.no_profile_set)))
            aapsLogger.debug(LTag.APS, rh.gs(CoreUiStrings.no_profile_set))
            return@withContext
        }
        if (!isEnabled()) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(ApsStrings.openapsma_disabled)))
            aapsLogger.debug(LTag.APS, rh.gs(ApsStrings.openapsma_disabled))
            return@withContext
        }
        if (glucoseStatus == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(ApsStrings.openapsma_no_glucose_data)))
            aapsLogger.debug(LTag.APS, rh.gs(ApsStrings.openapsma_no_glucose_data))
            return@withContext
        }

        val inputConstraints = ConstraintObject(0.0, aapsLogger) // fake. only for collecting all results

        if (!hardLimits.checkHardLimits(profile.iCfg.dia, InterfacesStrings.profile_dia, hardLimits.diaRange())) return@withContext
        if (!hardLimits.checkHardLimits(
                profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight()),
                InterfacesStrings.profile_carbs_ratio_value,
                hardLimits.icRange()
            )
        ) return@withContext
        if (!hardLimits.checkHardLimits(profile.getIsfMgdl("OpenAPSSMBPlugin"), InterfacesStrings.profile_sensitivity_value, HardLimits.LIMIT_ISF)) return@withContext
        if (!hardLimits.checkHardLimits(profile.getMaxDailyBasal(), CoreUiStrings.profile_max_daily_basal_value, 0.02, hardLimits.maxBasal())) return@withContext
        if (!hardLimits.checkHardLimits(ch.fromPump(pump.baseBasalRate), CoreUiStrings.current_basal_value, 0.01, hardLimits.maxBasal())) return@withContext

        // End of check, start gathering data

        val epsProfile = profile as ProfileSealed.EPS
        val dynIsfResult = calculateRawDynIsf(epsProfile)

        // 4.0: the activity classifier scales the final variable sensitivity (only) and can lift
        // the targets. The ISF multiplier runs before the dynIsfMode check, so the value that
        // becomes variable_sens is the value the 2..1000 range check below saw, as in 3.x.
        val useActivityClassifier = preferences.get(BooleanKey.ActivityMonitorDetection) && preferences.get(BooleanKey.ApsAutoIsfUseActivityClassifier)
        val useActivityTargetOffset = useActivityClassifier && preferences.get(BooleanKey.ApsAutoIsfUseActivityTargetOffset)
        var activityClass = ActivityClass.REST
        if (useActivityClassifier) {
            val avgHrForActivity = persistenceLayer.getHeartRatesFromTime(dateUtil.now() - T.mins(15).msecs())
                .map { it.beatsPerMinute }.average().takeIf { !it.isNaN() } ?: 0.0
            val steps15mTotal = StepService.getRecentStepCount5Min() +
                StepService.getRecentStepCount10Min() +
                StepService.getRecentStepCount15Min()
            activityClass = ActivityClassifier.classify(
                steps15m = steps15mTotal,
                avgHrLast15m = avgHrForActivity,
                restingHr = preferences.get(IntKey.ActivityRestingHr)
            )
            aapsLogger.debug(LTag.APS, "ActivityClass: $activityClass steps15m=$steps15mTotal hr=$avgHrForActivity")
            if (activityClass != ActivityClass.REST) {
                dynIsfResult.variableSensitivity = dynIsfResult.variableSensitivity?.times(activityClass.isfMultiplier)
            }
        }

        val dynIsfMode =
            preferences.get(BooleanKey.ApsUseDynamicSensitivity) &&
                hardLimits.checkHardLimits(
                    preferences.get(IntKey.ApsDynIsfAdjustmentFactor).toDouble(),
                    ApsStrings.dyn_isf_adjust_title,
                    IntKey.ApsDynIsfAdjustmentFactor.min.toDouble(),
                    IntKey.ApsDynIsfAdjustmentFactor.max.toDouble()
                ) &&
                hardLimits.checkHardLimits(
                    preferences.get(IntKey.ApsDynIsfVelocity).toDouble(),
                    IntKey.ApsDynIsfVelocity.title,
                    IntKey.ApsDynIsfVelocity.min.toDouble(),
                    IntKey.ApsDynIsfVelocity.max.toDouble()
                ) &&
                hardLimits.checkHardLimits(dynIsfResult.variableSensitivity ?: 0.0, ApsStrings.dynisf_settings_title, HardLimits.LIMIT_ISF)
        val smbEnabled = preferences.get(BooleanKey.ApsUseSmb)
        val advancedFiltering = constraintsChecker.isAdvancedFilteringEnabled().also { inputConstraints.copyReasons(it) }.value()

        val now = dateUtil.now()
        val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
        val currentTemp = CurrentTemp(
            duration = tb?.plannedRemainingMinutes ?: 0,
            rate = tb?.convertedToAbsolute(now, profile) ?: 0.0,
            minutesrunning = tb?.getPassedDurationToTimeInMinutes(now)
        )
        var minBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetLowMgdl(), 0.1), InterfacesStrings.profile_low_target, HardLimits.LIMIT_MIN_BG)
        var maxBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetHighMgdl(), 0.1), InterfacesStrings.profile_high_target, HardLimits.LIMIT_MAX_BG)
        var targetBg = hardLimits.verifyHardLimits(profile.getTargetMgdl(), CoreUiStrings.temp_target_value, HardLimits.LIMIT_TARGET_BG)
        var isTempTarget = false
        persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            isTempTarget = true
            minBg = hardLimits.verifyHardLimits(tempTarget.lowTarget, CoreUiStrings.temp_target_low_target, HardLimits.LIMIT_TEMP_MIN_BG)
            maxBg = hardLimits.verifyHardLimits(tempTarget.highTarget, CoreUiStrings.temp_target_high_target, HardLimits.LIMIT_TEMP_MAX_BG)
            targetBg = hardLimits.verifyHardLimits(tempTarget.target(), CoreUiStrings.temp_target_value, HardLimits.LIMIT_TEMP_TARGET_BG)
        }

        var autosensResult = AutosensResult()

        if (useActivityTargetOffset && activityClass.targetBgOffsetMgdl > 0.0 && !isTempTarget) {
            targetBg = (targetBg + activityClass.targetBgOffsetMgdl).coerceAtMost(maxOf(targetBg, 180.0))
            minBg = (minBg + activityClass.targetBgOffsetMgdl).coerceAtMost(maxOf(minBg, 180.0))
            maxBg = (maxBg + activityClass.targetBgOffsetMgdl).coerceAtMost(maxOf(maxBg, 180.0))
        }

        if (dynIsfMode && !dynIsfResult.tddPartsCalculated() && !preferences.get(BooleanKey.ApsDynIsfUseProfileSens)) {
            notificationManager.post(
                NotificationId.SMB_FALLBACK,
                ApsStrings.fallback_smb_no_tdd,
                level = NotificationLevel.INFO,
                validTo = dateUtil.now() + T.mins(1).msecs())
            inputConstraints.copyReasons(
                ConstraintObject(false, aapsLogger).also {
                    it.set(false, rh.gs(ApsStrings.fallback_smb_no_tdd), this)
                }
            )
            inputConstraints.copyReasons(
                ConstraintObject(false, aapsLogger).apply {
                    set(true, "tdd1D=${dynIsfResult.tdd1D} tdd7D=${dynIsfResult.tdd7D} tddLast4H=${dynIsfResult.tddLast4H} tddLast8to4H=${dynIsfResult.tddLast8to4H} tddLast24H=${dynIsfResult.tddLast24H}", this)
                }
            )
        }
        if (dynIsfMode && dynIsfResult.tddPartsCalculated()) {
            notificationManager.dismiss(NotificationId.SMB_FALLBACK)
            // Compare insulin consumption of last 24h with last 7 days average
            val tddRatio = if (preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity)) dynIsfResult.tddLast24H!! / dynIsfResult.tdd7D!! else 1.0
            // Because consumed carbs affects total amount of insulin compensate final ratio by consumed carbs ratio
            // take only 60% (expecting 40% basal). We cannot use bolus/total because of SMBs
            val carbsRatio = if (
                preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity) &&
                dynIsfResult.tddLast24HCarbs != 0.0 &&
                dynIsfResult.tdd7DDataCarbs != 0.0 &&
                dynIsfResult.tdd7DAllDaysHaveCarbs
            ) ((dynIsfResult.tddLast24HCarbs / dynIsfResult.tdd7DDataCarbs - 1.0) * 0.6) + 1.0 else 1.0
            autosensResult = AutosensResult(
                ratio = tddRatio / carbsRatio,
                ratioFromTdd = tddRatio,
                ratioFromCarbs = carbsRatio
            )
        } else {
            if (constraintsChecker.isAutosensModeEnabled().value()) {
                val autosensData = iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish("OpenAPSPlugin")
                if (autosensData == null) {
                    rxBus.send(EventResetOpenAPSGui(rh.gs(ApsStrings.openaps_no_as_data)))
                    return@withContext
                }
                autosensResult = autosensData.autosensResult
            } else autosensResult.sensResult = "autosens disabled"
        }

        val iobArray = iobCobCalculator.calculateIobArrayForSMB(autosensResult, SMBDefaults.exercise_mode, getHalfBasalExerciseTargetMgdl(), isTempTarget)
        val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()

        val profileIsfForCr = profile.getIsfMgdl("OpenAPSSMBPlugin")
        val profileCrForCycle = profile.getIc()
        val profileIsfForCycle = profile.getProfileIsfMgdl()
        val dynamicCrResult = dynamicCarbRatio.calculate(DynamicCarbRatioPolicy.Input(
            baseCr = profileCrForCycle, glucose = glucoseStatus.glucose, target = targetBg,
            profileIsf = profileIsfForCr, atMs = now),
            knownTdd = if (dynIsfResult.tddPartsCalculated()) DynamicCarbRatioPolicy.TddParts(
                dynIsfResult.tdd7D, dynIsfResult.tdd1D, dynIsfResult.tddLast24H,
                dynIsfResult.tddLast4H, dynIsfResult.tddLast8to4H) else null)
        if (dynamicCrResult.reason.isNotEmpty()) aapsLogger.debug(LTag.APS, dynamicCrResult.reason)

        val oapsProfile = OapsProfile(
            dia = profile.iCfg.dia, // captured insulin DIA; IOB is calculated separately
            min_5m_carbimpact = 0.0, // not used
            max_iob = constraintsChecker.getMaxIOBAllowed().also { inputConstraints.copyReasons(it) }.value(),
            max_daily_basal = profile.getMaxDailyBasal(),
            max_basal = constraintsChecker.getMaxBasalAllowed(profile).also { inputConstraints.copyReasons(it) }.value(),
            min_bg = minBg,
            max_bg = maxBg,
            target_bg = targetBg,
            carb_ratio = dynamicCrResult.carbRatio,
            sens = profileIsfForCr,
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
            half_basal_exercise_target = getHalfBasalExerciseTargetMgdl(),
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
            maxSMBBasalMinutes = (preferences.get(IntKey.ApsMaxMinutesOfBasalToLimitSmb) * activityClass.smbCapFactor).toInt(),
            maxUAMSMBBasalMinutes = preferences.get(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb),
            bolus_increment = pump.pumpDescription.bolusStep,
            carbsReqThreshold = preferences.get(IntKey.ApsCarbsRequestThreshold),
            current_basal = ch.fromPump(activePlugin.activePump.baseBasalRate),
            temptargetSet = isTempTarget,
            autosens_max = preferences.get(DoubleKey.AutosensMax),
            out_units = if (profileFunction.getUnits() == GlucoseUnit.MMOL) "mmol/L" else "mg/dl",
            variable_sens = if (dynIsfMode) dynIsfResult.variableSensitivity ?: 0.0 else 0.0,
            insulinDivisor = dynIsfResult.insulinDivisor,
            TDD = dynIsfResult.tdd ?: 0.0,
            dynIsfBaseSens = dynIsfResult.tdd?.let { DynIsfSensitivity.baseFromTdd(it, dynIsfResult.insulinDivisor) } ?: 0.0,
            use_TDD_for_predictions = !preferences.get(BooleanKey.ApsDynIsfUseProfileSens)
        )
        val microBolusAllowed = constraintsChecker.isSMBModeEnabled(ConstraintObject(tempBasalFallback.not(), aapsLogger)).also { inputConstraints.copyReasons(it) }.value()
        val flatBGsDetected = bgQualityCheck.state == BgQualityCheck.State.FLAT

        // Refuse to run the algorithm with degenerate ISF inputs — division by these would produce NaN/Infinity in the result.
        // carb_ratio feeds csf (sens/carb_ratio) and autosensResult.ratio becomes the non-dynISF sensitivityRatio
        // (sens = profile.sens/sensitivityRatio); a non-finite/≤0 value of either cascades to a NaN carbsReq (round() crash).
        // variable_sens passed the hard-limit range check inside dynIsfMode, which also rejects NaN
        // and the out-of-range values; the raw TDD base is only needed for predictions.
        val invalidInputs = !oapsProfile.sens.isFinite() || oapsProfile.sens <= 0.0 ||
            !oapsProfile.carb_ratio.isFinite() || oapsProfile.carb_ratio <= 0.0 ||
            !autosensResult.ratio.isFinite() || autosensResult.ratio <= 0.0 ||
            (dynIsfMode && (
                !oapsProfile.variable_sens.isFinite() || oapsProfile.variable_sens <= 0.0 ||
                    (oapsProfile.use_TDD_for_predictions == true && (
                        !oapsProfile.dynIsfBaseSens.isFinite() || oapsProfile.dynIsfBaseSens <= 0.0
                        )) ||
                    oapsProfile.insulinDivisor <= 0
                ))
        if (invalidInputs) {
            val msg = "OpenAPS SMB aborting: invalid ISF inputs " +
                "dynIsfMode=$dynIsfMode sens=${oapsProfile.sens} " +
                "carb_ratio=${oapsProfile.carb_ratio} autosensRatio=${autosensResult.ratio} " +
                "variable_sens=${oapsProfile.variable_sens} baseSens=${oapsProfile.dynIsfBaseSens} " +
                "TDD=${oapsProfile.TDD} insulinDivisor=${oapsProfile.insulinDivisor}"
            aapsLogger.error(LTag.APS, msg)
            fabricPrivacy.logException(IllegalStateException(msg))
            rxBus.send(EventResetOpenAPSGui(msg))
            rxBus.send(EventOpenAPSUpdateGui())
            return@withContext
        }

        aapsLogger.debug(LTag.APS, ">>> Invoking determine_basal SMB <<<")
        aapsLogger.debug(LTag.APS, "Glucose status:     $glucoseStatus")
        aapsLogger.debug(LTag.APS, "Current temp:       $currentTemp")
        aapsLogger.debug(LTag.APS, "IOB data:           ${iobArray.joinToString()}")
        aapsLogger.debug(LTag.APS, "Profile:            $oapsProfile")
        aapsLogger.debug(LTag.APS, "Autosens data:      $autosensResult")
        aapsLogger.debug(LTag.APS, "Meal data:          $mealData")
        aapsLogger.debug(LTag.APS, "MicroBolusAllowed:  $microBolusAllowed")
        aapsLogger.debug(LTag.APS, "flatBGsDetected:    $flatBGsDetected")
        aapsLogger.debug(LTag.APS, "DynIsfMode:         $dynIsfMode")

        determineBasalSMB.determine_basal(
            glucose_status = glucoseStatus,
            currenttemp = currentTemp,
            iob_data_array = iobArray,
            profile = oapsProfile,
            autosens_data = autosensResult,
            meal_data = mealData,
            microBolusAllowed = microBolusAllowed,
            currentTime = now,
            flatBGsDetected = flatBGsDetected,
            dynIsfMode = dynIsfMode,
            smbRatio = getSmbRatio(glucoseStatus.glucose, targetBg),
            smbMaxRangeExtension = preferences.get(DoubleKey.ApsAutoIsfSmbMaxRangeExtension)
        ).also {
            // Keep the profile baseline from this cycle, even across later profile switches.
            it.profileIsfMgdl = profileIsfForCycle.takeIf { value -> value.isFinite() && value > 0.0 }
            it.profileCarbRatio = profileCrForCycle.takeIf { value -> value.isFinite() && value > 0.0 }
            if (dynamicCrResult.reason.isNotEmpty()) it.reason.append(" [${dynamicCrResult.reason}]")
            val determineBasalResult = apsResultProvider().with(it)
            // Preserve input data
            determineBasalResult.inputConstraints = inputConstraints
            determineBasalResult.autosensResult = autosensResult
            determineBasalResult.iobData = iobArray
            determineBasalResult.glucoseStatus = glucoseStatus
            determineBasalResult.currentTemp = currentTemp
            determineBasalResult.oapsProfile = oapsProfile
            determineBasalResult.mealData = mealData
            lastAPSResult = determineBasalResult
            lastAPSRun = now
            aapsLogger.debug(LTag.APS, "Result: $it")
            rxBus.send(EventAPSCalculationFinished())
        }

        if (preferences.get(BooleanKey.ActivityMonitorShowStepsFromSmartphone)) {
            val stepsCount = SC(
                duration = 0,
                timestamp = now,
                steps5min = StepService.getRecentStepCount5Min(),
                steps10min = StepService.getRecentStepCount5Min() + StepService.getRecentStepCount10Min(),
                steps15min = StepService.getRecentStepCount5Min() + StepService.getRecentStepCount10Min() + StepService.getRecentStepCount15Min(),
                steps30min = StepService.getRecentStepCount30Min(),
                steps60min = StepService.getRecentStepCount60Min(),
                steps180min = StepService.getRecentStepCount180Min(),
                device = "Smartphone"
            )
            persistenceLayer.insertOrUpdateStepsCounts(listOf(stepsCount))
        }

        rxBus.send(EventOpenAPSUpdateGui())
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
        if (preferences.get(BooleanKey.ApsUseDynamicSensitivity)) {
            // DynISF mode
            if (!preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity))
                value.set(false, rh.gs(ApsStrings.autosens_disabled_in_preferences), this)
        } else {
            // SMB mode
            val enabled = preferences.get(BooleanKey.ApsUseAutosens)
            if (!enabled) value.set(false, rh.gs(ApsStrings.autosens_disabled_in_preferences), this)
        }
        return value
    }

    private fun getSmbRatio(bg: Double, targetBg: Double): Double {
        val fixedRatio = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatio)
        val ratioMin = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioMin)
        val ratioMax = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioMax)
        val ratioBgRange = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange)
            .let { if (it < 10.0) it * Constants.MMOLL_TO_MGDL else it }
        val lowerRatio = minOf(ratioMin, ratioMax)
        val higherRatio = maxOf(ratioMin, ratioMax)

        if (ratioBgRange <= 0.0) return fixedRatio
        if (bg <= targetBg) return lowerRatio
        if (bg >= targetBg + ratioBgRange) return higherRatio
        return (lowerRatio + (higherRatio - lowerRatio) * (bg - targetBg) / ratioBgRange)
            .coerceIn(lowerRatio, higherRatio)
    }

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "openapssmb_settings",
        title = ApsStrings.openapssmb,
        items = listOf(
            DoubleKey.ApsMaxBasal,
            DoubleKey.ApsSmbMaxIob,
            BooleanKey.ApsUseDynamicSensitivity,
            BooleanKey.ApsDynIsfUseProfileSens,
            BooleanKey.ApsUseAutosens,
            IntKey.ApsDynIsfAdjustmentFactor,
            IntKey.ApsDynIsfVelocity,
            UnitDoubleKey.ApsDynIsfBgCap,
            BooleanKey.ApsDynIsfProfilePercentage,
            BooleanKey.ApsDynIsfAdjustSensitivity,
            BooleanKey.ApsAutoIsfHighTtRaisesSens,
            BooleanKey.ApsAutoIsfLowTtLowersSens,
            UnitDoubleKey.ApsAutoIsfHalfBasalExerciseTarget,
            UnitDoubleKey.ApsLgsThreshold,
            BooleanKey.ApsSensitivityRaisesTarget,
            BooleanKey.ApsResistanceLowersTarget,
            BooleanKey.ApsUseSmb,
            BooleanKey.ApsUseSmbWithHighTt,
            BooleanKey.ApsUseSmbAlways,
            BooleanKey.ApsUseSmbWithCob,
            BooleanKey.ApsUseSmbWithLowTt,
            BooleanKey.ApsUseSmbAfterCarbs,
            IntKey.ApsMaxSmbFrequency,
            IntKey.ApsMaxMinutesOfBasalToLimitSmb,
            IntKey.ApsUamMaxMinutesOfBasalToLimitSmb,
            BooleanKey.ApsUseUam,
            IntKey.ApsCarbsRequestThreshold,
            PreferenceSubScreenDef(
                key = "smb_delivery_settings",
                title = ApsStrings.smb_delivery_settings_title,
                items = listOf(
                    DoubleKey.ApsAutoIsfSmbDeliveryRatio,
                    DoubleKey.ApsAutoIsfSmbDeliveryRatioMin,
                    DoubleKey.ApsAutoIsfSmbDeliveryRatioMax,
                    DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange,
                    DoubleKey.ApsAutoIsfSmbMaxRangeExtension
                )
            ),
            PreferenceSubScreenDef(
                key = "absorption_smb_advanced",
                title = CoreUiStrings.advanced_settings_title,
                items = listOf(
                    ApsIntentKey.LinkToDocs,
                    BooleanKey.ApsAlwaysUseShortDeltas,
                    DoubleKey.ApsMaxDailyMultiplier,
                    DoubleKey.ApsMaxCurrentBasalMultiplier
                )
            )
        ),
        icon = pluginDescription.icon
    )

}
