package app.aaps.ui.compose.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.dialogs.OkCancelDialog
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.compose.overview.chips.SensitivityUiState

internal val SensitivityUiState.hasProfileValues: Boolean
    get() = profileIsf.isNotEmpty() || isfFrom.isNotEmpty() || crFrom.isNotEmpty()

/** Values from one APS calculation, never a second calculation performed by the UI. */
@Composable
internal fun DynamicValuesBlock(state: SensitivityUiState, modifier: Modifier = Modifier) {
    var showInfo by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.clickable(enabled = state.dialogText.isNotEmpty()) { showInfo = true },
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ProfileValue(
            label = "ISF",
            spokenLabel = stringResource(CoreUiStrings.isf_short),
            from = state.isfFrom.ifEmpty { state.profileIsf },
            to = state.isfTo
        )
        ProfileValue(label = "CR", spokenLabel = stringResource(CoreUiStrings.cr_short), from = state.crFrom, to = state.crTo)
    }
    if (showInfo) {
        OkCancelDialog(
            title = stringResource(CoreUiStrings.sensitivity),
            message = state.dialogText,
            onConfirm = { showInfo = false },
            onDismiss = { showInfo = false }
        )
    }
}

@Composable
private fun ProfileValue(label: String, spokenLabel: String, from: String, to: String) {
    if (from.isEmpty()) return
    val changed = to.isNotEmpty() && to != from
    val values = if (changed) "$from → $to" else from
    val description = if (changed) stringResource(CoreUiStrings.overview_profile_to_used, spokenLabel, from, to) else "$spokenLabel $from"
    // Abbreviations stay on the same line as their values. The full translated
    // label remains available to accessibility services and in the existing dialog.
    BasicText(
        text = "$label $values",
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description },
        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp,
            maxFontSize = MaterialTheme.typography.bodyMedium.fontSize, stepSize = 1.sp)
    )
}
