package app.aaps.pump.apex.misc

import app.aaps.core.interfaces.notifications.NotificationId

/** Internal numeric adapter for the transport. Every posted card resolves to a typed core id. */
internal object ApexNotificationCode {
    const val URGENT = 0
    const val NORMAL = 2
    val PUMP_ERROR = NotificationId.PUMP_ERROR.ordinal
    val PUMP_SUSPENDED = NotificationId.PUMP_SUSPENDED.ordinal
    val PUMP_UNREACHABLE = NotificationId.PUMP_UNREACHABLE.ordinal
    val PUMP_IS_LOCKED = NotificationId.APEX_PUMP_LOCKED.ordinal
    val APEX_SERVICE_MSG = NotificationId.APEX_INVALID_OBJECT.ordinal
}
