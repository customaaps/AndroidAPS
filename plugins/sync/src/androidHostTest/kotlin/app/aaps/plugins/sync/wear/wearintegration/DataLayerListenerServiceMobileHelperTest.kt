package app.aaps.plugins.sync.wear.wearintegration

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.ServiceConnection
import app.aaps.core.interfaces.notifications.NotificationHolder
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * WearPlugin arms the helper at every app start. Android refuses a background foreground-service
 * start with ForegroundServiceStartNotAllowedException, and that exception used to be uncaught here,
 * killing the whole process on the main thread (nightscout issue #5616). Pin that the refusal is
 * caught and reported instead of thrown.
 */
class DataLayerListenerServiceMobileHelperTest : TestBase() {

    @Mock lateinit var context: Context
    @Mock lateinit var notificationHolder: NotificationHolder
    @Mock lateinit var fabricPrivacy: FabricPrivacy

    private lateinit var sut: DataLayerListenerServiceMobileHelper

    @BeforeEach
    fun prepare() {
        sut = DataLayerListenerServiceMobileHelper(notificationHolder, aapsLogger, fabricPrivacy)
    }

    @Test
    fun refusedForegroundStartInTheFallbackIsCaughtAndReported() {
        whenever(context.bindService(anyOrNull(), any<ServiceConnection>(), any<Int>())).thenThrow(RuntimeException("cannot bind"))
        whenever(context.startForegroundService(anyOrNull())).thenThrow(ForegroundServiceStartNotAllowedException("refused"))

        sut.startService(context)

        verify(fabricPrivacy).logException(any())
        verify(fabricPrivacy).logCustom("WearForegroundServiceRefused")
    }
}
