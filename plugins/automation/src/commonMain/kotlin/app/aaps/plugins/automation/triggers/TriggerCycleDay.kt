package app.aaps.plugins.automation.triggers

import androidx.compose.runtime.mutableStateOf
import app.aaps.core.interfaces.periodcalendar.PeriodCalendar
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.utils.lenientInt
import app.aaps.plugins.automation.AutomationStrings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class TriggerCycleDay(deps: TriggerDeps) : Trigger(deps) {

    var day = mutableStateOf(1)

    // Tracks edge: fire once when the target cycle day is first observed.
    // Null = not yet fired; after firing holds the day number we fired for.
    // In-memory only — at most one duplicate fire after app restart is acceptable.
    private var firedForDay: Int? = null

    override suspend fun shouldRun(): Boolean {
        val periodCalendarPlugin = periodCalendarPlugin() ?: return false
        val currentDay = periodCalendarPlugin.getCurrentCycleDay()

        if (currentDay <= 0 || currentDay != day.value) {
            // Left the target day — re-arm so next entry fires again.
            if (firedForDay == day.value) firedForDay = null
            return false
        }

        // In the target day: fire only on the first observation (edge).
        if (firedForDay == day.value) return false
        firedForDay = day.value
        return true
    }

    override fun dataJSON(): JsonObject = buildJsonObject { put("day", day.value) }

    override fun fromJSON(data: String): Trigger {
        day.value = jsonOf(data).lenientInt("day", 1)
        return this
    }

    override fun friendlyName(): TextRef = AutomationStrings.cycle_day_trigger
    override fun friendlyDescription(): String = rh.gs(AutomationStrings.cycle_day_trigger_desc, day.value)

    override fun duplicate(): Trigger {
        val copy = TriggerCycleDay(deps)
        copy.day.value = day.value
        return copy
    }

    private fun periodCalendarPlugin(): PeriodCalendar? {
        val plugin = activePlugin.getSpecificPluginsListByInterface(PeriodCalendar::class).firstOrNull() ?: return null
        if (plugin !is PluginBase || !plugin.isEnabled()) return null
        return plugin as? PeriodCalendar
    }
}
