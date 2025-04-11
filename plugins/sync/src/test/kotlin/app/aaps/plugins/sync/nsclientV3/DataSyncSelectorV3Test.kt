package app.aaps.plugins.sync.nsclientV3

import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.nsclient.StoreDataForDb
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.source.NSClientSource
import app.aaps.core.keys.BooleanKey
import app.aaps.plugins.sync.nsShared.StoreDataForDbImpl
import app.aaps.shared.tests.TestBaseWithProfile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`

class DataSyncSelectorV3Test : TestBaseWithProfile() {

    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var uel: UserEntryLogger
    @Mock lateinit var virtualPump: VirtualPump
    @Mock lateinit var nsClientSource: NSClientSource

    private lateinit var storeDataForDb: StoreDataForDb
    private lateinit var sut: DataSyncSelectorV3

    @BeforeEach
    fun setUp() {
        storeDataForDb = StoreDataForDbImpl(aapsLogger, rxBus, persistenceLayer, preferences, uel, config, nsClientSource, virtualPump)
        sut = DataSyncSelectorV3(preferences, aapsLogger, dateUtil, profileFunction, activePlugin, persistenceLayer, rxBus, storeDataForDb, config)
    }

    @AfterEach
    fun tearDown() {
    }

    @Test
    fun bgUploadEnabledTest() {

        class NSClientSourcePlugin() : NSClientSource, BgSource {

            override fun isEnabled(): Boolean = true
            override fun detectSource(glucoseValue: GV) {}
        }
        val nsClientSourcePlugin = NSClientSourcePlugin()

        `when`(preferences.get(BooleanKey.BgSourceUploadToNs)).thenReturn(false)
        `when`(activePlugin.activeBgSource).thenReturn(nsClientSourcePlugin)

        
    }

}