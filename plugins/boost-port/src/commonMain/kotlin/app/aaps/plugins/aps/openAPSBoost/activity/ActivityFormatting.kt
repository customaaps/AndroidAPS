package app.aaps.plugins.aps.openAPSBoost.activity

/** Diagnostic text only. Keeps the source Formatter semantics on the Android/JVM targets. */
internal expect fun activityFormat(pattern: String, value: Double): String
