package app.aaps.pump.apex.misc

import androidx.annotation.RawRes
import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.ui.R as CoreUiR
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** Owns the driver's cards; the core owns sound arbitration and Android notification lifetimes. */
@SingleIn(AppScope::class)
@Inject
class ApexNotifications(private val manager: NotificationManager) {
    data class Handle(val id: Int)
    private val freeAlarmIds = ArrayDeque((ALARM_CARD_FIRST until ALARM_CARD_FIRST + ALARM_CARD_SLOTS).toList())

    private fun notificationId(id: Int): NotificationId = when (id) {
        in ALARM_CARD_FIRST until ALARM_CARD_FIRST + ALARM_CARD_SLOTS -> alarmIds[id - ALARM_CARD_FIRST]
        ALARM_CARD_OVERFLOW -> NotificationId.APEX_ALARM_OVERFLOW
        RESERVOIR_LOW -> NotificationId.APEX_RESERVOIR_LOW
        RESERVOIR_CRITICAL -> NotificationId.APEX_RESERVOIR_CRITICAL
        PERMISSION_MISSING -> NotificationId.APEX_PERMISSION_MISSING
        PUMP_LINK_LOST -> NotificationId.APEX_PUMP_LINK_LOST
        BLUETOOTH_OFF -> NotificationId.APEX_BLUETOOTH_OFF
        BOLUS_UNCONFIRMED -> NotificationId.APEX_BOLUS_UNCONFIRMED
        PASSWORD_REJECTED -> NotificationId.APEX_PASSWORD_REJECTED
        BOLUS_UNCONFIRMED_SMB -> NotificationId.APEX_BOLUS_UNCONFIRMED_SMB
        else -> requireNotNull(NotificationId.fromOrdinal(id)) { "Unknown Apex notification id: $id" }
    }

    @Synchronized
    fun post(id: Int, text: String, level: Int = notificationId(id).defaultLevel.ordinal, @RawRes soundRes: Int? = null): Handle {
        val sound = when (soundRes) {
            null -> null
            CoreUiR.raw.boluserror -> AlarmSound.BOLUS_ERROR
            CoreUiR.raw.alarm -> AlarmSound.ALARM
            else -> AlarmSound.ERROR
        }
        val nativeId = notificationId(id)
        val standing = manager.notifications.value.firstOrNull { it.id == nativeId }
        // A silent wording refresh must not silence an alarm that is still standing.
        val keepSound = sound == null && standing?.sound != null
        manager.post(id = nativeId, text = text,
            level = if (keepSound) standing!!.level else NotificationLevel.entries.getOrElse(level) { NotificationLevel.NORMAL },
            validMinutes = 0, sound = sound ?: standing?.sound)
        return Handle(id)
    }

    @Synchronized
    fun postAlarmCard(text: String, level: Int, @RawRes soundRes: Int?, into: Handle?): Handle {
        val id = into?.id ?: freeAlarmIds.removeFirstOrNull() ?: ALARM_CARD_OVERFLOW
        return post(id, text, level, soundRes)
    }

    @Synchronized
    fun dismiss(id: Int) {
        manager.dismiss(notificationId(id))
        if (id in ALARM_CARD_FIRST until ALARM_CARD_FIRST + ALARM_CARD_SLOTS && id !in freeAlarmIds) freeAlarmIds.addLast(id)
    }
    fun dismiss(handle: Handle) = dismiss(handle.id)
    fun isOnScreen(id: Int) = manager.notifications.value.any { it.id == notificationId(id) }
    fun isOnScreen(handle: Handle) = isOnScreen(handle.id)

    @Synchronized
    fun clearAlarmCards() {
        for (id in ALARM_CARD_FIRST..ALARM_CARD_OVERFLOW) dismiss(id)
    }

    companion object {
        const val ALARM_CARD_FIRST = 900
        const val ALARM_CARD_SLOTS = 10
        const val ALARM_CARD_OVERFLOW = 910
        const val RESERVOIR_LOW = 911
        const val RESERVOIR_CRITICAL = 912
        const val PERMISSION_MISSING = 913
        const val PUMP_LINK_LOST = 914
        const val BLUETOOTH_OFF = 915
        const val BOLUS_UNCONFIRMED = 916
        const val PASSWORD_REJECTED = 917
        const val BOLUS_UNCONFIRMED_SMB = 918
        val PUMP_LOCKED = NotificationId.APEX_PUMP_LOCKED.ordinal
        val INVALID_OBJECT = NotificationId.APEX_INVALID_OBJECT.ordinal
        private val alarmIds = listOf(
            NotificationId.APEX_ALARM_01, NotificationId.APEX_ALARM_02,
            NotificationId.APEX_ALARM_03, NotificationId.APEX_ALARM_04,
            NotificationId.APEX_ALARM_05, NotificationId.APEX_ALARM_06,
            NotificationId.APEX_ALARM_07, NotificationId.APEX_ALARM_08,
            NotificationId.APEX_ALARM_09, NotificationId.APEX_ALARM_10
        )
    }
}
