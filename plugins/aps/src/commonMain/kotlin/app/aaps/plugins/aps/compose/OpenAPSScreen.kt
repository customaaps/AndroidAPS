package app.aaps.plugins.aps.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsCard
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.aps.ApsStrings

@Composable
fun OpenAPSScreen(
    state: OpenAPSUiState,
    onRefresh: () -> Unit
) {
    // Track expanded state per section; key = title
    val expandedState = remember { mutableStateMapOf<Int, Boolean>() }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Status message when no data
            if (state.sections.isEmpty() && state.statusMessage.isNotEmpty()) {
                Text(
                    text = state.statusMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp)
                )
            }

            // Last run
            if (state.lastRun.isNotEmpty()) {
                AapsCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(ApsStrings.openapsma_last_run_label),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = state.lastRun,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Sections
            state.sections.forEachIndexed { index, section ->
                if (section.isGroupHeader) {
                    // Group header — centered label
                    Text(
                        text = stringResource(section.title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                } else {
                    val isExpanded = expandedState[index] ?: !section.collapsedByDefault

                    CollapsibleSection(
                        title = stringResource(section.title),
                        expanded = isExpanded,
                        onToggle = { expandedState[index] = !isExpanded },
                        rows = section.rows
                    )
                }
            }

        }
    }
}

@Composable
private fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    rows: List<KeyValueRow>
) {
    val expandedState = stringResource(CoreUiStrings.state_expanded)
    val collapsedState = stringResource(CoreUiStrings.state_collapsed)
    AapsCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    // A heading, so a screen reader can jump between sections rather than swiping
                    // through every key/value row to reach the next one. The open/closed state goes
                    // with it - the arrow alone said nothing, its description was null.
                    .semantics {
                        heading()
                        stateDescription = if (expanded) expandedState else collapsedState
                    }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Content
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    rows.forEachIndexed { index, row ->
                        KeyValueRowItem(row)
                        if (index < rows.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyValueRowItem(row: KeyValueRow) {
    if (row.key.isEmpty()) {
        // Full-width text (e.g. constraints, script debug lines)
        Text(
            text = row.value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = localizedFieldName(row.key),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = when (row.value) {
                    "true" -> stringResource(CoreUiStrings.yes)
                    "false" -> stringResource(CoreUiStrings.no)
                    else -> row.value
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1.2f)
            )
        }
    }
}

/** Localize presentation labels while preserving the algorithm field names and captured data. */
@Composable
private fun localizedFieldName(key: String): String {
    val label = when (key) {
        "A52_risk_enable" -> ApsStrings.aps_field_a52_risk_enable
        "SMBInterval" -> ApsStrings.aps_field_smbinterval
        "activity" -> ApsStrings.aps_field_activity
        "adv_target_adjustments" -> ApsStrings.aps_field_adv_target_adjustments
        "algorithm" -> ApsStrings.aps_field_algorithm
        "allowSMB_with_high_temptarget" -> ApsStrings.aps_field_allowsmb_with_high_temptarget
        "arraySize" -> ApsStrings.aps_field_arraysize
        "autoISF_max" -> ApsStrings.aps_field_autoisf_max
        "autoISF_min" -> ApsStrings.aps_field_autoisf_min
        "autoISF_version" -> ApsStrings.aps_field_autoisf_version
        "autosens_adjust_targets" -> ApsStrings.aps_field_autosens_adjust_targets
        "autosens_max" -> ApsStrings.aps_field_autosens_max
        "basaliob" -> ApsStrings.aps_field_basaliob
        "bgAccel_ISF_weight" -> ApsStrings.aps_field_bgaccel_isf_weight
        "bgBrake_ISF_weight" -> ApsStrings.aps_field_bgbrake_isf_weight
        "bolus_increment" -> ApsStrings.aps_field_bolus_increment
        "bolussnooze" -> ApsStrings.aps_field_bolussnooze
        "carb_ratio" -> ApsStrings.aps_field_carb_ratio
        "carbs" -> ApsStrings.aps_field_carbs
        "carbsAbsorbed" -> ApsStrings.aps_field_carbsabsorbed
        "carbsReq" -> ApsStrings.aps_field_carbsreq
        "carbsReqThreshold" -> ApsStrings.aps_field_carbsreqthreshold
        "carbsReqWithin" -> ApsStrings.aps_field_carbsreqwithin
        "current_basal" -> ApsStrings.aps_field_current_basal
        "current_basal_safety_multiplier" -> ApsStrings.aps_field_current_basal_safety_multiplier
        "deliverAt" -> ApsStrings.aps_field_deliverat
        "delta" -> ApsStrings.aps_field_delta
        "dura_ISF_weight" -> ApsStrings.aps_field_dura_isf_weight
        "duration" -> ApsStrings.aps_field_duration
        "enableSMB_EvenOn_OddOff_always" -> ApsStrings.aps_field_enablesmb_evenon_oddoff_always
        "enableSMB_after_carbs" -> ApsStrings.aps_field_enablesmb_after_carbs
        "enableSMB_always" -> ApsStrings.aps_field_enablesmb_always
        "enableSMB_with_COB" -> ApsStrings.aps_field_enablesmb_with_cob
        "enableSMB_with_temptarget" -> ApsStrings.aps_field_enablesmb_with_temptarget
        "enableUAM" -> ApsStrings.aps_field_enableuam
        "enable_autoISF" -> ApsStrings.aps_field_enable_autoisf
        "eventualBG" -> ApsStrings.aps_field_eventualbg
        "exercise_mode" -> ApsStrings.aps_field_exercise_mode
        "extendedBolusInsulin" -> ApsStrings.aps_field_extendedbolusinsulin
        "glucose" -> ApsStrings.aps_field_glucose
        "half_basal_exercise_target" -> ApsStrings.aps_field_half_basal_exercise_target
        "high_temptarget_raises_sensitivity" -> ApsStrings.aps_field_high_temptarget_raises_sensitivity
        "higher_ISFrange_weight" -> ApsStrings.aps_field_higher_isfrange_weight
        "hightempinsulin" -> ApsStrings.aps_field_hightempinsulin
        "insulinDivisor" -> ApsStrings.aps_field_insulindivisor
        "insulinReq" -> ApsStrings.aps_field_insulinreq
        "iob_threshold_percent" -> ApsStrings.aps_field_iob_threshold_percent
        "isfMgdlForCarbs" -> ApsStrings.aps_field_isfmgdlforcarbs
        "lastBolusTime" -> ApsStrings.aps_field_lastbolustime
        "lastCarbTime" -> ApsStrings.aps_field_lastcarbtime
        "lgsThreshold" -> ApsStrings.aps_field_lgsthreshold
        "longAvgDelta" -> ApsStrings.aps_field_longavgdelta
        "low_temptarget_lowers_sensitivity" -> ApsStrings.aps_field_low_temptarget_lowers_sensitivity
        "lower_ISFrange_weight" -> ApsStrings.aps_field_lower_isfrange_weight
        "maxCOB" -> ApsStrings.aps_field_maxcob
        "maxSMBBasalMinutes" -> ApsStrings.aps_field_maxsmbbasalminutes
        "maxUAMSMBBasalMinutes" -> ApsStrings.aps_field_maxuamsmbbasalminutes
        "max_basal" -> ApsStrings.aps_field_max_basal
        "max_bg" -> ApsStrings.aps_field_max_bg
        "max_daily_basal" -> ApsStrings.aps_field_max_daily_basal
        "max_daily_safety_multiplier" -> ApsStrings.aps_field_max_daily_safety_multiplier
        "max_iob" -> ApsStrings.aps_field_max_iob
        "mealCOB" -> ApsStrings.aps_field_mealcob
        "min_5m_carbimpact" -> ApsStrings.aps_field_min_5m_carbimpact
        "min_bg" -> ApsStrings.aps_field_min_bg
        "minutesrunning" -> ApsStrings.aps_field_minutesrunning
        "netInsulin" -> ApsStrings.aps_field_netinsulin
        "netbasalinsulin" -> ApsStrings.aps_field_netbasalinsulin
        "out_units" -> ApsStrings.aps_field_out_units
        "pastSensitivity" -> ApsStrings.aps_field_pastsensitivity
        "pp_ISF_weight" -> ApsStrings.aps_field_pp_isf_weight
        "profile_percentage" -> ApsStrings.aps_field_profile_percentage
        "rate" -> ApsStrings.aps_field_rate
        "ratio" -> ApsStrings.aps_field_ratio
        "ratioFromCarbs" -> ApsStrings.aps_field_ratiofromcarbs
        "ratioFromTdd" -> ApsStrings.aps_field_ratiofromtdd
        "ratioLimit" -> ApsStrings.aps_field_ratiolimit
        "reason" -> ApsStrings.aps_field_reason
        "remainingCarbsCap" -> ApsStrings.aps_field_remainingcarbscap
        "resistance_lowers_target" -> ApsStrings.aps_field_resistance_lowers_target
        "runningDynamicIsf" -> ApsStrings.aps_field_runningdynamicisf
        "sens" -> ApsStrings.aps_field_sens
        "sensResult" -> ApsStrings.aps_field_sensresult
        "sensitivityRatio" -> ApsStrings.aps_field_sensitivityratio
        "sensitivity_raises_target" -> ApsStrings.aps_field_sensitivity_raises_target
        "shortAvgDelta" -> ApsStrings.aps_field_shortavgdelta
        "skip_neutral_temps" -> ApsStrings.aps_field_skip_neutral_temps
        "slopeFromMaxDeviation" -> ApsStrings.aps_field_slopefrommaxdeviation
        "slopeFromMinDeviation" -> ApsStrings.aps_field_slopefrommindeviation
        "smb_delivery_ratio" -> ApsStrings.aps_field_smb_delivery_ratio
        "smb_delivery_ratio_bg_range" -> ApsStrings.aps_field_smb_delivery_ratio_bg_range
        "smb_delivery_ratio_max" -> ApsStrings.aps_field_smb_delivery_ratio_max
        "smb_delivery_ratio_min" -> ApsStrings.aps_field_smb_delivery_ratio_min
        "smb_max_range_extension" -> ApsStrings.aps_field_smb_max_range_extension
        "snoozeBG" -> ApsStrings.aps_field_snoozebg
        "targetBG" -> ApsStrings.aps_field_targetbg
        "target_bg" -> ApsStrings.aps_field_target_bg
        "temp" -> ApsStrings.aps_field_temp
        "temptargetSet" -> ApsStrings.aps_field_temptargetset
        "tick" -> ApsStrings.aps_field_tick
        "timestamp" -> ApsStrings.aps_field_timestamp
        "units" -> ApsStrings.aps_field_units
        "usedMinCarbsImpact" -> ApsStrings.aps_field_usedmincarbsimpact
        "variable_sens" -> ApsStrings.aps_field_variable_sens
        else -> null
    }
    return label?.let { stringResource(it) } ?: key
}
