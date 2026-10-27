package app.aaps.plugins.boostport.aps

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.aps.DynamicCarbRatioPolicy
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSBoost.*
import app.aaps.plugins.aps.openAPSBoostV5.MEAL_ANNOUNCED_BOLUS_WINDOW_MS
import app.aaps.plugins.aps.openAPSBoost.activity.*
import app.aaps.plugins.aps.openAPSBoost.replay.*
import app.aaps.plugins.boostport.telemetry.BoostMlAssetParser
import app.aaps.plugins.boostport.telemetry.BoostMlAssetSchemaAudit
import dev.zacsweers.metro.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class BoostApsCalculatorFactoryImpl(
    private val persistence: PersistenceLayer,
    private val tdd: TddCalculator,
    private val dynamicCr: DynamicCarbRatioPolicy,
    private val storage: KeyValueStore,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val profileUtil: ProfileUtil,
    private val modelSource: BoostApsModelSource? = null,
) : BoostApsCalculatorFactory {
    override val ownedKeys = BoostApsSettingKeys.all
    override fun create(): BoostApsCalculator = Calculator()

    /** One sequential owner, recreated by the APS plugin after stop/import. */
    private inner class Calculator : BoostApsCalculator {
        private val mutex = Mutex()
        private val storage = BoostOwnerStorage(this@BoostApsCalculatorFactoryImpl.storage)
        private val settings = BoostApsRuntimeSettings(storage)
        private var session: BoostReplaySession? = null
        private val clock = object : V1ReplayClock {
            override fun hourAt(timestampMs: Long): Int = (localMinute(timestampMs) / 60).toInt()
            override fun minuteAt(timestampMs: Long): Int = (localMinute(timestampMs) % 60).toInt()
        }
        override fun close() = storage.close()
        private fun localMinute(atMs: Long): Long =
            ((atMs / 60_000L + dateUtil.getTimeZoneOffsetMinutes(atMs)) % 1440 + 1440) % 1440

        override suspend fun calculate(input: BoostApsCalculator.Input): BoostApsCalculator.Result = mutex.withLock {
            try {
                require(input.atMs > 0 && input.iob.isNotEmpty()) { "missing Boost time/IOB" }
                require(listOf(input.originalProfileCr, input.originalProfileIsf, input.profile.sens,
                    input.profile.current_basal, input.profile.max_basal, input.profile.max_iob,
                    input.glucose.glucose, input.glucose.delta, input.glucose.shortAvgDelta,
                    input.glucose.longAvgDelta, input.glucose.noise, input.autosens.ratio).all { it.isFinite() }) { "invalid Boost inputs" }
                require(input.originalProfileCr > 0 && input.originalProfileIsf > 0 && input.profile.sens > 0 &&
                    input.profilePercent > 0 && input.autosens.ratio > 0) { "invalid Boost profile" }
                val unsupported = settings.unsupportedEnabledFeatures()
                if (unsupported.isNotEmpty()) return@withLock BoostApsCalculator.Result.Rejected(
                    "Boost context integration pending: ${unsupported.joinToString()}")
                reconcileSettings()
                val sleepRaw = settings.s(BoostApsSettingKeys.ApsBoostSleepState)
                if (sleepRaw.isNotEmpty()) Json.parseToJsonElement(sleepRaw).jsonObject
                val sleep = SleepStateDetector.State.deserialize(sleepRaw)
                val activitySettings = settings.activity()
                val snapshot = BoostActivitySnapshot(dateUtil.beginOfDay(input.atMs),
                    "${clock.hourAt(input.atMs)}:${clock.minuteAt(input.atMs).toString().padStart(2, '0')}",
                    0, 0, 0, 0, false, sleep, null, null)
                val context = BoostProfileContext(input.atMs, input.glucose.glucose, input.profilePercent,
                    input.profile.target_bg, input.profile.min_bg, input.profile.max_bg, input.profile.target_bg,
                    input.insulinPeak, false, settings.d(BoostApsSettingKeys.ApsBoostBolus),
                    settings.d(BoostApsSettingKeys.ApsBoostMaxIob), settings.d(BoostApsSettingKeys.ApsBoostInsulinReqPct),
                    settings.d(BoostApsSettingKeys.ApsBoostScale), settings.d(BoostApsSettingKeys.ApsBoostPercentScale),
                    settings.b(BoostApsSettingKeys.ApsBoostEnablePercentScale), settings.b(BoostApsSettingKeys.ApsBoostEnableCircadianIsf),
                    activitySettings.allowBoostWithHighTt, "none")
                val isfSettings = BoostIsfSettings(input.profile.autosens_max, preferences.get(DoubleKey.AutosensMin),
                    settings.b(BoostApsSettingKeys.ApsBoostUseTdd), settings.d(BoostApsSettingKeys.ApsBoostDynIsfVelocity) / 100.0,
                    settings.d(BoostApsSettingKeys.ApsBoostDynIsfBgCap), activitySettings.dynIsfNormalTarget,
                    input.profile.high_temptarget_raises_sensitivity, input.profile.low_temptarget_lowers_sensitivity,
                    input.profile.half_basal_exercise_target, settings.b(BoostApsSettingKeys.ApsBoostAdjustSensitivity),
                    settings.i(BoostApsSettingKeys.ApsBoostDynIsfAdjustmentFactor), settings.b(BoostApsSettingKeys.ApsBoostAutosensWhenNoTdd))
                val cycle = BoostTargetCycleAssembler(persistence, tdd, dynamicCr).assembleWithActivity(
                    BoostTargetCycleAssembler.Inputs(input.glucose, input.currentTemp, input.iob, input.meal,
                        input.profile, input.originalProfileCr, input.autosens, isfSettings, context,
                        input.microBolusAllowed, input.flatBg, settings.d(BoostApsSettingKeys.ApsBoostCumulativeSmbCap60Min)),
                    activitySettings, snapshot, { value -> value?.let { activityFormat("%.1f", it) } ?: "null" },
                    resolveRecoveryAndMealContext = { c, _ -> c })
                val manualBolus = persistence.getBolusesFromTimeToTime(input.atMs - MEAL_ANNOUNCED_BOLUS_WINDOW_MS, input.atMs, true)
                    .any { it.type == BS.Type.NORMAL && it.isValid && it.amount > 0.0 }
                val v5 = V5ReplayContext(input.profile.bolus_increment, activeMode = true,
                    asleep = sleep.state == SleepStateDetector.SleepState.SLEEPING,
                    profileSwitched = input.profileSwitched, pumpDisconnected = input.pumpDisconnected,
                    loopSuspended = input.loopSuspended, manualBolusInAnnouncementWindow = manualBolus,
                    fastCarbConfirmEnabled = settings.b(BoostApsSettingKeys.ApsBoostV5FastCarbConfirm),
                    aggressiveEarlyConfirmEnabled = settings.b(BoostApsSettingKeys.ApsBoostV5AggressiveEarlyConfirm),
                    aggression = settings.d(BoostApsSettingKeys.ApsBoostV5Aggression),
                    hypoCaution = settings.d(BoostApsSettingKeys.ApsBoostV5HypoCaution),
                    sensitivity = settings.d(BoostApsSettingKeys.ApsBoostV5Sensitivity),
                    confirmedCapU = settings.d(BoostApsSettingKeys.ApsBoostV5ConfirmedCapU),
                    committedCapU = settings.d(BoostApsSettingKeys.ApsBoostV5CommittedCapU),
                    primerCapU = settings.d(BoostApsSettingKeys.ApsBoostV5PrimerCapU),
                    primerTbrFallback = settings.b(BoostApsSettingKeys.ApsBoostV5PrimerTbrFallback),
                    primerBolusMode = settings.b(BoostApsSettingKeys.ApsBoostV5PrimerBolusMode))
                val v6 = V6ReplaySettings(settings.b(BoostApsSettingKeys.ApsBoostV5ConfirmTranche),
                    settings.d(BoostApsSettingKeys.ApsBoostV5TrancheFraction), settings.d(BoostApsSettingKeys.ApsBoostV5TrancheThreshold))
                val runner = session ?: createSession().also { session = it }
                when (val calculated = runner.run(cycle, v5, v6)) {
                    is BoostReplaySession.Result.Rejected -> BoostApsCalculator.Result.Rejected(calculated.reason)
                    is BoostReplaySession.Result.Calculated -> {
                        if (!calculated.persistence.persisted) return@withLock BoostApsCalculator.Result.Rejected(
                            calculated.persistence.problem ?: "Boost state was not saved")
                        val result = BoostApsResultMapper.toRt(calculated.frame.finalCandidate, input)
                        BoostApsCalculator.Result.Calculated(result, input.profile.copy(
                            current_basal = cycle.profile.current_basal, carb_ratio = result.carbRatio ?: input.originalProfileCr,
                            variable_sens = result.variable_sens ?: 0.0, TDD = cycle.profile.TDD,
                            insulinDivisor = cycle.profile.insulinDivisor), cycle.autosens.copy())
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                BoostApsCalculator.Result.Rejected("Boost calculation rejected: ${error.message ?: error::class.simpleName}")
            }
        }

        private fun reconcileSettings() {
            val useTdd = settings.b(BoostApsSettingKeys.ApsBoostUseTdd)
            val transition = boostTddSettingsTransition(useTdd, settings.b(BoostApsSettingKeys.ApsBoostAdjustSensitivity),
                settings.d(BoostApsSettingKeys.ApsBoostDynIsfVelocity),
                boostSwitchedToV6(settings.s(BoostApsSettingKeys.ApsBoostLastEngineMode), true),
                boostTddJustEnabled(settings.s(BoostApsSettingKeys.ApsBoostLastUseTdd), useTdd))
            if (transition.clearedAdjustSens) storage.putBoolean(BoostApsSettingKeys.ApsBoostAdjustSensitivity.key, false)
            if (transition.zeroedVelocity || transition.restoredVelocity)
                storage.putDouble(BoostApsSettingKeys.ApsBoostDynIsfVelocity.key, transition.velocityPct)
            storage.putString(BoostApsSettingKeys.ApsBoostLastEngineMode.key, "v6")
            storage.putString(BoostApsSettingKeys.ApsBoostLastUseTdd.key, useTdd.toString())
        }

        private fun createSession(): BoostReplaySession {
            val key = BoostApsSettingKeys.Session.key
            if (storage.getString(key, "").isEmpty()) {
                val legacy = BoostReplayStateCodec.fromLegacy(settings.s(BoostApsSettingKeys.ApsBoostMlRingBuffer),
                    settings.s(BoostApsSettingKeys.ApsBoostV5State)) ?: error("invalid source Boost state")
                storage.putString(key, checkNotNull(BoostReplayStateCodec.encode(legacy)))
            }
            fun model(kind: BoostMlAssetSchemaAudit.Kind, path: String) = modelSource?.readAsset(path)?.let {
                (BoostMlAssetParser.parse(kind, it) as? BoostMlAssetParser.Result.Ready)?.model
            }
            val risk = model(BoostMlAssetSchemaAudit.Kind.HYPO_RISK_V12, "boost/hypo_risk_model.json")?.let(V1ReplayRiskTreeModel::from)
            val meal = model(BoostMlAssetSchemaAudit.Kind.MEAL_LIKELIHOOD, "boost/meal_likelihood_model.json")?.let(V1ReplayMealTreeModel::from)
            val presentation = object : V1ReplayPresentation {
                override fun glucose(value: Double) = profileUtil.fromMgdlToStringInUnits(value)
                override fun fixed2(value: Double) = app.aaps.core.data.format.NumberFormat.DECIMAL_2_UP_TO_3.format(value)
                override fun withoutZeros(value: Double): String = app.aaps.core.data.format.NumberFormat.UP_TO_2_DECIMALS.format(value)
            }
            return BoostReplaySession(BoostV1V5Replay(presentation, clock, risk, meal), BoostReplayStateStore(storage, key))
        }
    }
}
