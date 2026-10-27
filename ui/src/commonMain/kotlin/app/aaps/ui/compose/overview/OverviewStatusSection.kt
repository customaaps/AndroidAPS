package app.aaps.ui.compose.overview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsCard
import app.aaps.core.ui.compose.LocalClientRole
import app.aaps.core.ui.compose.LongOrShort
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.preference.PreferenceSheetContent
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.ui.compose.statusLevelToColor
import app.aaps.core.ui.compose.statusLevelToDescription
import app.aaps.core.ui.compose.stringResource
import app.aaps.core.ui.compose.stringResourceOrNull
import app.aaps.ui.compose.overview.statusLights.StatusItem
import app.aaps.ui.compose.overview.statusLights.StatusSectionContent
import app.aaps.ui.compose.overview.chips.SensitivityUiState

/**
 * @see OverviewStatusSectionCollapsedPreview
 * @see OverviewStatusSectionExpandedPreview
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewStatusSection(
    sensorStatus: StatusItem?,
    insulinStatus: StatusItem?,
    cannulaStatus: StatusItem?,
    batteryStatus: StatusItem?,
    showFill: Boolean,
    showPumpBatteryChange: Boolean,
    // The status-row action buttons (fill / sensor insert / battery change) open mutating screens — hidden on an
    // unpaired client (null callback drops just the button; the status row's age/level stays visible). Same gate as nav/Manage/chips.
    commandsAllowed: Boolean = true,
    onNavigate: (NavigationRequest) -> Unit,
    statusLightsDef: PreferenceSubScreenDef,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    sensitivity: SensitivityUiState = SensitivityUiState(),
    modifier: Modifier = Modifier
) {
    val items = listOfNotNull(cannulaStatus, insulinStatus, sensorStatus, batteryStatus)
    if (items.isEmpty() && !sensitivity.hasProfileValues) return
    val compactItems = items.filter { it.compactAge || (it.compactLevel && it.level != null) }

    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    AapsCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!expanded || items.isEmpty()) {
                CompactOverviewStatus(
                    items = compactItems,
                    sensitivity = sensitivity,
                    canExpand = items.isNotEmpty(),
                    onExpand = { onExpandedChange(true) }
                )
            } else Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(CoreUiStrings.status),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onExpandedChange(false) }
                )
                IconButton(
                    onClick = { showSettingsSheet = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(CoreUiStrings.settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Icon(
                    imageVector = Icons.Filled.ExpandLess,
                    contentDescription = stringResource(CoreUiStrings.collapse),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onExpandedChange(false) }
                )
            }

            AnimatedVisibility(
                visible = expanded && items.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (sensitivity.hasProfileValues) {
                        DynamicValuesBlock(state = sensitivity, modifier = Modifier.fillMaxWidth())
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    // Each button also needs this phone's role to allow what it opens (the master would refuse it).
                    val role = LocalClientRole.current
                    StatusSectionContent(
                        sensorStatus = sensorStatus,
                        insulinStatus = insulinStatus,
                        cannulaStatus = cannulaStatus,
                        batteryStatus = batteryStatus,
                        onSensorInsertClick = if (commandsAllowed && role.allows(ElementType.SENSOR_INSERT.role)) {
                            { onNavigate(NavigationRequest.Element(ElementType.SENSOR_INSERT)) }
                        } else null,
                        onFillClick = if (showFill && commandsAllowed && role.allows(ElementType.CANNULA_CHANGE.role)) {
                            { onNavigate(NavigationRequest.Element(ElementType.CANNULA_CHANGE)) }
                        } else null,
                        onInsulinChangeClick = if (showFill && commandsAllowed && role.allows(ElementType.FILL.role)) {
                            { onNavigate(NavigationRequest.Element(ElementType.FILL)) }
                        } else null,
                        onBatteryChangeClick = if (showPumpBatteryChange && commandsAllowed && role.allows(ElementType.BATTERY_CHANGE.role)) {
                            { onNavigate(NavigationRequest.Element(ElementType.BATTERY_CHANGE)) }
                        } else null
                    )
                }
            }
        }
    }

    if (showSettingsSheet) {
        StatusLightsSettingsBottomSheet(
            settingsDef = statusLightsDef,
            onDismiss = { showSettingsSheet = false },
            sheetState = sheetState
        )
    }
}

/** Equipment stays on the left; both APS values stay together on the right. */
@Composable
internal fun CompactOverviewStatus(
    items: List<StatusItem>,
    sensitivity: SensitivityUiState,
    canExpand: Boolean,
    onExpand: () -> Unit
) {
    if (!sensitivity.hasProfileValues) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LongOrShort(
                long = { CompactStatusItems(items, shortAge = false) },
                short = { CompactStatusItems(items, shortAge = true) },
                modifier = Modifier.weight(1f).clickable(enabled = canExpand, onClick = onExpand)
            )
            if (canExpand) StatusExpandButton(onExpand)
        }
        return
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Large accessibility fonts and very narrow split-screen panes need a vertical fallback.
        val stack = maxWidth / LocalDensity.current.fontScale < 280.dp
        if (stack || items.isEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (items.isNotEmpty()) {
                    CompactEquipment(items, Modifier.fillMaxWidth().clickable(onClick = onExpand))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DynamicValuesBlock(sensitivity, Modifier.weight(1f))
                    if (canExpand) StatusExpandButton(onExpand)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactEquipment(items, Modifier.weight(0.54f).clickable(onClick = onExpand))
                VerticalDivider(Modifier.height(64.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Column(modifier = Modifier.weight(0.46f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DynamicValuesBlock(sensitivity, Modifier.fillMaxWidth())
                    if (canExpand) {
                        androidx.compose.foundation.layout.Box(Modifier.align(Alignment.End)) { StatusExpandButton(onExpand) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusExpandButton(onExpand: () -> Unit) {
    IconButton(onClick = onExpand, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Filled.ExpandMore, contentDescription = stringResource(CoreUiStrings.expand))
    }
}

@Composable
private fun CompactEquipment(items: List<StatusItem>, modifier: Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { CompactStatusItem(item = it, age = it.ageShort) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusLightsSettingsBottomSheet(
    settingsDef: PreferenceSubScreenDef,
    onDismiss: () -> Unit,
    sheetState: SheetState
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        StatusLightsSettingsContent(settingsDef = settingsDef)
    }
}

@Composable
internal fun StatusLightsSettingsContent(settingsDef: PreferenceSubScreenDef) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        Text(
            text = stringResource(CoreUiStrings.statuslights),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
        )

        // Multiple group subscreens → one expandable card each (collapsed by default), matching the
        // main Settings screen's look. Shared renderer; groups are the SSOT in BuiltInSearchables.
        PreferenceSheetContent(settingsDef = settingsDef)
    }
}

@Composable
private fun CompactStatusItems(items: List<StatusItem>, shortAge: Boolean) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items.forEach { item ->
            CompactStatusItem(item = item, age = if (shortAge) item.ageShort else item.age)
        }
    }
}

@Composable
private fun CompactStatusItem(item: StatusItem, age: String) {
    val showAge = item.compactAge
    val showLevel = item.compactLevel && item.level != null
    if (!showAge && !showLevel) return

    val ageColor = statusLevelToColor(item.ageStatus)
    val levelColor = if (item.level != null) statusLevelToColor(item.levelStatus) else ageColor

    // Severity is carried only by the colour of the spans inside the text below, so an overdue
    // cannula or a nearly empty reservoir looked urgent and read as ordinary. This chip shows two
    // values at once, so it speaks the worse of the two - saying both would be a mouthful on a chip
    // whose whole job is to be glanceable.
    val worstStatus = when {
        item.ageStatus == StatusLevel.CRITICAL ||
            (showLevel && item.levelStatus == StatusLevel.CRITICAL) -> StatusLevel.CRITICAL

        item.ageStatus == StatusLevel.WARNING ||
            (showLevel && item.levelStatus == StatusLevel.WARNING)  -> StatusLevel.WARNING

        else                                                        -> StatusLevel.NORMAL
    }
    val severity = stringResourceOrNull(statusLevelToDescription(worstStatus))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        // Merged so the icon's name, the values and the severity are one item. The description adds
        // only the severity, because on a merging node it sits before the children rather than
        // replacing them: "Critical, Cannula, 3d 50%".
        modifier = if (severity != null) {
            Modifier.semantics(mergeDescendants = true) { contentDescription = severity }
        } else {
            Modifier.semantics(mergeDescendants = true) { }
        }
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = buildAnnotatedString {
                if (showAge) {
                    withStyle(SpanStyle(color = ageColor)) {
                        append(age)
                    }
                }
                if (showLevel) {
                    withStyle(SpanStyle(color = levelColor)) {
                        if (showAge) append(" ")
                        append(item.level)
                    }
                }
            },
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
