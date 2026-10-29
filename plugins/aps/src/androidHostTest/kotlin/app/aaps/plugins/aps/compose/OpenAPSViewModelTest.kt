package app.aaps.plugins.aps.compose

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.OapsProfileAutoIsf
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.shared.tests.generatedTextResolver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import java.util.Locale
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever
import org.mockito.kotlin.mock
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import app.aaps.plugins.aps.ApsStrings

internal class OpenAPSViewModelTest {

    @Mock private lateinit var apsPlugin: APS
    @Mock private lateinit var rxBus: RxBus
    @Mock private lateinit var dateUtil: DateUtil

    private val updateGuiFlow = MutableSharedFlow<EventOpenAPSUpdateGui>()
    private val resetGuiFlow = MutableSharedFlow<EventResetOpenAPSGui>()

    private lateinit var previousLocale: Locale

    @AfterEach fun restoreLocale() { Locale.setDefault(previousLocale) }

    @BeforeEach
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        MockitoAnnotations.openMocks(this)
        whenever(rxBus.toFlow(EventOpenAPSUpdateGui::class)).thenReturn(updateGuiFlow)
        whenever(rxBus.toFlow(EventResetOpenAPSGui::class)).thenReturn(resetGuiFlow)
    }

    // Dispatchers.Unconfined runs the VM's launched coroutines (init updateState + onRefresh's
    // apsPlugin.invoke) eagerly and synchronously, so state and interactions settle in-line. The
    // never-completing toFlow subscriptions just park; a standalone scope has no leaked-coroutine check.
    // The resolver is the real one, not a mock. init's updateState() takes the no-result path and builds
    // OpenAPSUiState(statusMessage = rh.gs(...)); a mock answered that with null, which became a
    // non-null-param NPE inside the Unconfined init coroutine. The standalone scope has no leaked-coroutine
    // check, so it escaped this test and tripped "UncaughtExceptionsBeforeTest" in whatever runTest ran next
    // in the same fork. Real text cannot be null, so that whole hazard is gone.
    private fun viewModel() =
        OpenAPSViewModel(apsPlugin, rxBus, generatedTextResolver(), dateUtil, CoroutineScope(Dispatchers.Unconfined)) { GlucoseUnit.MGDL }

    @Test
    fun uiState_showsNotAvailable_whenNoApsResult() {
        whenever(apsPlugin.lastAPSResult).thenReturn(null)

        val sut = viewModel()

        assertThat(sut.uiState.value.statusMessage).isEqualTo("Not available")
        assertThat(sut.uiState.value.sections).isEmpty()
    }

    @Test
    fun onRefresh_setsRefreshingState() {
        whenever(apsPlugin.lastAPSResult).thenReturn(null)

        val sut = viewModel()
        sut.onRefresh()

        // isRefreshing is flipped synchronously; the subsequent apsPlugin.invoke(...) is a
        // fire-and-forget launch, not asserted here to keep the test deterministic.
        assertThat(sut.uiState.value.isRefreshing).isTrue()
    }

    @Test
    fun sharedScreenFormatsAllAlgorithmsWithoutChangingCapturedInputs() = runTest {
        for (algorithm in listOf(APSResult.Algorithm.AMA, APSResult.Algorithm.SMB, APSResult.Algorithm.AUTO_ISF, APSResult.Algorithm.BOOST)) {
            val standard = sampleProfile()
            val autoIsf = sampleAutoIsfProfile()
            val raw = RT(algorithm = algorithm, runningDynamicIsf = false, targetBG = 100.88730399999999, units = 0.025)
            val result = mock<APSResult>()
            whenever(result.algorithm).thenReturn(algorithm)
            whenever(result.oapsProfile).thenReturn(standard.takeUnless { algorithm == APSResult.Algorithm.AUTO_ISF })
            whenever(result.oapsProfileAutoIsf).thenReturn(autoIsf.takeIf { algorithm == APSResult.Algorithm.AUTO_ISF })
            whenever(result.rawData()).thenReturn(raw)
            whenever(result.resultAsString()).thenReturn("")
            whenever(apsPlugin.lastAPSResult).thenReturn(result)
            whenever(dateUtil.dateAndTimeString(0)).thenReturn("date")
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                val sut = OpenAPSViewModel(apsPlugin, rxBus, generatedTextResolver(), dateUtil, scope) { GlucoseUnit.MMOL }
                val profile = sut.uiState.value.sections.single { it.title == ApsStrings.openapsma_profile_label }
                    .rows.associate { it.key to it.value }
                assertThat(profile["target_bg"]).isEqualTo("5.6 mmol/L")
                assertThat(profile["sens"]).isEqualTo("10 mmol/L/U")
                assertThat(profile["max_daily_basal"]).isEqualTo("1.5 U/h")
                assertThat(profile["carb_ratio"]).isEqualTo("9.64 g/U")
                assertThat(profile["dia"]).isEqualTo("5 h")
                assertThat(profile.containsKey("min_5m_carbimpact")).isEqualTo(algorithm == APSResult.Algorithm.AMA)
                val output = sut.uiState.value.sections.last { it.title == app.aaps.core.ui.CoreUiStrings.result }
                    .rows.associate { it.key to it.value }
                assertThat(output["targetBG"]).isEqualTo("5.6 mmol/L")
                assertThat(output["units"]).isEqualTo("0.025 U")
                assertThat(standard.target_bg).isEqualTo(100.88730399999999)
                assertThat(autoIsf.sens).isEqualTo(180.1559)
                assertThat(raw.targetBG).isEqualTo(100.88730399999999)
                assertThat(raw.units).isEqualTo(0.025)
            } finally {
                scope.cancel()
            }
        }
    }

    private fun sampleProfile() = OapsProfile(
        dia = 5.0,
        min_5m_carbimpact = 8.0,
        max_iob = 9.0,
        max_daily_basal = 1.5,
        max_basal = 6.0,
        min_bg = 100.9,
        max_bg = 100.9,
        target_bg = 100.88730399999999,
        carb_ratio = 9.639,
        sens = 180.1559,
        autosens_adjust_targets = false,
        max_daily_safety_multiplier = 0.0,
        current_basal_safety_multiplier = 0.0,
        high_temptarget_raises_sensitivity = false,
        low_temptarget_lowers_sensitivity = false,
        sensitivity_raises_target = false,
        resistance_lowers_target = false,
        adv_target_adjustments = false,
        exercise_mode = false,
        half_basal_exercise_target = 0,
        maxCOB = 0,
        skip_neutral_temps = false,
        remainingCarbsCap = 0,
        enableUAM = false,
        A52_risk_enable = false,
        SMBInterval = 0,
        enableSMB_with_COB = false,
        enableSMB_with_temptarget = false,
        allowSMB_with_high_temptarget = false,
        enableSMB_always = false,
        enableSMB_after_carbs = false,
        maxSMBBasalMinutes = 0,
        maxUAMSMBBasalMinutes = 0,
        bolus_increment = 0.0,
        carbsReqThreshold = 0,
        current_basal = 0.0,
        temptargetSet = false,
        autosens_max = 0.0,
        out_units = "mg/dl",
        lgsThreshold = null,
        variable_sens = 0.0,
        insulinDivisor = 0,
        TDD = 0.0
    )

    private fun sampleAutoIsfProfile() = OapsProfileAutoIsf(
        dia = 5.0,
        min_5m_carbimpact = 8.0,
        max_iob = 9.0,
        max_daily_basal = 1.5,
        max_basal = 6.0,
        min_bg = 100.9,
        max_bg = 100.9,
        target_bg = 100.88730399999999,
        carb_ratio = 9.639,
        sens = 180.1559,
        autosens_adjust_targets = false,
        max_daily_safety_multiplier = 0.0,
        current_basal_safety_multiplier = 0.0,
        high_temptarget_raises_sensitivity = false,
        low_temptarget_lowers_sensitivity = false,
        sensitivity_raises_target = false,
        resistance_lowers_target = false,
        adv_target_adjustments = false,
        exercise_mode = false,
        half_basal_exercise_target = 0,
        maxCOB = 0,
        skip_neutral_temps = false,
        remainingCarbsCap = 0,
        enableUAM = false,
        A52_risk_enable = false,
        SMBInterval = 0,
        enableSMB_with_COB = false,
        enableSMB_with_temptarget = false,
        allowSMB_with_high_temptarget = false,
        enableSMB_always = false,
        enableSMB_after_carbs = false,
        maxSMBBasalMinutes = 0,
        maxUAMSMBBasalMinutes = 0,
        bolus_increment = 0.0,
        carbsReqThreshold = 0,
        current_basal = 0.0,
        temptargetSet = false,
        autosens_max = 0.0,
        out_units = "mg/dl",
        lgsThreshold = null,
        variable_sens = 0.0,
        autoISF_version = "",
        enable_autoISF = false,
        autoISF_max = 0.0,
        autoISF_min = 0.0,
        bgAccel_ISF_weight = 0.0,
        bgBrake_ISF_weight = 0.0,
        pp_ISF_weight = 0.0,
        lower_ISFrange_weight = 0.0,
        higher_ISFrange_weight = 0.0,
        dura_ISF_weight = 0.0,
        smb_delivery_ratio = 0.0,
        smb_delivery_ratio_min = 0.0,
        smb_delivery_ratio_max = 0.0,
        smb_delivery_ratio_bg_range = 0.0,
        smb_max_range_extension = 0.0,
        enableSMB_EvenOn_OddOff_always = false,
        iob_threshold_percent = 0,
        profile_percentage = 0
    )
}
