package app.aaps.plugins.aps.openAPSBoost.activity

import app.aaps.core.data.format.NumberFormat

/** Uses the application's existing native number formatter; does not participate in arithmetic. */
internal actual fun activityFormat(pattern: String, value: Double): String {
    val places = when (pattern) {
        "%.1f" -> 1
        "%.2f" -> 2
        else -> error("Unsupported activity diagnostic format: $pattern")
    }
    return NumberFormat.withDecimalsHalfUp(places).format(value)
}
