package app.aaps.plugins.periodcalendar.compose.chips

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.aaps.plugins.periodcalendar.R
import app.aaps.plugins.periodcalendar.model.CyclePhase

@Composable
fun PeriodCalendarChip(
    cycleDay: Int,
    currentPhase: CyclePhase?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val phaseColor = if (currentPhase != null) {
        Color(android.graphics.Color.parseColor(currentPhase.colorHex))
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val containerColor = phaseColor.copy(alpha = 0.2f)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        onClick = { onClick() },
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        modifier = modifier
            .fillMaxWidth()
            .height(35.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Canvas(modifier = Modifier.size(24.dp)) {
                    drawCircle(phaseColor)
                }
                Column(
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        text = if (cycleDay > 0) String.format("%02d", cycleDay) else "--",
                        color = textColor,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = currentPhase?.label ?: "--",
                        color = textColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

