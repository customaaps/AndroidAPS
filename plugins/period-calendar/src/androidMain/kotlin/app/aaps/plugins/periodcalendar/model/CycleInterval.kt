package app.aaps.plugins.periodcalendar.model

data class CycleInterval(
    val startTimestamp: Long,
    val lengthDays: Int,
    val ongoing: Boolean,
)
