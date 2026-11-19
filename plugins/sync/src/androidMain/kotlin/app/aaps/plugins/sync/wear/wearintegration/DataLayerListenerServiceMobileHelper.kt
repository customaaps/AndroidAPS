package app.aaps.plugins.sync.wear.wearintegration

import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationHolder
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/*
    This code replaces  following
    val alarm = Intent(context, DataLayerListenerServiceMobile::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(alarm) else context.startService(alarm)

    it fails randomly with error
    Context.startForegroundService() did not then call Service.startForeground(): ServiceRecord{e317f7e u0 info.nightscout.nsclient/info.nightscout.androidaps.services.DataLayerListenerServiceMobile}

 */
@SingleIn(AppScope::class)
@Inject
class DataLayerListenerServiceMobileHelper(
    private val notificationHolder: NotificationHolder,
    private val aapsLogger: AAPSLogger,
    private val fabricPrivacy: FabricPrivacy
) {

    /**
     * Android refuses a start from the background with [ForegroundServiceStartNotAllowedException],
     * and on Android 14+ can refuse the `connectedDevice` foreground with [SecurityException] when
     * `BLUETOOTH_CONNECT` is not granted at that moment. Both would end the app as an uncaught throw
     * on the main thread, so they are caught and reported here (the same guard `DummyServiceHelper`
     * has). The start is not retried here: WearPlugin runs this again at its next start.
     */
    fun startService(context: Context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                // The binder of the service that returns the instance that is created.
                val binder: DataLayerListenerServiceMobile.LocalBinder = service as DataLayerListenerServiceMobile.LocalBinder

                val dataLayerListenerServiceMobile: DataLayerListenerServiceMobile = binder.getService()

                try {
                    context.startForegroundService(Intent(context, DataLayerListenerServiceMobile::class.java))

                    // This is the key: Without waiting Android Framework to call this method
                    // inside Service.onCreate(), immediately call here to post the notification.
                    dataLayerListenerServiceMobile.startForeground(notificationHolder.notificationID, notificationHolder.notification)
                } catch (e: ForegroundServiceStartNotAllowedException) {
                    notAllowed(dataLayerListenerServiceMobile, e)
                } catch (e: SecurityException) {
                    notAllowed(dataLayerListenerServiceMobile, e)
                } finally {
                    // Release the connection to prevent leaks.
                    context.unbindService(this)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
            }
        }

        try {
            context.bindService(Intent(context, DataLayerListenerServiceMobile::class.java), connection, Context.BIND_AUTO_CREATE)
        } catch (ignored: RuntimeException) {
            // This is probably a broadcast receiver context even though we are calling getApplicationContext().
            // Just call startForegroundService instead since we cannot bind a service to a
            // broadcast receiver context. The service also have to call startForeground in
            // this case.
            try {
                context.startForegroundService(Intent(context, DataLayerListenerServiceMobile::class.java))
            } catch (e: ForegroundServiceStartNotAllowedException) {
                notAllowed(null, e)
            }
        }
    }

    fun stopService(context: Context) {
        context.stopService(Intent(context, DataLayerListenerServiceMobile::class.java))
    }

    /**
     * Logs the refusal. When the start itself went through but [SecurityException] stopped the
     * foreground call, the service has about 5 s to call `startForeground` or Android kills the
     * process - nothing else calls it here, so the service is stopped right away. A
     * `WearableListenerService` comes back when the next Wear event needs it.
     */
    private fun notAllowed(service: DataLayerListenerServiceMobile?, e: Exception) {
        aapsLogger.warn(LTag.WEAR, "Android does not allow DataLayerListenerServiceMobile into the foreground now: ${e.message}")
        fabricPrivacy.logException(e)
        fabricPrivacy.logCustom("WearForegroundServiceRefused")
        service?.stopSelf()
    }
}