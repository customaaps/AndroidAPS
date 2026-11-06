package app.aaps.plugins.periodcalendar.model

import kotlinx.serialization.Serializable

@Serializable
data class CycleStartRecord(
    val timestamp: Long,
)
