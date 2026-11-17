package app.aaps.plugins.source.libre2

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.keys.BooleanComposedKey
import app.aaps.core.keys.interfaces.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.core.ui.compose.icons.IcGenericCgm
import app.aaps.plugins.source.R
import dev.zacsweers.metro.*

/** Settings remain accessible independently of the selected glucose source. */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(561)
@SingleIn(AppScope::class)
@Inject
class LibreViewPlugin(
    private val context: Context,
    preferences: Preferences,
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    notificationManager: NotificationManager,
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.SYNC)
        .composeContent { LibreViewContent(context) }
        .icon(IcGenericCgm)
        .pluginName(TextRef.AndroidRes(R.string.libre2_libreview_export))
        .description(TextRef.AndroidRes(R.string.libre2_libreview_region)),
    aapsLogger, rh, notificationManager
) {
    init {
        // The old entry was enforced and had no ConfigBuilder selection. Preserve its upload consent once.
        val key = "SYNC_LibreViewPlugin"
        if (preferences.getIfExists(BooleanComposedKey.ConfigBuilderEnabled, key) == null) {
            val enabled = runCatching { Libre2Exchange(context).settings().libreView }.getOrElse {
                aapsLogger.error("Could not migrate LibreView upload selection")
                false
            }
            preferences.put(BooleanComposedKey.ConfigBuilderEnabled, key, value = enabled)
        }
    }

    override suspend fun onStart() {
        withContext(Dispatchers.IO) { Libre2Exchange(context).setLibreViewEnabled(true) }
    }

    override suspend fun onStop() {
        withContext(Dispatchers.IO) { Libre2Exchange(context).setLibreViewEnabled(false) }
    }
}

private class LibreViewContent(private val context: Context) : ComposablePluginContent {
    @Composable
    override fun Render(setToolbarConfig: (ToolbarConfig) -> Unit, onNavigateBack: () -> Unit, onSettings: (() -> Unit)?) {
        val title = stringResource(R.string.libre2_libreview_export)
        SideEffect { setToolbarConfig(ToolbarConfig(title, {}, {})) }
        Column(Modifier.padding(16.dp)) {
            Libre2ExchangeContent(context, libreViewOnly = true)
        }
    }
}
