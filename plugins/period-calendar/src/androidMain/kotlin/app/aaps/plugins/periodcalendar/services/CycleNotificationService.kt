package app.aaps.plugins.periodcalendar.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.plugins.periodcalendar.R
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

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

        private var schedulerScope: CoroutineScope? = null
        private var schedulerJob: Job? = null

        fun start() {
            stop()
            try {
                createNotificationChannel()
            } catch (e: Exception) {
                aapsLogger.error(LTag.CORE, "Failed to create period calendar notification channel", e)
            }
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            schedulerScope = scope
            schedulerJob = scope.launch {
                checkSafely()
                while (isActive) {
                    delay(millisUntilNextMidnight())
                    checkSafely()
                }
            }
        }

        fun stop() {
            schedulerJob?.cancel()
            schedulerScope?.cancel()
            schedulerJob = null
            schedulerScope = null
        }

        fun createNotificationChannel() {
            val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.period_calendar_short),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            channel.description = context.getString(R.string.description_period_calendar)
            notificationManager.createNotificationChannel(channel)
        }

        fun checkAndNotify() {
            if (!cycleCounterService.isCycleStarted()) return

            checkCycleStartNotification()
            checkPhaseChangeNotification()
        }

        private fun checkSafely() {
            try {
                checkAndNotify()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                aapsLogger.error(LTag.CORE, "Period calendar notification check failed", e)
            }
        }

        private fun checkCycleStartNotification() {
            if (!notifyCycleStart) return

            val currentStart = cycleCounterService.cycleStartTimestamp
            if (currentStart <= 0L) return
            val lastNotified = sp.getLong(KEY_LAST_NOTIFIED_CYCLE_START, 0L)

            if (lastNotified == 0L) {
                sp.putLong(KEY_LAST_NOTIFIED_CYCLE_START, currentStart)
                return
            }
            if (currentStart != lastNotified) {
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
            val lastNotifiedPhase = try {
                sp.getString(KEY_LAST_NOTIFIED_PHASE, "")
            } catch (e: Exception) {
                aapsLogger.error(LTag.CORE, "Failed to read last notified phase", e)
                ""
            }

            if (currentPhase != null && currentPhase.label != lastNotifiedPhase) {
                if (lastNotifiedPhase.isEmpty()) {
                    sp.putString(KEY_LAST_NOTIFIED_PHASE, currentPhase.label)
                    return
                }
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
            val notificationManager = NotificationManagerCompat.from(context)
            if (!notificationManager.areNotificationsEnabled()) {
                aapsLogger.debug(LTag.CORE, "Notifications disabled, skipping period calendar notification")
                return
            }
            val pendingIntent =
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    PendingIntent.getActivity(
                        context,
                        id,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                }

            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(content)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .apply { pendingIntent?.let { setContentIntent(it) } }
                    .build()

            try {
                notificationManager.notify(id, notification)
            } catch (e: Exception) {
                aapsLogger.error(LTag.CORE, "Failed to post period calendar notification", e)
            }
        }

        private fun millisUntilNextMidnight(): Long {
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
            return Duration.between(now, nextMidnight).toMillis().coerceAtLeast(60_000L)
        }
    }
