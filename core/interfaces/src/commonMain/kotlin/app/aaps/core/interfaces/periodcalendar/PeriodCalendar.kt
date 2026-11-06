package app.aaps.core.interfaces.periodcalendar

interface PeriodCalendar {

    /** Returns the formatted cycle day string (e.g. "01ME", "15", "--") or empty if plugin not set up. */
    fun getCycleDayText(): String = "--"

    /** Returns the current cycle day (1..99) or 0 when the cycle is not started. */
    fun getCurrentCycleDay(): Int = 0

    /** Returns the current phase label or null when the cycle is not started or no phase matches. */
    fun getCurrentPhaseLabel(): String? = null

    /** Returns the configured phase labels in display order. */
    fun getPhaseLabels(): List<String> = emptyList()

    /** Returns the current phase colour as #RRGGBB, or null when no phase matches. */
    fun getCurrentPhaseColorHex(): String? = null
}
