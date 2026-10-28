package app.aaps.di.metro

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.plugin.EnforcedState
import app.aaps.plugins.aps.openAPSBoost.OpenAPSBoostPlugin
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockingDetails

class BoostPluginRegistrationTest {
    @Test fun `the real app graph exposes selectable Boost V6 without selecting it automatically`() {
        val plugins = testRoot().contributedPlugins
        val plugin = plugins[236]
        assertThat(plugin).isInstanceOf(OpenAPSBoostPlugin::class.java)
        val boost = plugin as OpenAPSBoostPlugin
        assertThat(mockingDetails(boost).isMock).isFalse()
        assertThat(boost.getType()).isEqualTo(PluginType.APS)
        assertThat(boost.enforcedState()).isNull()
        assertThat(boost.isDefault()).isFalse()
        assertThat(boost.isEnabled()).isFalse()
        val inspection = checkNotNull(plugins[235])
        assertThat(inspection.getType()).isEqualTo(PluginType.GENERAL)
        assertThat(inspection.enforcedState()).isEqualTo(EnforcedState.Disabled)
        assertThat(inspection.pluginDescription.showInList()).isFalse()
    }
}
