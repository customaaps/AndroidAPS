package app.aaps.ui.compose.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.overview.graph.BgInfoData
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalAapsScale
import app.aaps.ui.compose.components.bgSpokenDescription
import app.aaps.ui.UiStrings
import app.aaps.core.ui.compose.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Displays current BG information in a circular design.
 * Shows BG value centered in a ring, with trend indicated by an arc position.
 *
 * @param bgInfo Current BG info data, or null if no data available
 * @param timeAgoText Formatted "time ago" string in the user's language, including the word "ago"
 *   (`DateUtil.minAgo`, e.g. "2 min ago")
 * @param modifier Optional modifier for the composable
 * @param size Size of the circular BG display. Defaults to [AapsSpacing.bgCircleSize] scaled by
 *   [LocalAapsScale] so the circle grows on tablets to match scaled typography. Pass an explicit
 *   value only if you want to override the tablet-aware default.
 * @param showTimeAgo Whether to render the small "time ago" line below the BG value.
 *
 * @see BgInfoSectionInRangePreview
 * @see BgInfoSectionHighPreview
 * @see BgInfoSectionLowPreview
 * @see BgInfoSectionNullPreview
 */
@Composable
fun BgInfoSection(
    bgInfo: BgInfoData?,
    timeAgoText: String,
    modifier: Modifier = Modifier,
    size: Dp = AapsSpacing.bgCircleSize * LocalAapsScale.current,
    showTimeAgo: Boolean = true,
    compactTimeAgoText: String = timeAgoText
) {
    if (bgInfo == null) {
        // Show placeholder when no data
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.size(size)
        ) {
            Text(
                text = "---",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val bgColor = bgInfo.bgRange.toColor()
    val ringColor = bgColor.copy(alpha = 0.3f)
    val ringStrokeWidth = AapsSpacing.bgRingStrokeWidth * LocalAapsScale.current

    // High/low is the colour and an old reading is struck through, so the description says both.
    val a11yDescription = bgSpokenDescription(
        bgText = bgInfo.bgText,
        range = bgInfo.bgRange,
        trend = bgInfo.trendDescription,
        deltaText = bgInfo.deltaText,
        timeAgo = timeAgoText,
        isOutdated = bgInfo.isOutdated
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .padding(AapsSpacing.small)
            .size(size)
            // Merging, not a bare semantics block: a described node is reached by linear screen-reader
            // navigation only when it is a leaf or a merging parent. This one has the value, the delta
            // and the age as children, so unmerged the whole sentence was skipped and those three were
            // read instead, as loose fragments mixed in among the chips - measured with TalkBack on a
            // phone. Merging makes the sentence one stop and takes the fragments out of the sweep.
            .semantics(mergeDescendants = true) { contentDescription = a11yDescription }
    ) {
        // Background ring + trend arc indicator
        Canvas(modifier = Modifier.size(size)) {
            val strokeWidth = ringStrokeWidth.toPx()
            val arcSize = Size(size.toPx() - strokeWidth, size.toPx() - strokeWidth)
            val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)

            // Background ring (full circle, semi-transparent)
            drawArc(
                color = ringColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Trend arc indicator (bright segment + outward triangles)
            bgInfo.trendArrow?.let { trend ->
                trend.toArcIndicator()?.let { indicator ->
                    // Single bright arc segment
                    val arcStart = indicator.centerAngle - indicator.sweepAngle / 2
                    drawArc(
                        color = bgColor,
                        startAngle = arcStart,
                        sweepAngle = indicator.sweepAngle,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )

                    // Triangles pointing outward, each placed on the circle at its own angle
                    val ringCenterX = topLeft.x + arcSize.width / 2
                    val ringCenterY = topLeft.y + arcSize.height / 2
                    val ringRadius = arcSize.width / 2
                    val triHeight = strokeWidth * 1.2f
                    val triHalfBase = strokeWidth * 1.6f
                    val n = indicator.triangleCount
                    val baseDist = ringRadius + strokeWidth * 0.2f
                    // Same expressions the JDK's Math.toDegrees / Math.toRadians use, written out
                    // so they work on every platform. Note the order: toRadians divides first.
                    val angularSpacing = ((triHalfBase * 1.6 / ringRadius) * 180.0 / PI).toFloat()

                    for (i in 0 until n) {
                        val triAngle = indicator.centerAngle + (i - (n - 1) / 2f) * angularSpacing
                        val triRad = triAngle.toDouble() / 180.0 * PI
                        val dirX = cos(triRad).toFloat()
                        val dirY = sin(triRad).toFloat()
                        val perpX = -dirY
                        val perpY = dirX
                        val baseX = ringCenterX + baseDist * dirX
                        val baseY = ringCenterY + baseDist * dirY
                        val tipX = baseX + triHeight * dirX
                        val tipY = baseY + triHeight * dirY
                        drawPath(
                            path = Path().apply {
                                moveTo(tipX, tipY)
                                lineTo(baseX + triHalfBase * perpX, baseY + triHalfBase * perpY)
                                lineTo(baseX - triHalfBase * perpX, baseY - triHalfBase * perpY)
                                close()
                            },
                            color = bgColor
                        )
                    }
                }
            }
        }

        // All three lines are constrained to the circle's interior. The age has
        // its own lower slot, with extra side insets to clear the curved ring.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().padding(
                horizontal = size * 0.15f,
                vertical = ringStrokeWidth + 8.dp * LocalAapsScale.current)
        ) {
            bgInfo.deltaText?.let { delta ->
                Box(Modifier.weight(0.2f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircleValueText(delta, AapsTheme.typography.bgSecondary,
                        MaterialTheme.colorScheme.onSurfaceVariant, 10.sp * LocalAapsScale.current)
                }
            }
            Box(Modifier.weight(0.6f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircleValueText(bgInfo.bgText, AapsTheme.typography.bgValue.copy(
                    textDecoration = if (bgInfo.isOutdated) TextDecoration.LineThrough else TextDecoration.None),
                    bgColor, 22.sp * LocalAapsScale.current)
            }
            if (showTimeAgo) {
                Box(Modifier.weight(0.2f).fillMaxWidth().padding(horizontal = size * 0.1f),
                    contentAlignment = Alignment.Center) {
                    CircleValueText(compactTimeAgoText,
                        AapsTheme.typography.bgTimeAgo.copy(fontSize = 14.sp * LocalAapsScale.current),
                        MaterialTheme.colorScheme.onSurfaceVariant, 10.sp * LocalAapsScale.current)
                }
            }
        }
    }
}

@Composable
private fun CircleValueText(text: String, style: TextStyle, color: Color, minFontSize: TextUnit) {
    BasicText(text = text, modifier = Modifier.fillMaxWidth(),
        style = style.copy(color = color, textAlign = TextAlign.Center, lineHeight = TextUnit.Unspecified),
        maxLines = 1, softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = minFontSize, maxFontSize = style.fontSize, stepSize = 1.sp))
}

/** Full localized age is retained separately for spoken description. */
@Composable
internal fun compactBgAgeText(ageSeconds: Long?): String = when {
    ageSeconds == null || ageSeconds < 0L -> "—"
    ageSeconds < 60L -> stringResource(UiStrings.overview_bg_age_seconds_short, ageSeconds)
    else -> stringResource(UiStrings.overview_bg_age_minutes_short, ageSeconds / 60L)
}

/**
 * Maps BgRange to the appropriate theme color.
 */
@Composable
private fun BgRange.toColor(): Color = when (this) {
    BgRange.HIGH     -> AapsTheme.generalColors.bgHigh
    BgRange.IN_RANGE -> AapsTheme.generalColors.bgInRange
    BgRange.LOW      -> AapsTheme.generalColors.bgLow
}

/**
 * Arc indicator describing position and triangle count. Internal: also used by the glucose circle widget.
 * @param centerAngle center of the arc in degrees (0° = right/3 o'clock, -90° = top, 90° = bottom)
 * @param sweepAngle arc length in degrees
 * @param triangleCount number of outward-pointing triangles (1-3)
 */
internal data class ArcIndicator(
    val centerAngle: Float,
    val sweepAngle: Float,
    val triangleCount: Int
)

/**
 * Maps TrendArrow to arc indicator with 5 fixed positions and 1-3 triangles.
 * Returns null for NONE (no arc drawn).
 *
 * Positions: Up (-90°), 45°-up (-45°), Flat (0°), 45°-down (45°), Down (90°)
 * Double/Triple use same position but show 2 or 3 triangles.
 */
internal fun TrendArrow.toArcIndicator(): ArcIndicator? {
    val sweepAngle = 40f
    return when (this) {
        TrendArrow.NONE            -> null
        TrendArrow.FLAT            -> ArcIndicator(centerAngle = 0f, sweepAngle = sweepAngle, triangleCount = 1)
        TrendArrow.FORTY_FIVE_UP   -> ArcIndicator(centerAngle = -45f, sweepAngle = sweepAngle, triangleCount = 1)
        TrendArrow.FORTY_FIVE_DOWN -> ArcIndicator(centerAngle = 45f, sweepAngle = sweepAngle, triangleCount = 1)
        TrendArrow.SINGLE_UP       -> ArcIndicator(centerAngle = -90f, sweepAngle = sweepAngle, triangleCount = 1)
        TrendArrow.SINGLE_DOWN     -> ArcIndicator(centerAngle = 90f, sweepAngle = sweepAngle, triangleCount = 1)
        TrendArrow.DOUBLE_UP       -> ArcIndicator(centerAngle = -90f, sweepAngle = sweepAngle, triangleCount = 2)
        TrendArrow.DOUBLE_DOWN     -> ArcIndicator(centerAngle = 90f, sweepAngle = sweepAngle, triangleCount = 2)
        TrendArrow.TRIPLE_UP       -> ArcIndicator(centerAngle = -90f, sweepAngle = sweepAngle, triangleCount = 3)
        TrendArrow.TRIPLE_DOWN     -> ArcIndicator(centerAngle = 90f, sweepAngle = sweepAngle, triangleCount = 3)
    }
}
