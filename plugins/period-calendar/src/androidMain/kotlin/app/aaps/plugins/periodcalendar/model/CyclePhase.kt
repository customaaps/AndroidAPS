package app.aaps.plugins.periodcalendar.model

import kotlinx.serialization.Serializable

@Serializable
data class CyclePhase(
    val dayFrom: Int,
    val dayTo: Int,
    val label: String,
    val colorHex: String = "#FFFFFF",
) {
    fun containsDay(day: Int): Boolean = day in dayFrom..dayTo

    override fun toString(): String = "$dayFrom-$dayTo:$label"

    companion object {
        fun defaultPhases(): List<CyclePhase> =
            listOf(
                CyclePhase(1, 5, "ME", "#E57373"),
                CyclePhase(6, 10, "FO", "#64B5F6"),
                CyclePhase(11, 13, "FE", "#81C784"),
                CyclePhase(14, 14, "OV", "#FFD54F"),
                CyclePhase(15, 21, "L1", "#BA68C8"),
                CyclePhase(22, 28, "L2", "#4DB6AC"),
            )
    }
}
