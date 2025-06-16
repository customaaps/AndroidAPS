package app.aaps.core.interfaces.aps

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(InternalSerializationApi::class)
@Serializable
data class LoopKitProfile(
    val dia: Double,
    val cob: Double,
    val iob: Double,
)
