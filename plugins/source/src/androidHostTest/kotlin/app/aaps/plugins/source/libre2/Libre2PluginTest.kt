package app.aaps.plugins.source.libre2

import app.aaps.core.keys.BooleanKey
import app.aaps.plugins.source.XdripSourcePlugin
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class Libre2PluginTest : TestBaseWithProfile() {
    @Test
    fun `LibreView selection can be switched off in configuration`() {
        val libreView = LibreViewPlugin(context, preferences, rh, aapsLogger, mock(), mock(), mock())
        assertThat(libreView.getType()).isEqualTo(app.aaps.core.data.plugin.PluginType.SYNC)
        assertThat(libreView.enforcedState()).isNull()
    }

    @Test
    fun `Libre2 exposes the same sensor registration preference as xDrip`() {
        val libre2 = Libre2Plugin(context, rh, aapsLogger, preferences, mock(), mock())
        val xdrip = XdripSourcePlugin(rh, aapsLogger, preferences, config, mock())
        assertThat(libre2.getPreferenceScreenContent().items).contains(BooleanKey.BgSourceCreateSensorChange)
        assertThat(libre2.getPreferenceScreenContent().items).isEqualTo(xdrip.getPreferenceScreenContent().items)
    }
}
