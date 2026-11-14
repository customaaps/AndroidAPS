package app.aaps.plugins.automation.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.ui.CoreUiStrings
import app.aaps.plugins.automation.compose.actions.ActionCategory
import app.aaps.plugins.automation.compose.actions.ActionOption
import app.aaps.plugins.automation.compose.actions.ChooseActionSheet
import app.aaps.plugins.automation.triggers.Trigger
import app.aaps.plugins.automation.triggers.TriggerDeps
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.mockito.kotlin.mock

/**
 * The chooser sheets can exceed a small window, especially with translated labels. Their content
 * must scroll so the last grouped chip remains reachable, as in the graph series sheet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h300dp")
class AutomationChooserSheetsShortWindowTest {

    @get:Rule
    val compose = createComposeRule()

    private fun options(count: Int = 32): List<ActionOption> {
        val prototype = ActionPrototype()
        return List(count) { index ->
            ActionOption(
                className = "action-$index",
                label = "Action $index",
                icon = Icons.Filled.Add,
                prototype = prototype,
                category = ActionCategory.Other
            )
        }
    }

    @Test
    fun theLastActionCanBeReachedAndPickedInAShortWindow() {
        var show by mutableStateOf(true)
        var picked by mutableStateOf<String?>(null)

        compose.setContent {
            MaterialTheme {
                if (show) {
                    ChooseActionSheet(
                        options = options(),
                        onPick = { option ->
                            picked = option.className
                            show = false
                        },
                        onDismiss = { show = false }
                    )
                }
            }
        }

        compose.onAllNodesWithText("Action 31").onLast()
            .performScrollTo()
            .performClick()

        compose.runOnIdle { assertThat(picked).isEqualTo("action-31") }
    }

    @Test
    fun theLastTriggerCanBeReachedAndPickedInAShortWindow() {
        var show by mutableStateOf(true)
        var picked by mutableStateOf<String?>(null)
        val options = List(32) { index ->
            TriggerOption(
                className = "trigger-$index",
                labelResId = CoreUiStrings.alarm,
                icon = Icons.Filled.Add,
                prototype = TestTrigger(mock<TriggerDeps>()),
                category = TriggerCategory.Other
            )
        }

        compose.setContent {
            MaterialTheme {
                if (show) {
                    ChooseTriggerSheet(
                        options = options,
                        onPick = { option ->
                            picked = option.className
                            show = false
                        },
                        onDismiss = { show = false }
                    )
                }
            }
        }

        compose.onAllNodesWithText("Alarm").onLast()
            .performScrollTo()
            .performClick()

        compose.runOnIdle { assertThat(picked).isEqualTo("trigger-31") }
    }

    private open class ActionPrototype : app.aaps.plugins.automation.actions.Action(
        aapsLogger = mock(),
        rh = mock<TextResolver>(),
        pumpEnactResultProvider = { mock<PumpEnactResult>() }
    ) {
        override fun friendlyName() = CoreUiStrings.alarm
        override fun shortDescription() = "Action"
        override suspend fun doAction(): PumpEnactResult = mock()
        override fun isValid() = true
        override fun composeIcon() = Icons.Filled.Add
    }

    private open class TestTrigger(deps: TriggerDeps) : Trigger(deps) {
        override suspend fun shouldRun() = true
        override fun dataJSON() = JsonObject(emptyMap())
        override fun fromJSON(data: String) = this
        override fun friendlyName() = CoreUiStrings.alarm
        override fun friendlyDescription() = "Trigger"
        override fun duplicate() = this
        override fun composeIcon() = Icons.Filled.Add
    }
}
