package app.aaps.plugins.periodcalendar

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.periodcalendar.PeriodCalendar
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.periodcalendar.compose.PeriodCalendarComposeContent
import app.aaps.plugins.periodcalendar.services.CycleCounterService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.IntKey as MetroIntKey

// Fork-only plugin (no upstream *PluginsListModule to live in); registered directly here, same
// pattern as SafetyPlugin. Global @IntKey ordering block for fork-only plugins: 252-253.
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@MetroIntKey(253)
@ContributesBinding(AppScope::class, binding = binding<PeriodCalendar>())
@SingleIn(AppScope::class)
class PeriodCalendarPlugin
    @Inject
    constructor(
        aapsLogger: AAPSLogger,
        rh: TextResolver,
        private val cycleCounterService: CycleCounterService,
        notificationManager: NotificationManager,
    ) : PluginBase(
            PluginDescription()
                .mainType(PluginType.GENERAL)
                .composeContent { PeriodCalendarComposeContent() }
                .icon(IcPeriodCalendar)
                .pluginName(TextRef.AndroidRes(R.string.period_calendar))
                .description(TextRef.AndroidRes(R.string.description_period_calendar)),
            aapsLogger,
            rh,
            notificationManager,
        ),
        PeriodCalendar {

    override fun getCycleDayText(): String = cycleCounterService.getFormattedDisplay()

    override fun getCurrentCycleDay(): Int = cycleCounterService.getCurrentCycleDay()

    override fun getCurrentPhaseLabel(): String? = cycleCounterService.getCurrentPhase()?.label

    override fun getPhaseLabels(): List<String> = cycleCounterService.phases.map { it.label }

    override fun getCurrentPhaseColorHex(): String? = cycleCounterService.getCurrentPhase()?.colorHex
}
