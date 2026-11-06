package app.aaps.plugins.automation.triggers

import androidx.compose.runtime.mutableStateOf
import app.aaps.core.interfaces.periodcalendar.PeriodCalendar
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.utils.lenientString
import app.aaps.plugins.automation.AutomationStrings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class TriggerCyclePhase(deps: TriggerDeps) : Trigger(deps) {

    var phaseLabel = mutableStateOf("ME")

    val phaseLabels: List<String>
        get() {
            val periodCalendarPlugin = periodCalendarPlugin()
            return periodCalendarPlugin?.getPhaseLabels()?.takeIf { it.isNotEmpty() } ?: listOf("ME", "FO", "FE", "OV", "L1", "L2")
        }

    // Edge-detection: fire once per phase entry, not on every processActions() tick.
    // Null = armed (not yet fired for current phase); non-null = already fired for that phase label.
    // In-memory only — after restart at most one duplicate fire is acceptable.
    private var firedForPhase: String? = null

    override suspend fun shouldRun(): Boolean {
        val periodCalendarPlugin = periodCalendarPlugin() ?: return false
        val currentPhase = periodCalendarPlugin.getCurrentPhaseLabel()

        // Left the target phase: re-arm so the next entry fires again.
        if (currentPhase != phaseLabel.value) {
            if (firedForPhase == phaseLabel.value) firedForPhase = null
            return false
        }

        // In the target phase: fire only on the first observation (edge).
        if (firedForPhase == phaseLabel.value) return false
        firedForPhase = phaseLabel.value
        return true
    }

    override fun dataJSON(): JsonObject = buildJsonObject { put("phaseLabel", phaseLabel.value) }

    override fun fromJSON(data: String): Trigger {
        phaseLabel.value = jsonOf(data).lenientString("phaseLabel", "ME")
        return this
    }

    override fun friendlyName(): TextRef = AutomationStrings.cycle_phase_trigger
    override fun friendlyDescription(): String = rh.gs(AutomationStrings.cycle_phase_trigger_desc, phaseLabel.value)

    override fun duplicate(): Trigger {
        val copy = TriggerCyclePhase(deps)
        copy.phaseLabel.value = phaseLabel.value
        return copy
    }

    private fun periodCalendarPlugin(): PeriodCalendar? {
        val plugin = activePlugin.getSpecificPluginsListByInterface(PeriodCalendar::class).firstOrNull() ?: return null
        if (plugin !is PluginBase || !plugin.isEnabled()) return null
        return plugin as? PeriodCalendar
    }
}
