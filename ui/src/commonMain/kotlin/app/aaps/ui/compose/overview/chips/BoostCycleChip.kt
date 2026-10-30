package app.aaps.ui.compose.overview.chips

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.LocalAapsScale
import app.aaps.core.ui.compose.icons.IcPluginOpenAPS

@Composable
internal fun BoostCycleChip(
    status: String,
    multiplier: String,
    cycle: Int?,
    dose: String,
    budget: String,
    score: String,
    scoreProgress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val accent = MaterialTheme.colorScheme.tertiary
    val text = MaterialTheme.colorScheme.onTertiaryContainer
    val quiet = text.copy(alpha = 0.78f)
    Surface(
        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
        shape = RoundedCornerShape(AapsSpacing.chipCornerRadius),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier.height(64.dp * LocalAapsScale.current)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = AapsSpacing.medium, vertical = 5.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().weight(1f)) {
                Icon(IcPluginOpenAPS, contentDescription = "Boost", tint = accent, modifier = Modifier.size(17.dp))
                BasicText(
                    text = status,
                    modifier = Modifier.weight(0.42f).padding(start = AapsSpacing.small),
                    style = MaterialTheme.typography.bodyMedium.copy(color = accent),
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 17.sp, stepSize = 1.sp)
                )
                BasicText(
                    text = "dose ${dose.ifBlank { "—" }}U · budget ${budget.ifBlank { "—" }}U",
                    modifier = Modifier.weight(0.58f),
                    style = MaterialTheme.typography.labelSmall.copy(color = quiet),
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 12.sp, stepSize = 1.sp)
                )
            }
            BasicText(
                text = "×${multiplier.ifBlank { "—" }} · ${cycle?.let { "${it}c" } ?: "—"}",
                style = MaterialTheme.typography.bodyMedium.copy(color = accent),
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 15.sp, stepSize = 1.sp)
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().weight(1f)) {
                BasicText("SCORE", style = MaterialTheme.typography.labelSmall.copy(color = quiet), maxLines = 1, modifier = Modifier.width(42.dp))
                Box(modifier = Modifier.weight(1f).height(7.dp).clip(RoundedCornerShape(4.dp)).background(quiet.copy(alpha = 0.28f))) {
                    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(scoreProgress.coerceIn(0f, 1f)).background(accent))
                }
                BasicText(text = score.ifBlank { "—" }, modifier = Modifier.width(36.dp).padding(start = AapsSpacing.small), style = MaterialTheme.typography.labelSmall.copy(color = text), maxLines = 1)
            }
        }
    }
}
