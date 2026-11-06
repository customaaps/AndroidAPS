package app.aaps.plugins.periodcalendar.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.plugins.periodcalendar.R
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
class CycleNotificationService
    @Inject
    constructor(
        private val context: Context,
        private val sp: SP,
        private val cycleCounterService: CycleCounterService,
        private val aapsLogger: AAPSLogger,
    ) {
        companion object {
            private const val CHANNEL_ID = "period_calendar_notifications"
            private const val NOTIFICATION_ID_CYCLE_START = 10001
            private const val NOTIFICATION_ID_PHASE_CHANGE = 10002
            private const val KEY_LAST_NOTIFIED_CYCLE_START = "period_calendar_last_notified_cycle_start"
            private const val KEY_LAST_NOTIFIED_PHASE = "period_calendar_last_notified_phase"
            private const val KEY_NOTIFY_CYCLE_START = "period_calendar_notify_cycle_start"
            private const val KEY_NOTIFY_PHASE_CHANGE = "period_calendar_notify_phase_change"
        }

        var notifyCycleStart: Boolean
            get() = sp.getBoolean(KEY_NOTIFY_CYCLE_START, true)
            set(value) = sp.putBoolean(KEY_NOTIFY_CYCLE_START, value)

        var notifyPhaseChange: Boolean
            get() = sp.getBoolean(KEY_NOTIFY_PHASE_CHANGE, true)
            set(value) = sp.putBoolean(KEY_NOTIFY_PHASE_CHANGE, value)

        fun createNotificationChannel() {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.period_calendar_short),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            channel.description = context.getString(R.string.description_period_calendar)
            notificationManager.createNotificationChannel(channel)
        }

        fun checkAndNotify(pluginEnabled: Boolean) {
            if (!pluginEnabled) {
                aapsLogger.debug(LTag.CORE, "Period calendar plugin disabled, skipping notifications")
                return
            }
            if (!cycleCounterService.isCycleStarted()) return

            checkCycleStartNotification()
            checkPhaseChangeNotification()
        }

        private fun checkCycleStartNotification() {
            if (!notifyCycleStart) return

            val currentStart = cycleCounterService.cycleStartTimestamp
            val lastNotified = sp.getLong(KEY_LAST_NOTIFIED_CYCLE_START, 0L)

            if (currentStart != lastNotified && currentStart > 0L) {
                aapsLogger.debug(LTag.CORE, "Sending new cycle notification")
                sendNotification(
                    NOTIFICATION_ID_CYCLE_START,
                    context.getString(R.string.period_calendar_short),
                    context.getString(R.string.notification_new_cycle),
                )
                sp.putLong(KEY_LAST_NOTIFIED_CYCLE_START, currentStart)
            }
        }

        private fun checkPhaseChangeNotification() {
            if (!notifyPhaseChange) return

            val currentPhase = cycleCounterService.getCurrentPhase()
            val lastNotifiedPhase = sp.getString(KEY_LAST_NOTIFIED_PHASE, "")

            if (currentPhase != null && currentPhase.label != lastNotifiedPhase) {
                aapsLogger.debug(LTag.CORE, "Sending phase change notification: ${currentPhase.label}")
                sendNotification(
                    NOTIFICATION_ID_PHASE_CHANGE,
                    context.getString(R.string.period_calendar_short),
                    context.getString(R.string.notification_phase_changed, currentPhase.label),
                )
                sp.putString(KEY_LAST_NOTIFIED_PHASE, currentPhase.label)
            }
        }

        private fun sendNotification(
            id: Int,
            title: String,
            content: String,
        ) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val intent =
                Intent().apply {
                    setClassName(context, "app.aaps.activities.MainActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            val pendingIntent =
                PendingIntent.getActivity(
                    context,
                    id,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(content)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build()

            notificationManager.notify(id, notification)
        }
    }
