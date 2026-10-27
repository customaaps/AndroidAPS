package app.aaps.plugins.boostport

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.EnforcedState
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.IntKey as MetroIntKey

/**
 * Metro registration for the Boost V6 *port*, not for an APS algorithm.
 *
 * The source code under legacy/ cannot be substituted for Nightscout's active
 * AndroidAPS dev engine. This GENERAL plugin is hidden and forced disabled.
 * It owns its namespaced preference keys so import/export can resolve them.
 * It does not implement APS, invoke a dosing engine, or send pump commands.
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@MetroIntKey(235)
@SingleIn(AppScope::class)
@Inject
class BoostPortInspectionPlugin(
    aapsLogger: AAPSLogger,
    override val rh: TextResolver,
    preferences: Preferences,
    notificationManager: NotificationManager
) : PluginBaseWithPreferences(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .enforce(EnforcedState.Disabled)
        .showInList { false }
        .pluginName(TextRef.Literal("Boost V6 (integration pending)"))
        .description(TextRef.Literal("Staged pure logic only; no APS dosing or pump control.")),
    ownPreferences = BoostPortKey.entries + BoostReplayStateKey.entries,
    aapsLogger = aapsLogger,
    rh = rh,
    preferences = preferences,
    notificationManager = notificationManager
) {
    /** A flag for future offline audit tooling; it must not activate a dosing path. */
    fun isAuditRequested(): Boolean = preferences.get(BoostPortKey.AuditRequested)
}
