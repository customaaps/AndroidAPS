package app.aaps.ui.compose.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.LongOrShort
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
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ProfileValue(
            label = stringResource(CoreUiStrings.isf_short),
            from = state.isfFrom.ifEmpty { state.profileIsf },
            to = state.isfTo
        )
        ProfileValue(label = stringResource(CoreUiStrings.cr_short), from = state.crFrom, to = state.crTo)
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
private fun ProfileValue(label: String, from: String, to: String) {
    if (from.isEmpty()) return
    val changed = to.isNotEmpty() && to != from
    val values = if (changed) "$from → $to" else from
    val description = if (changed) stringResource(CoreUiStrings.overview_profile_to_used, label, from, to) else "$label $from"
    // Keep the font size. When the label plus numbers do not fit, put the label above the
    // numbers instead of clipping the used value or shrinking the user's accessibility font.
    LongOrShort(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description },
        long = {
            Text("$label $values", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        },
        short = {
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(values, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    )
}
