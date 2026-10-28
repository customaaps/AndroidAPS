package app.aaps.plugins.aps.openAPSBoost

import app.aaps.core.data.model.*
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.aps.*
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpWithConcentration
import app.aaps.core.keys.BooleanKey
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.plugins.boostport.aps.*
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

class OpenAPSBoostPluginTest : TestBaseWithProfile() {
    private val persistenceLayer: PersistenceLayer = mock()
    private val glucoseProvider: GlucoseStatusProvider = mock()
    private val quality: BgQualityCheck = mock()
    private val requests = mutableListOf<BoostApsCalculator.Input>()
    private val teEvents = MutableSharedFlow<List<TE>>(extraBufferCapacity = 1)
    private val calEvents = MutableSharedFlow<List<CAL>>(extraBufferCapacity = 1)
    private var owners = 0
    private var duringCalculation: (suspend () -> Unit)? = null
    private var closedOwners = 0
    private var failClose = false
    private var reject = false
    private var nightModeActive = false
    private var hrDarkMinutes: Long? = null
    private var configNotice: BoostAutoConfigNotice? = null
    private val factory = object : BoostApsCalculatorFactory {
        override val ownedKeys = emptyList<app.aaps.core.keys.interfaces.NonPreferenceKey>()
        override fun create(): BoostApsCalculator {
            owners++
            return object : BoostApsCalculator {
                override fun close() {
                    closedOwners++
                    if (failClose) error("platform cleanup failed")
                }
                override suspend fun calculate(input: BoostApsCalculator.Input): BoostApsCalculator.Result {
                    requests.add(input)
                    duringCalculation?.invoke()
                    if (reject) return BoostApsCalculator.Result.Rejected("test rejection")
                    return BoostApsCalculator.Result.Calculated(RT(algorithm = APSResult.Algorithm.BOOST,
                        runningDynamicIsf = true, timestamp = input.atMs, units = if (input.microBolusAllowed) 0.2 else 0.0,
                        rate = 1.0, duration = 30, profileCarbRatio = input.originalProfileCr,
                        profileIsfMgdl = input.originalProfileIsf, effectiveIsf = 40.0), input.profile, input.autosens, nightModeActive = nightModeActive, hrFeedDarkMinutes = hrDarkMinutes, autoConfigNotice = configNotice)
                }
            }
        }
    }
    private lateinit var sut: OpenAPSBoostPlugin

    @BeforeEach fun prepare() {
        whenever(config.APS).thenReturn(true)
        whenever(config.AAPSCLIENT).thenReturn(false)
        whenever(preferences.get(BooleanKey.ApsUseSmb)).thenReturn(true)
        val ads = mock<AutosensDataStore>()
        whenever(ads.detectedCadenceMinutes).thenReturn(5.0)
        whenever(iobCobCalculator.ads).thenReturn(ads)
        doReturn("Boost V6").whenever(rh).gs(app.aaps.plugins.aps.ApsStrings.openapsboost)
        doReturn("APS disabled").whenever(rh).gs(app.aaps.plugins.aps.ApsStrings.openapsma_disabled)
        val pump: PumpWithConcentration = mock()
        whenever(pump.baseBasalRate).thenReturn(PumpRate(1.0))
        whenever(pump.pumpDescription).thenReturn(testPumpPlugin.pumpDescription)
        whenever(activePlugin.activePump).thenReturn(pump)
        val glucose: GlucoseStatus = mock()
        whenever(glucose.glucose).thenReturn(150.0)
        whenever(glucose.date).thenReturn(now)
        whenever(glucoseProvider.glucoseStatusData).thenReturn(glucose)
        whenever(quality.state).thenReturn(BgQualityCheck.State.FIVE_MIN_DATA)
        whenever(constraintsChecker.isAutosensModeEnabled()).thenReturn(ConstraintObject(false, aapsLogger))
        whenever(constraintsChecker.getMaxBasalAllowed(any())).thenReturn(ConstraintObject(3.0, aapsLogger))
        whenever(constraintsChecker.getMaxBolusAllowed()).thenReturn(ConstraintObject(5.0, aapsLogger))
        whenever(constraintsChecker.isUAMEnabled()).thenReturn(ConstraintObject(true, aapsLogger))
        whenever(persistenceLayer.observeChanges(TE::class)).thenReturn(teEvents)
        whenever(persistenceLayer.observeChanges(CAL::class)).thenReturn(calEvents)
        runBlocking {
            whenever(profileFunction.getProfile()).thenReturn(effectiveProfile)
            whenever(profileFunction.getUnits()).thenReturn(GlucoseUnit.MGDL)
            whenever(constraintsChecker.getMaxIOBAllowed()).thenReturn(ConstraintObject(5.0, aapsLogger))
            whenever(constraintsChecker.isAdvancedFilteringEnabled()).thenReturn(ConstraintObject(true, aapsLogger))
            whenever(constraintsChecker.isSMBModeEnabled(any())).thenReturn(ConstraintObject(true, aapsLogger))
            whenever(iobCobCalculator.calculateIobArrayForSMB(any(), any(), any(), any())).thenReturn(arrayOf(IobTotal(now)))
            whenever(iobCobCalculator.getMealDataWithWaitingForCalculationFinish()).thenReturn(MealData())
            whenever(persistenceLayer.getRunningModeActiveAt(any())).thenReturn(RM(timestamp = now, mode = RM.Mode.CLOSED_LOOP, duration = 0))
        }
        sut = spy(OpenAPSBoostPlugin(aapsLogger, rxBus, constraintsChecker, rh, config, profileFunction,
            activePlugin, iobCobCalculator, processedTbrEbData, hardLimits, dateUtil, persistenceLayer,
            glucoseProvider, preferences, factory, profileUtil, quality, glucoseStatusCalculatorSMB,
            { apsResultProvider() }, ch, fabricPrivacy, notificationManager))
        doReturn(true).whenever(sut).isEnabled()
        runBlocking { sut.onStart() }
    }

    @AfterEach fun stopOwner() = runBlocking { sut.onStop() }

    @Test fun boostIsSelectableWithoutForcingOrChangingTheDefaultAlgorithm() {
        assertThat(sut.getType()).isEqualTo(PluginType.APS)
        assertThat(sut.enforcedState()).isNull()
        assertThat(sut.isDefault()).isFalse()
        assertThat(sut.isEnabled(PluginType.APS)).isFalse()
        assertThat(sut.pluginDescription.showInList()).isTrue()
        whenever(config.APS).thenReturn(false)
        whenever(config.AAPSCLIENT).thenReturn(true)
        assertThat(sut.pluginDescription.showInList()).isTrue()
        whenever(config.AAPSCLIENT).thenReturn(false)
        assertThat(sut.pluginDescription.showInList()).isFalse()
    }

    @Test fun explicitSelectionRunsTheApsPathAndDisablingStopsIt(): Unit = runBlocking {
        doCallRealMethod().whenever(sut).isEnabled()
        sut.invoke("not selected", false)
        assertThat(requests).isEmpty()
        sut.setPluginEnabledAwaiting(PluginType.APS, true)
        assertThat(sut.isEnabled()).isTrue()
        sut.invoke("selected", false)
        assertThat(requests).hasSize(1)
        assertThat(sut.lastAPSResult?.algorithm).isEqualTo(APSResult.Algorithm.BOOST)
        assertThat(sut.lastAPSResult?.smb).isEqualTo(0.2)
        assertThat(sut.lastAPSResult?.inputConstraints).isNotNull()
        sut.setPluginEnabledAwaiting(PluginType.APS, false)
        sut.invoke("disabled", false)
        assertThat(requests).hasSize(1)
        assertThat(sut.lastAPSResult).isNull()
        assertThat(closedOwners).isEqualTo(1)
    }

    @Test fun calculationPublishesStandardApsResultAndPreservesInputConstraints() = runBlocking {
        sut.invoke("test", false)
        assertThat(requests).hasSize(1)
        assertThat(requests.single().profile.max_iob).isEqualTo(5.0)
        assertThat(requests.single().profile.max_basal).isEqualTo(3.0)
        assertThat(sut.lastAPSResult?.algorithm).isEqualTo(APSResult.Algorithm.BOOST)
        assertThat(sut.lastAPSResult?.inputConstraints).isNotNull()
        assertThat(sut.lastAPSResult?.oapsProfile).isNotNull()
        assertThat(sut.lastAPSRun).isEqualTo(now)
    }

    @Test fun nightPolicyExplanationUsesLocalizedPresentationResource() = runBlocking {
        val localized = "Ночной режим Boost: SMB отключены; "
        doReturn(localized).whenever(rh).gs(app.aaps.plugins.aps.ApsStrings.boost_night_mode_smb_disabled)
        nightModeActive = true
        sut.invoke("night presentation", false)
        assertThat(sut.lastAPSResult?.reason).contains(localized.trim())
    }

    @Test fun feedWarningUsesLocalizedNotificationAfterValidPublication(): Unit = runBlocking {
        val text = "Boost: пульс не поступает 60 min"
        doReturn(text).whenever(rh).gs(app.aaps.plugins.aps.ApsStrings.boost_hr_feed_dark, 60L)
        hrDarkMinutes = 60L
        sut.invoke("feed warning", false)
        verify(notificationManager).post(app.aaps.core.interfaces.notifications.NotificationId.BOOST_HR_FEED_DARK,
            text, level = app.aaps.core.interfaces.notifications.NotificationLevel.LOW, validMinutes = 0)
    }

    @Test fun configurationNoticeUsesLocalizedResourceAndCapturesConstrainedBolusLimit(): Unit = runBlocking {
        val text = "Настройки Boost: настроено 8, скорректировано 2; отложено 1"
        configNotice = BoostAutoConfigNotice(8, 2, 1)
        doReturn(text).whenever(rh).gs(app.aaps.plugins.aps.ApsStrings.boost_auto_config_notice, 8, 2, 1)
        sut.invoke("configuration notice", false)
        assertThat(requests.last().maxBolusAllowed).isEqualTo(5.0)
        verify(notificationManager).post(app.aaps.core.interfaces.notifications.NotificationId.BOOST_AUTOCONFIG,
            text, level = app.aaps.core.interfaces.notifications.NotificationLevel.INFO, validMinutes = 0)
    }

    @Test fun importDuringConfigurationDoesNotPublishStaleNotice(): Unit = runBlocking {
        configNotice = BoostAutoConfigNotice(8, 0, 0)
        duringCalculation = { sut.beforeImport() }
        sut.invoke("stale configuration", false)
        verify(notificationManager, never()).post(
            id = eq(app.aaps.core.interfaces.notifications.NotificationId.BOOST_AUTOCONFIG),
            text = any<String>(), level = any<app.aaps.core.interfaces.notifications.NotificationLevel>(),
            validMinutes = any<Int>(), sound = anyOrNull(), actions = any(), validityCheck = anyOrNull())
    }

    @Test fun originalNightProfileTargetIsCapturedBeforeTemporaryTargetReplacement() = runBlocking {
        val original = effectiveProfile.getTargetMgdl()
        whenever(persistenceLayer.getTemporaryTargetActiveAt(any())).thenReturn(TT(
            timestamp = now, reason = TT.Reason.EATING_SOON, highTarget = 72.0, lowTarget = 72.0,
            duration = 3_600_000L))
        sut.invoke("night target capture", false)
        val request = requests.last()
        assertThat(request.originalProfileTargetMgDl).isEqualTo(original)
        assertThat(request.profile.target_bg).isEqualTo(72.0)
        assertThat(request.profile.temptargetSet).isTrue()
        assertThat(request.originalProfileTargetMgDl).isNotEqualTo(request.profile.target_bg)
    }

    @Test fun rejectedCycleClearsPreviousResultAndImportDiscardsInFlightResult() = runBlocking {
        sut.invoke("first", false)
        assertThat(sut.lastAPSResult).isNotNull()
        reject = true
        sut.invoke("rejected", false)
        assertThat(sut.lastAPSResult).isNull()
        reject = false
        duringCalculation = { sut.afterImport() }
        sut.invoke("import", false)
        assertThat(sut.lastAPSResult).isNull()
        duringCalculation = null
        sut.invoke("restored", false)
        assertThat(owners).isEqualTo(2)
    }

    @Test fun calibrationBlocksSmbAndFallbackCannotBeUndoneByAnAllowingConstraint() = runBlocking {
        sut.invoke("fallback", true)
        assertThat(requests.last().microBolusAllowed).isFalse()
        sut.onStart()
        calEvents.emit(listOf(CAL(timestamp = now, fingerstickMgdl = 150.0, sensorMgdlAtPairing = 145.0)))
        for (attempt in 0..20) {
            sut.invoke("calibration", false)
            if (!requests.last().microBolusAllowed) break
            delay(10)
        }
        assertThat(requests.last().microBolusAllowed).isFalse()
        sut.onStop()
        val requestsBefore = requests.size
        sut.invoke("stopped", false)
        assertThat(requests).hasSize(requestsBefore)
        sut.onStart()
        sut.invoke("fallback", true)
        assertThat(requests.last().microBolusAllowed).isFalse()
    }

    @Test fun importDuringInputCollectionPreventsCreatingAnOwnerWithStaleInputs() = runBlocking {
        whenever(persistenceLayer.getTemporaryTargetActiveAt(any())).thenAnswer {
            sut.beforeImport()
            null
        }
        sut.invoke("import during input collection", false)
        assertThat(owners).isEqualTo(0)
        assertThat(requests).isEmpty()
        assertThat(sut.lastAPSResult).isNull()
    }

    @Test fun importWindowBlocksNewOwnersUntilPreferencesHaveBeenReplaced(): Unit = runBlocking {
        sut.beforeImport()
        sut.invoke("during import", false)
        assertThat(owners).isEqualTo(0)
        assertThat(requests).isEmpty()
        sut.afterImport()
        sut.invoke("after import", false)
        assertThat(owners).isEqualTo(1)
        assertThat(sut.lastAPSResult).isNotNull()
    }

    @Test fun stopBlocksQueuedInvocationsAndRestartCreatesExactlyOneFreshOwner(): Unit = runBlocking {
        sut.invoke("before stop", false)
        sut.onStop()
        val previousRequests = requests.size
        sut.invoke("stopped", false)
        assertThat(requests).hasSize(previousRequests)
        assertThat(sut.lastAPSResult).isNull()
        assertThat(sut.lastAPSRun).isEqualTo(0L)
        assertThat(closedOwners).isEqualTo(1)
        sut.onStart()
        sut.invoke("restarted", false)
        sut.invoke("reuse", false)
        assertThat(owners).isEqualTo(2)
        assertThat(closedOwners).isEqualTo(1)
    }

    @Test fun restartDuringImportDoesNotReopenCalculationEarly(): Unit = runBlocking {
        sut.beforeImport()
        sut.onStop()
        sut.onStart()
        sut.invoke("restart inside import", false)
        assertThat(owners).isEqualTo(0)
        sut.afterImport()
        sut.invoke("import complete", false)
        assertThat(owners).isEqualTo(1)
    }

    @Test fun cleanupFailureStillDetachesTheOwnerAndAllowsImportToComplete(): Unit = runBlocking {
        sut.invoke("before import", false)
        failClose = true
        sut.beforeImport()
        assertThat(sut.lastAPSResult).isNull()
        assertThat(sut.lastAPSRun).isEqualTo(0L)
        sut.invoke("blocked", false)
        assertThat(owners).isEqualTo(1)
        sut.afterImport()
        failClose = false
        sut.invoke("new settings", false)
        assertThat(owners).isEqualTo(2)
        assertThat(closedOwners).isEqualTo(1)
    }

    @Test fun suspendedPreImportResultAndNoticesCannotCrossIntoTheNewOwner(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        configNotice = BoostAutoConfigNotice(2, 0, 0)
        hrDarkMinutes = 60L
        duringCalculation = { entered.complete(Unit); release.await() }
        val oldCycle = launch(Dispatchers.Default) { sut.invoke("old cycle", false) }
        entered.await()
        sut.beforeImport()
        release.complete(Unit)
        oldCycle.join()
        assertThat(sut.lastAPSResult).isNull()
        verify(notificationManager, never()).post(id = eq(app.aaps.core.interfaces.notifications.NotificationId.BOOST_AUTOCONFIG),
            text = any<String>(), level = any<app.aaps.core.interfaces.notifications.NotificationLevel>(),
            validMinutes = any<Int>(), sound = anyOrNull(), actions = any(), validityCheck = anyOrNull())
        duringCalculation = null
        configNotice = null
        hrDarkMinutes = null
        sut.afterImport()
        sut.invoke("new cycle", false)
        assertThat(owners).isEqualTo(2)
        assertThat(sut.lastAPSResult).isNotNull()
    }

    @Test fun stoppedPluginCannotPublishASuspendedResult(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        duringCalculation = { entered.complete(Unit); release.await() }
        val oldCycle = launch(Dispatchers.Default) { sut.invoke("old cycle", false) }
        entered.await()
        sut.onStop()
        release.complete(Unit)
        oldCycle.join()
        assertThat(sut.lastAPSResult).isNull()
        assertThat(sut.lastAPSRun).isEqualTo(0L)
        assertThat(closedOwners).isEqualTo(1)
    }

    @Test fun globalSmbDisableCannotBeUndoneByAnAllowingConstraint(): Unit = runBlocking {
        whenever(preferences.get(BooleanKey.ApsUseSmb)).thenReturn(false)
        sut.invoke("SMB disabled", false)
        assertThat(requests.single().microBolusAllowed).isFalse()
        assertThat(sut.lastAPSResult?.smb).isEqualTo(0.0)
    }

    @Test fun smbConstraintDenialIsCarriedIntoTheV6Calculator(): Unit = runBlocking {
        val denied = ConstraintObject(false, aapsLogger)
        whenever(constraintsChecker.isSMBModeEnabled(any())).thenReturn(denied)
        sut.invoke("SMB denied", false)
        assertThat(requests.single().microBolusAllowed).isFalse()
        assertThat(sut.lastAPSResult?.smb).isEqualTo(0.0)
    }
}
