package app.aaps.implementations

import androidx.documentfile.provider.DocumentFile
import app.aaps.core.interfaces.configuration.ExternalOptions
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.di.ExternalOptionsOverride
import app.aaps.implementation.maintenance.migration.FileKeyValueStore
import app.aaps.implementation.sharedPreferences.PreferencesImpl
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ConfigImplTest {
    private val files = mock<FileListProvider>()
    private val store = FileKeyValueStore()

    private fun config(optionsOverride: ExternalOptionsOverride = ExternalOptionsOverride.NONE) =
        ConfigImpl({ files }, optionsOverride, store)

    @Test fun engineeringSwitchUpdatesRealPreferencesWithoutRecursionOrRestart() {
        val config = config()
        val preferences = PreferencesImpl(store, { mock() }, { mock() }, { mock() }, mock(), config, mock())
        preferences.put(BooleanKey.GeneralSimpleMode, false)
        preferences.put(IntKey.OverviewResetBolusPercentageTime, 32)

        assertThat(config.isEngineeringMode()).isFalse()
        assertThat(preferences.get(BooleanKey.EngineeringMode)).isFalse()
        assertThat(preferences.get(IntKey.OverviewResetBolusPercentageTime)).isEqualTo(16)

        preferences.put(BooleanKey.EngineeringMode, true)
        assertThat(config.isEngineeringMode()).isTrue()
        assertThat(preferences.get(BooleanKey.EngineeringMode)).isTrue()
        assertThat(preferences.get(IntKey.OverviewResetBolusPercentageTime)).isEqualTo(32)

        preferences.put(BooleanKey.EngineeringMode, false)
        assertThat(config.isEngineeringMode()).isFalse()
        assertThat(preferences.get(IntKey.OverviewResetBolusPercentageTime)).isEqualTo(16)
    }

    @Test fun engineeringFileStillEnablesModeWhenSwitchIsOff() {
        val directory = mock<DocumentFile>()
        val marker = mock<DocumentFile>()
        whenever(files.ensureExtraDirExists()).thenReturn(directory)
        whenever(directory.findFile("engineering_mode")).thenReturn(marker)
        store.putBoolean(BooleanKey.EngineeringMode.key, false)
        assertThat(config().isEngineeringMode()).isTrue()
    }

    @Test fun externalEngineeringOverrideRemainsLive() {
        var enabled = setOf(ExternalOptions.ENGINEERING_MODE)
        val config = config(ExternalOptionsOverride { enabled })
        assertThat(config.isEngineeringMode()).isTrue()
        enabled = emptySet()
        assertThat(config.isEngineeringMode()).isFalse()
    }

    @Test fun engineeringSwitchDoesNotEnableOtherExternalOptions() {
        store.putBoolean(BooleanKey.EngineeringMode.key, true)
        val config = config()
        assertThat(config.isEngineeringMode()).isTrue()
        for (option in ExternalOptions.entries.filter { it != ExternalOptions.ENGINEERING_MODE })
            assertThat(config.isEnabled(option)).isFalse()
    }
}
