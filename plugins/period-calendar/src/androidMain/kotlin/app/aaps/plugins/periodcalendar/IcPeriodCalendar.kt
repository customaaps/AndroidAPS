package app.aaps.plugins.periodcalendar

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

val IcPeriodCalendar: ImageVector by lazy {
    val paths = listOf(
        "M2.522,13.141a3.09,3.09 91.367,1 0,6.16 0.5a3.09,3.09 91.367,1 0,-6.16 -0.5z",
        "M7.192,19.208a2.573,2.573 75.495,1 0,5.129 0.416a2.573,2.573 75.495,1 0,-5.129 -0.416z",
        "M13.909,18.832a2.102,2.102 86.111,1 0,4.19 0.34a2.102,2.102 86.111,1 0,-4.19 -0.34z",
        "M18.024,14.866a1.655,1.655 71.555,1 0,3.3 0.268a1.655,1.655 71.555,1 0,-3.3 -0.268z",
        "M18.262,10.101a1.274,1.274 114.774,1 0,2.54 0.206a1.274,1.274 114.774,1 0,-2.54 -0.206z",
        "M15.78,6.799a1.13,1.13 112.162,1 0,2.252 0.183a1.13,1.13 112.162,1 0,-2.252 -0.183z",
        "M6.016,5.613a4.033,4.033 46.659,1 0,8.04 0.652a4.033,4.033 46.659,1 0,-8.04 -0.652z"
    )
    ImageVector.Builder(
        name = "IcPeriodCalendar",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        paths.forEach { pathData ->
            addPath(
                pathData = PathParser().parsePathString(pathData).toNodes(),
                fill = SolidColor(Color.Black)
            )
        }
    }.build()
}
