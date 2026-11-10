package app.aaps.ui.compose.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TT
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.overview.graph.TbrState
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.LocalClientRole
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.compose.main.TempTargetChipState
import app.aaps.ui.compose.overview.chips.CobUiState
import app.aaps.ui.compose.overview.chips.CycleChip
import app.aaps.ui.compose.overview.chips.CycleChipUiState
import app.aaps.ui.compose.overview.chips.IobCobChipsRow
import app.aaps.ui.compose.overview.chips.IobUiState
import app.aaps.ui.compose.overview.chips.ProfileChip
import app.aaps.ui.compose.overview.chips.RunningModeChip
import app.aaps.ui.compose.overview.chips.SensitivityUiState
import app.aaps.ui.compose.overview.chips.TbrChip
import app.aaps.ui.compose.overview.chips.TempTargetChip

@Composable
fun OverviewChipsColumn(
    runningMode: RM.Mode?,
    runningModeText: String,
    runningModeRemaining: String,
    runningModeProgress: Float,
    runningModeSceneManaged: Boolean = false,
    smbEnabled: Boolean = false,
    profileName: String,
    isProfileModified: Boolean,
    profileProgress: Float,
    profileSceneManaged: Boolean = false,
    tempTargetText: String,
    tempTargetState: TempTargetChipState,
    tempTargetProgress: Float,
    tempTargetReason: TT.Reason?,
    tempTargetSceneManaged: Boolean = false,
    tbrState: TbrState,
    tbrText: String = "",
    iobUiState: IobUiState,
    cobUiState: CobUiState,
    sensitivityUiState: SensitivityUiState,
    cycleUiState: CycleChipUiState = CycleChipUiState(),
    onNavigate: (NavigationRequest) -> Unit,
    onTbrChipClick: () -> Unit,
    onIobChipClick: () -> Unit,
    commandsAllowed: Boolean = true,
    showPrimaryChips: Boolean = true,
    showDynamicValues: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (showPrimaryChips) {
            OverviewPrimaryChips(
                runningMode = runningMode,
                runningModeText = runningModeText,
                runningModeRemaining = runningModeRemaining,
                runningModeProgress = runningModeProgress,
                runningModeSceneManaged = runningModeSceneManaged,
                smbEnabled = smbEnabled,
                profileName = profileName,
                isProfileModified = isProfileModified,
                profileProgress = profileProgress,
                profileSceneManaged = profileSceneManaged,
                onNavigate = onNavigate,
                commandsAllowed = commandsAllowed
            )
        }
        if (showDynamicValues && sensitivityUiState.hasProfileValues) {
            // Move ISF/CR out of the lower status card into the free area beside
            // the glucose ring. The original detail dialog remains clickable.
            Surface(
                shape = RoundedCornerShape(AapsSpacing.chipCornerRadius),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.fillMaxWidth()
            ) {
                DynamicValuesBlock(
                    state = sensitivityUiState,
                    modifier = Modifier.padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small)
                )
            }
        }
        OverviewSecondaryChips(
            tempTargetText = tempTargetText,
            tempTargetState = tempTargetState,
            tempTargetProgress = tempTargetProgress,
            tempTargetReason = tempTargetReason,
            tempTargetSceneManaged = tempTargetSceneManaged,
            tbrState = tbrState,
            tbrText = tbrText,
            onNavigate = onNavigate,
            onTbrChipClick = onTbrChipClick,
            commandsAllowed = commandsAllowed
        )
        IobCobChipsRow(
            iobUiState = iobUiState,
            cobUiState = cobUiState,
            onIobChipClick = onIobChipClick
        )
        if (cycleUiState.visible) {
            CycleChip(
                dayText = cycleUiState.dayText,
                phaseLabel = cycleUiState.phaseLabel,
                colorHex = cycleUiState.colorHex,
                onClick = {
                    if (cycleUiState.pluginClassName.isNotBlank()) {
                        onNavigate(NavigationRequest.Plugin(cycleUiState.pluginClassName))
                    }
                }
            )
        }
        SensitivityChipBlock(
            state = sensitivityUiState.copy(isfFrom = "", isfTo = ""),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun OverviewPrimaryChips(
    runningMode: RM.Mode?,
    runningModeText: String,
    runningModeRemaining: String,
    runningModeProgress: Float,
    runningModeSceneManaged: Boolean,
    smbEnabled: Boolean,
    profileName: String,
    isProfileModified: Boolean,
    profileProgress: Float,
    profileSceneManaged: Boolean,
    onNavigate: (NavigationRequest) -> Unit,
    commandsAllowed: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (runningModeText.isNotEmpty()) {
            RunningModeChip(
                mode = runningMode,
                text = runningModeText,
                progress = runningModeProgress,
                remaining = runningModeRemaining,
                sceneManaged = runningModeSceneManaged,
                smbEnabled = smbEnabled,
                enabled = commandsAllowed && LocalClientRole.current.allows(ElementType.RUNNING_MODE.role),
                onClick = { onNavigate(NavigationRequest.Element(ElementType.RUNNING_MODE)) }
            )
        }
        ProfileChip(
            profileName = profileName.ifEmpty { stringResource(CoreUiStrings.no_profile_set) },
            isModified = isProfileModified,
            progress = profileProgress,
            onClick = { onNavigate(NavigationRequest.Element(ElementType.PROFILE_MANAGEMENT)) },
            sceneManaged = profileSceneManaged,
            isNoProfile = profileName.isEmpty(),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun OverviewSecondaryChips(
    tempTargetText: String,
    tempTargetState: TempTargetChipState,
    tempTargetProgress: Float,
    tempTargetReason: TT.Reason?,
    tempTargetSceneManaged: Boolean,
    tbrState: TbrState,
    tbrText: String,
    onNavigate: (NavigationRequest) -> Unit,
    onTbrChipClick: () -> Unit,
    commandsAllowed: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small)
    ) {
        if (tempTargetText.isNotEmpty()) {
            TempTargetChip(
                targetText = tempTargetText,
                state = tempTargetState,
                progress = tempTargetProgress,
                reason = tempTargetReason,
                modifier = Modifier.weight(0.6f),
                onClick = { onNavigate(NavigationRequest.Element(ElementType.TEMP_TARGET_MANAGEMENT)) },
                sceneManaged = tempTargetSceneManaged,
                enabled = commandsAllowed && LocalClientRole.current.allows(ElementType.TEMP_TARGET_MANAGEMENT.role)
            )
        }
        TbrChip(
            state = tbrState,
            valueText = tbrText,
            modifier = if (tempTargetText.isNotEmpty()) Modifier.weight(0.4f) else Modifier,
            onClick = onTbrChipClick
        )
    }
}
