package app.aaps.ui.compose.overview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.icons.IcCannulaChange
import app.aaps.core.ui.compose.icons.IcCgmInsert
import app.aaps.core.ui.compose.icons.IcPumpBattery
import app.aaps.core.ui.compose.icons.IcPumpCartridge
import app.aaps.ui.compose.overview.chips.SensitivityUiState
import app.aaps.ui.compose.overview.statusLights.StatusItem
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CompactOverviewStatusTest {
    @get:Rule val compose = createComposeRule()
    private val state = SensitivityUiState(profileIsf = "5.6", isfFrom = "5.6", isfTo = "4.6", crFrom = "10.0", crTo = "8.0")
    private val items = listOf(
        StatusItem("Cannula", "2d1h", icon = IcCannulaChange, ageStatus = StatusLevel.WARNING),
        StatusItem("Sensor", "6d9h", icon = IcCgmInsert),
        StatusItem("Insulin", "2d1h", icon = IcPumpCartridge, level = "50 U"),
        StatusItem("Battery", "0d0h", icon = IcPumpBattery, level = "50%")
    )

    private fun render(width: Int = 360, fontScale: Float = 1f, values: SensitivityUiState = state, onExpand: () -> Unit = {}) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        Box(Modifier.width(width.dp).padding(8.dp)) {
                            CompactOverviewStatus(items, values, canExpand = true, onExpand)
                        }
                    }
                }
            }
        }
    }

    @Test fun equipmentIsLeftOfBothValuesAndExpandStillWorks() {
        var expands = 0
        render(onExpand = { expands++ })
        val isf = compose.onNodeWithContentDescription("ISF: profile 5.6; used 4.6").assertIsDisplayed().fetchSemanticsNode()
        val cr = compose.onNodeWithContentDescription("CR: profile 10.0; used 8.0").assertIsDisplayed().fetchSemanticsNode()
        val reservoir = compose.onNodeWithText("2d1h 50 U").assertIsDisplayed().fetchSemanticsNode()
        assertThat(reservoir.boundsInRoot.right).isAtMost(isf.boundsInRoot.left)
        assertThat(isf.boundsInRoot.top).isLessThan(cr.boundsInRoot.top)
        compose.onNodeWithText("0d0h 50%").assertIsDisplayed()
        compose.onNodeWithContentDescription("Expand").performClick()
        assertThat(expands).isEqualTo(1)
        assertNoClippedText()
        System.getenv("AAPS_PREVIEW_DIR")?.let { output ->
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            File(output, "overview-status.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun narrowScreenWithLargeFontKeepsEveryValueReadable() {
        render(width = 280, fontScale = 1.6f)
        compose.onNodeWithContentDescription("ISF: profile 5.6; used 4.6").assertIsDisplayed()
        compose.onNodeWithContentDescription("CR: profile 10.0; used 8.0").assertIsDisplayed()
        compose.onNodeWithText("2d1h 50 U").assertIsDisplayed()
        compose.onNodeWithText("0d0h 50%").assertIsDisplayed()
        assertNoClippedText()
    }

    @Test fun fourEquipmentIndicatorsStayOnOneLineAndExpandStillWorks() {
        var expands = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.25f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        Box(Modifier.width(320.dp).padding(8.dp)) {
                            CompactEquipmentSingleRow(items, canExpand = true, onExpand = { expands++ })
                        }
                    }
                }
            }
        }
        val cannula = compose.onNodeWithText("2d1h").assertIsDisplayed().fetchSemanticsNode()
        val insulin = compose.onNodeWithText("2d1h 50 U").assertIsDisplayed().fetchSemanticsNode()
        val sensor = compose.onNodeWithText("6d9h").assertIsDisplayed().fetchSemanticsNode()
        val battery = compose.onNodeWithText("0d0h 50%").assertIsDisplayed().fetchSemanticsNode()
        val row = listOf(cannula, insulin, sensor, battery)
        val y = cannula.boundsInRoot.center.y
        row.forEach { assertThat(kotlin.math.abs(it.boundsInRoot.center.y - y)).isLessThan(6f) }
        compose.onNodeWithContentDescription("Expand").performClick()
        assertThat(expands).isEqualTo(1)
        assertNoClippedText()
    }

    @Test fun unchangedValuesShowOnceWithoutAnArrow() {
        render(values = SensitivityUiState(profileIsf = "5.6", crFrom = "10.0"))
        compose.onNodeWithText("ISF 5.6").assertIsDisplayed()
        compose.onNodeWithText("CR 10.0").assertIsDisplayed()
        assertNoClippedText()
    }

    private fun assertNoClippedText() {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes().forEach { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                layouts.forEach { assertThat(it.hasVisualOverflow).isFalse() }
            }
    }
}
