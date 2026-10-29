package app.aaps.plugins.aps.compose

import androidx.compose.runtime.Immutable
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.OapsProfileAutoIsf
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@Immutable
data class OpenAPSUiState(
    val lastRun: String = "",
    val sections: List<OpenAPSSection> = emptyList(),
    val statusMessage: String = "",
    val isRefreshing: Boolean = false
)

@Immutable
data class OpenAPSSection(
    val title: TextRef,
    val rows: List<KeyValueRow> = emptyList(),
    val collapsedByDefault: Boolean = false,
    val isGroupHeader: Boolean = false
)

@Immutable
data class KeyValueRow(
    val key: String,
    val value: String
)

class OpenAPSViewModel(
    private val apsPlugin: APS,
    private val rxBus: RxBus,
    private val rh: TextResolver,
    private val dateUtil: DateUtil,
    private val scope: CoroutineScope,
    glucoseUnit: () -> GlucoseUnit
) {

    private val valueFormatter = OpenAPSValueFormatter(rh, glucoseUnit)

    private val _uiState = MutableStateFlow(OpenAPSUiState())
    val uiState: StateFlow<OpenAPSUiState> = _uiState.asStateFlow()

    init {
        rxBus.toFlow(EventOpenAPSUpdateGui::class)
            .onEach { updateState() }
            .launchIn(scope)

        rxBus.toFlow(EventResetOpenAPSGui::class)
            .onEach { event -> resetState(event.text) }
            .launchIn(scope)

        // Initial state from current data
        scope.launch { updateState() }
    }

    fun onRefresh() {
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        scope.launch {
            apsPlugin.invoke("OpenAPS pull-to-refresh", false)
        }
    }

    private suspend fun updateState() {
        val lastAPSResult = apsPlugin.lastAPSResult
        if (lastAPSResult == null) {
            _uiState.value = OpenAPSUiState(
                statusMessage = rh.gs(CoreUiStrings.not_available_full)
            )
            return
        }

        val sections = buildList {
            // --- Input parameters group ---
            add(OpenAPSSection(title = ApsStrings.openapsma_input_parameters_label, isGroupHeader = true))

            // Constraints
            lastAPSResult.inputConstraints?.let { constraints ->
                val reasons = constraints.getReasons()
                if (reasons.isNotEmpty()) {
                    add(
                        OpenAPSSection(
                            title = ApsStrings.constraints,
                            rows = reasons.split("\n").filter { it.isNotBlank() }.map { KeyValueRow("", it) }
                        )
                    )
                }
            }

            // Glucose Status
            lastAPSResult.glucoseStatus?.let { gs ->
                add(
                    OpenAPSSection(
                        title = ApsStrings.openapsma_glucose_status_label,
                        rows = gs.toRows()
                    )
                )
            }

            // Current Temp
            lastAPSResult.currentTemp?.let { ct ->
                add(
                    OpenAPSSection(
                        title = ApsStrings.openapsma_current_temp_label,
                        rows = ct.toRows()
                    )
                )
            }

            // IOB Data
            lastAPSResult.iob?.let { iob ->
                add(
                    OpenAPSSection(
                        title = ApsStrings.openapsma_iob_data_label,
                        rows = iob.toRows(lastAPSResult.iobData?.size ?: 0)
                    )
                )
            }

            // Profile (OapsProfile or OapsProfileAutoIsf)
            val profileRows = lastAPSResult.oapsProfileAutoIsf?.toRows()
                ?: lastAPSResult.oapsProfile?.toRows(includeAmaFields = lastAPSResult.algorithm == APSResult.Algorithm.AMA)
            if (profileRows != null) {
                add(
                    OpenAPSSection(
                        title = ApsStrings.openapsma_profile_label,
                        rows = profileRows,
                        collapsedByDefault = true
                    )
                )
            }

            // Meal Data
            lastAPSResult.mealData?.let { md ->
                add(
                    OpenAPSSection(
                        title = ApsStrings.openapsma_meal_data_label,
                        rows = md.toRows()
                    )
                )
            }

            // Autosens Data
            lastAPSResult.autosensResult?.let { asr ->
                add(
                    OpenAPSSection(
                        title = ApsStrings.openapsma_autosensdata_label,
                        rows = asr.toRows()
                    )
                )
            }

            // --- Result group ---
            add(OpenAPSSection(title = CoreUiStrings.result, isGroupHeader = true))

            // Script Debug
            lastAPSResult.scriptDebug?.let { debug ->
                if (debug.isNotEmpty()) {
                    add(
                        OpenAPSSection(
                            title = ApsStrings.openapsma_script_debug_data_label,
                            rows = debug.map { KeyValueRow("", it) },
                            collapsedByDefault = true
                        )
                    )
                }
            }

            // Raw Result (RT)
            val rawData = lastAPSResult.rawData()
            if (rawData is RT) {
                add(
                    OpenAPSSection(
                        title = CoreUiStrings.result,
                        rows = rawData.toRows(),
                        collapsedByDefault = true
                    )
                )
            }

            // Request (algorithm decision) — last section
            val requestText = lastAPSResult.resultAsString()
            if (requestText.isNotEmpty()) {
                add(
                    OpenAPSSection(
                        title = ApsStrings.openapsma_request_label,
                        rows = requestText.lines().filter { it.isNotBlank() }.map { KeyValueRow("", it) }
                    )
                )
            }
        }

        _uiState.value = OpenAPSUiState(
            lastRun = dateUtil.dateAndTimeString(apsPlugin.lastAPSRun),
            sections = sections,
            isRefreshing = false
        )
    }

    private fun resetState(text: String) {
        _uiState.value = OpenAPSUiState(
            statusMessage = text,
            isRefreshing = false
        )
    }

    // --- Mapping functions ---

    private fun numberRow(key: String, value: Number) = KeyValueRow(key, valueFormatter.format(key, value))

    private fun GlucoseStatus.toRows(): List<KeyValueRow> = listOf(
        numberRow("glucose", glucose),
        numberRow("delta", delta),
        numberRow("shortAvgDelta", shortAvgDelta),
        numberRow("longAvgDelta", longAvgDelta)
    )

    private fun CurrentTemp.toRows(): List<KeyValueRow> = buildList {
        add(numberRow("duration", duration))
        add(numberRow("rate", rate))
        minutesrunning?.let { add(numberRow("minutesrunning", it)) }
    }

    private fun IobTotal.toRows(arraySize: Int): List<KeyValueRow> = buildList {
        add(KeyValueRow("arraySize", rh.gs(ApsStrings.array_of_elements, arraySize)))
        add(numberRow("iob", iob))
        add(numberRow("activity", activity))
        add(numberRow("bolussnooze", bolussnooze))
        add(numberRow("basaliob", basaliob))
        add(numberRow("netbasalinsulin", netbasalinsulin))
        add(numberRow("hightempinsulin", hightempinsulin))
        add(KeyValueRow("lastBolusTime", if (lastBolusTime > 0) dateUtil.dateAndTimeString(lastBolusTime) else "0"))
        add(numberRow("netInsulin", netInsulin))
        add(numberRow("extendedBolusInsulin", extendedBolusInsulin))
    }

    private fun OapsProfile.toRows(includeAmaFields: Boolean): List<KeyValueRow> = buildList {
        add(numberRow("dia", dia))
        if (includeAmaFields) add(numberRow("min_5m_carbimpact", min_5m_carbimpact))
        add(numberRow("max_iob", max_iob))
        add(numberRow("max_daily_basal", max_daily_basal))
        add(numberRow("max_basal", max_basal))
        add(numberRow("min_bg", min_bg))
        add(numberRow("max_bg", max_bg))
        add(numberRow("target_bg", target_bg))
        add(numberRow("carb_ratio", carb_ratio))
        add(numberRow("sens", sens))
        if (includeAmaFields) add(KeyValueRow("autosens_adjust_targets", autosens_adjust_targets.toString()))
        add(numberRow("max_daily_safety_multiplier", max_daily_safety_multiplier))
        add(numberRow("current_basal_safety_multiplier", current_basal_safety_multiplier))
        add(KeyValueRow("high_temptarget_raises_sensitivity", high_temptarget_raises_sensitivity.toString()))
        add(KeyValueRow("low_temptarget_lowers_sensitivity", low_temptarget_lowers_sensitivity.toString()))
        add(KeyValueRow("sensitivity_raises_target", sensitivity_raises_target.toString()))
        add(KeyValueRow("resistance_lowers_target", resistance_lowers_target.toString()))
        add(KeyValueRow("adv_target_adjustments", adv_target_adjustments.toString()))
        add(KeyValueRow("exercise_mode", exercise_mode.toString()))
        add(numberRow("half_basal_exercise_target", half_basal_exercise_target))
        add(numberRow("maxCOB", maxCOB))
        add(KeyValueRow("skip_neutral_temps", skip_neutral_temps.toString()))
        add(numberRow("remainingCarbsCap", remainingCarbsCap))
        add(KeyValueRow("enableUAM", enableUAM.toString()))
        add(KeyValueRow("A52_risk_enable", A52_risk_enable.toString()))
        add(numberRow("SMBInterval", SMBInterval))
        add(KeyValueRow("enableSMB_with_COB", enableSMB_with_COB.toString()))
        add(KeyValueRow("enableSMB_with_temptarget", enableSMB_with_temptarget.toString()))
        add(KeyValueRow("allowSMB_with_high_temptarget", allowSMB_with_high_temptarget.toString()))
        add(KeyValueRow("enableSMB_always", enableSMB_always.toString()))
        add(KeyValueRow("enableSMB_after_carbs", enableSMB_after_carbs.toString()))
        add(numberRow("maxSMBBasalMinutes", maxSMBBasalMinutes))
        add(numberRow("maxUAMSMBBasalMinutes", maxUAMSMBBasalMinutes))
        add(numberRow("bolus_increment", bolus_increment))
        add(numberRow("carbsReqThreshold", carbsReqThreshold))
        add(numberRow("current_basal", current_basal))
        add(KeyValueRow("temptargetSet", temptargetSet.toString()))
        add(numberRow("autosens_max", autosens_max))
        add(KeyValueRow("out_units", out_units))
        lgsThreshold?.let { add(numberRow("lgsThreshold", it)) }
        add(numberRow("variable_sens", variable_sens))
        add(numberRow("insulinDivisor", insulinDivisor))
        add(numberRow("TDD", TDD))
    }

    private fun OapsProfileAutoIsf.toRows(): List<KeyValueRow> = buildList {
        add(numberRow("dia", dia))
        add(numberRow("max_iob", max_iob))
        add(numberRow("max_daily_basal", max_daily_basal))
        add(numberRow("max_basal", max_basal))
        add(numberRow("min_bg", min_bg))
        add(numberRow("max_bg", max_bg))
        add(numberRow("target_bg", target_bg))
        add(numberRow("carb_ratio", carb_ratio))
        add(numberRow("sens", sens))
        add(numberRow("max_daily_safety_multiplier", max_daily_safety_multiplier))
        add(numberRow("current_basal_safety_multiplier", current_basal_safety_multiplier))
        add(KeyValueRow("high_temptarget_raises_sensitivity", high_temptarget_raises_sensitivity.toString()))
        add(KeyValueRow("low_temptarget_lowers_sensitivity", low_temptarget_lowers_sensitivity.toString()))
        add(KeyValueRow("sensitivity_raises_target", sensitivity_raises_target.toString()))
        add(KeyValueRow("resistance_lowers_target", resistance_lowers_target.toString()))
        add(KeyValueRow("adv_target_adjustments", adv_target_adjustments.toString()))
        add(KeyValueRow("exercise_mode", exercise_mode.toString()))
        add(numberRow("half_basal_exercise_target", half_basal_exercise_target))
        add(numberRow("maxCOB", maxCOB))
        add(KeyValueRow("skip_neutral_temps", skip_neutral_temps.toString()))
        add(numberRow("remainingCarbsCap", remainingCarbsCap))
        add(KeyValueRow("enableUAM", enableUAM.toString()))
        add(KeyValueRow("A52_risk_enable", A52_risk_enable.toString()))
        add(numberRow("SMBInterval", SMBInterval))
        add(KeyValueRow("enableSMB_with_COB", enableSMB_with_COB.toString()))
        add(KeyValueRow("enableSMB_with_temptarget", enableSMB_with_temptarget.toString()))
        add(KeyValueRow("allowSMB_with_high_temptarget", allowSMB_with_high_temptarget.toString()))
        add(KeyValueRow("enableSMB_always", enableSMB_always.toString()))
        add(KeyValueRow("enableSMB_after_carbs", enableSMB_after_carbs.toString()))
        add(numberRow("maxSMBBasalMinutes", maxSMBBasalMinutes))
        add(numberRow("maxUAMSMBBasalMinutes", maxUAMSMBBasalMinutes))
        add(numberRow("bolus_increment", bolus_increment))
        add(numberRow("carbsReqThreshold", carbsReqThreshold))
        add(numberRow("current_basal", current_basal))
        add(KeyValueRow("temptargetSet", temptargetSet.toString()))
        add(numberRow("autosens_max", autosens_max))
        add(KeyValueRow("out_units", out_units))
        lgsThreshold?.let { add(numberRow("lgsThreshold", it)) }
        // AutoISF-specific
        add(numberRow("variable_sens", variable_sens))
        add(KeyValueRow("autoISF_version", autoISF_version))
        add(KeyValueRow("enable_autoISF", enable_autoISF.toString()))
        add(numberRow("autoISF_max", autoISF_max))
        add(numberRow("autoISF_min", autoISF_min))
        add(numberRow("bgAccel_ISF_weight", bgAccel_ISF_weight))
        add(numberRow("bgBrake_ISF_weight", bgBrake_ISF_weight))
        add(numberRow("pp_ISF_weight", pp_ISF_weight))
        add(numberRow("lower_ISFrange_weight", lower_ISFrange_weight))
        add(numberRow("higher_ISFrange_weight", higher_ISFrange_weight))
        add(numberRow("dura_ISF_weight", dura_ISF_weight))
        add(numberRow("smb_delivery_ratio", smb_delivery_ratio))
        add(numberRow("smb_delivery_ratio_min", smb_delivery_ratio_min))
        add(numberRow("smb_delivery_ratio_max", smb_delivery_ratio_max))
        add(numberRow("smb_delivery_ratio_bg_range", smb_delivery_ratio_bg_range))
        add(numberRow("smb_max_range_extension", smb_max_range_extension))
        add(KeyValueRow("enableSMB_EvenOn_OddOff_always", enableSMB_EvenOn_OddOff_always.toString()))
        add(numberRow("iob_threshold_percent", iob_threshold_percent))
        add(numberRow("profile_percentage", profile_percentage))
    }

    private fun MealData.toRows(): List<KeyValueRow> = buildList {
        add(numberRow("carbs", carbs))
        add(numberRow("mealCOB", mealCOB))
        add(numberRow("slopeFromMaxDeviation", slopeFromMaxDeviation))
        add(numberRow("slopeFromMinDeviation", slopeFromMinDeviation))
        add(KeyValueRow("lastBolusTime", if (lastBolusTime > 0) dateUtil.dateAndTimeString(lastBolusTime) else "0"))
        add(KeyValueRow("lastCarbTime", if (lastCarbTime > 0) dateUtil.dateAndTimeString(lastCarbTime) else "0"))
        add(numberRow("usedMinCarbsImpact", usedMinCarbsImpact))
    }

    private fun AutosensResult.toRows(): List<KeyValueRow> = buildList {
        add(numberRow("ratio", ratio))
        add(numberRow("carbsAbsorbed", carbsAbsorbed))
        add(KeyValueRow("sensResult", sensResult))
        add(KeyValueRow("pastSensitivity", pastSensitivity))
        add(KeyValueRow("ratioLimit", ratioLimit))
        add(numberRow("ratioFromTdd", ratioFromTdd))
        add(numberRow("ratioFromCarbs", ratioFromCarbs))
    }

    private fun RT.toRows(): List<KeyValueRow> = buildList {
        add(KeyValueRow("algorithm", algorithm.name))
        add(KeyValueRow("runningDynamicIsf", runningDynamicIsf.toString()))
        timestamp?.let { add(KeyValueRow("timestamp", dateUtil.dateAndTimeString(it))) }
        add(KeyValueRow("temp", temp))
        bg?.let { add(numberRow("bg", it)) }
        tick?.let { add(KeyValueRow("tick", it)) }
        eventualBG?.let { add(numberRow("eventualBG", it)) }
        targetBG?.let { add(numberRow("targetBG", it)) }
        snoozeBG?.let { add(numberRow("snoozeBG", it)) }
        insulinReq?.let { add(numberRow("insulinReq", it)) }
        carbsReq?.let { add(numberRow("carbsReq", it)) }
        carbsReqWithin?.let { add(numberRow("carbsReqWithin", it)) }
        units?.let { add(numberRow("units", it)) }
        deliverAt?.let { add(KeyValueRow("deliverAt", dateUtil.dateAndTimeString(it))) }
        sensitivityRatio?.let { add(numberRow("sensitivityRatio", it)) }
        val reasonStr = reason.toString()
        if (reasonStr.isNotEmpty()) add(KeyValueRow("reason", reasonStr))
        duration?.let { add(numberRow("duration", it)) }
        rate?.let { add(numberRow("rate", it)) }
        COB?.let { add(numberRow("COB", it)) }
        IOB?.let { add(numberRow("IOB", it)) }
        variable_sens?.let { add(numberRow("variable_sens", it)) }
        isfMgdlForCarbs?.let { add(numberRow("isfMgdlForCarbs", it)) }
    }
}
