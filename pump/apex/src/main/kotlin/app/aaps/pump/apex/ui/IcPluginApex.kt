package app.aaps.pump.apex.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val IcPluginApex: ImageVector by lazy {
    ImageVector.Builder("Apex", 48.dp, 48.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6f, 2f); lineTo(18f, 2f); lineTo(20f, 4f); lineTo(20f, 20f)
            lineTo(18f, 22f); lineTo(6f, 22f); lineTo(4f, 20f); lineTo(4f, 4f); close()
        }
        path(fill = SolidColor(Color.White)) {
            moveTo(7f, 5f); lineTo(17f, 5f); lineTo(17f, 13f); lineTo(7f, 13f); close()
            moveTo(11f, 15f); lineTo(13f, 15f); lineTo(13f, 17f); lineTo(15f, 17f)
            lineTo(15f, 19f); lineTo(13f, 19f); lineTo(13f, 21f); lineTo(11f, 21f)
            lineTo(11f, 19f); lineTo(9f, 19f); lineTo(9f, 17f); lineTo(11f, 17f); close()
        }
    }.build()
}
