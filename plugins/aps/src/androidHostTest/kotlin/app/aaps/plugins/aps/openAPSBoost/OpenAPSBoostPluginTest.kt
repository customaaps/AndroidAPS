package app.aaps.plugins.aps.openAPSBoost

import app.aaps.core.data.model.*
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.aps.*
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.EnforcedState
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpWithConcentration
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.plugins.boostport.aps.*
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
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
    private var duringCalculation: (() -> Unit)? = null
    private var reject = false
    private var nightModeActive = false
    private val factory = object : BoostApsCalculatorFactory {
        override val ownedKeys = emptyList<app.aaps.core.keys.interfaces.NonPreferenceKey>()
        override fun create(): BoostApsCalculator {
            owners++
            return object : BoostApsCalculator {
                override suspend fun calculate(input: BoostApsCalculator.Input): BoostApsCalculator.Result {
                    requests.add(input)
                    duringCalculation?.invoke()
                    if (reject) return BoostApsCalculator.Result.Rejected("test rejection")
                    return BoostApsCalculator.Result.Calculated(RT(algorithm = APSResult.Algorithm.BOOST,
                        runningDynamicIsf = true, timestamp = input.atMs, units = if (input.microBolusAllowed) 0.2 else 0.0,
                        rate = 1.0, duration = 30, profileCarbRatio = input.originalProfileCr,
                        profileIsfMgdl = input.originalProfileIsf, effectiveIsf = 40.0), input.profile, input.autosens, nightModeActive = nightModeActive)
                }
            }
        }
    }
    private lateinit var sut: OpenAPSBoostPlugin

    @BeforeEach fun prepare() {
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
    }

    @Test fun integrationPluginIsAnApsButRemainsDisabledUntilRemainingOwnersAreConnected() {
        assertThat(sut.getType()).isEqualTo(PluginType.APS)
        assertThat(sut.enforcedState()).isEqualTo(EnforcedState.Disabled)
        assertThat(sut.pluginDescription.showInList()).isFalse()
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
}
