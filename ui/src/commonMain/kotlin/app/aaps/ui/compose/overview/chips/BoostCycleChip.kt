package app.aaps.ui.compose.overview.chips
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.icons.IcPluginOpenAPS
@Composable internal fun BoostCycleChip(status: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
 Surface(shape = RoundedCornerShape(AapsSpacing.chipCornerRadius), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = modifier.heightIn(min = AapsSpacing.chipHeight).clickable(onClick = onClick)) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small)) {
   Icon(IcPluginOpenAPS, "Boost", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(AapsSpacing.chipIconSize))
   Text("BOOST • $status", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(start = AapsSpacing.medium))
  }
 }
}