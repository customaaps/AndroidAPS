package app.aaps.plugins.source.libre2

import android.content.Context
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.icons.IcGenericCgm
import app.aaps.plugins.source.AbstractBgSourcePlugin
import app.aaps.plugins.source.R
import dev.zacsweers.metro.*

@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(560)
@SingleIn(AppScope::class)
@Inject
class Libre2Plugin(
    context: Context,
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    preferences: Preferences,
    config: Config,
    notificationManager: NotificationManager,
    private val client: Libre2Client,
) : AbstractBgSourcePlugin(
    PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .composeContent { Libre2Content(client, context) }
        .icon(IcGenericCgm)
        .pluginName(TextRef.AndroidRes(R.string.source_libre2))
        .description(TextRef.AndroidRes(R.string.description_source_libre2)),
    aapsLogger = aapsLogger, rh = rh, preferences = preferences, config = config,
    notificationManager = notificationManager
) {
    override suspend fun onStart() { super.onStart(); client.start() }
    override suspend fun onStop() { client.stop(); super.onStop() }
}
