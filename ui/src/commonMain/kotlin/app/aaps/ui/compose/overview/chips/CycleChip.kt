package app.aaps.ui.compose.overview.chips

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.LocalAapsScale

/** Compact cycle-day chip shown on the overview while the Period Calendar plugin is enabled. */
@Composable
fun CycleChip(
    dayText: String,
    phaseLabel: String?,
    colorHex: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val phaseColor = remember(colorHex) { parseHexColor(colorHex) }
    val containerColor = phaseColor?.copy(alpha = 0.2f) ?: MaterialTheme.colorScheme.surfaceVariant
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(AapsSpacing.chipCornerRadius),
        color = containerColor,
        modifier = modifier
            .fillMaxWidth()
            .height(AapsSpacing.chipHeight * LocalAapsScale.current)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = AapsSpacing.medium)
        ) {
            Box(
                Modifier
                    .size(AapsSpacing.chipIconSize)
                    .clip(CircleShape)
                    .background(phaseColor ?: MaterialTheme.colorScheme.onSurfaceVariant)
            )
            Spacer(Modifier.width(AapsSpacing.medium))
            androidx.compose.material3.Text(
                text = dayText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            if (!phaseLabel.isNullOrBlank()) {
                Spacer(Modifier.width(AapsSpacing.small))
                androidx.compose.material3.Text(
                    text = phaseLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun parseHexColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    val raw = hex.removePrefix("#")
    val value = raw.toLongOrNull(16) ?: return null
    return when (raw.length) {
        6    -> Color(value or 0xFF000000L)
        8    -> Color(value)
        else -> null
    }
}
