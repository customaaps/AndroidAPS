package app.aaps.plugins.boostport.telemetry

/**
 * Passive KMP extraction of BoostDosingPreferences' stored-value versus
 * Simple-Mode-masked-value distinction. The caller provides every value:
 * this class does not access Preferences, toggle Simple Mode, alter key
 * defaults, run APS, compute insulin or issue pump commands.
 */
internal object BoostStoredPreferenceResolutionAudit {
    data class Result<T>(
        val resolvedValue: T,
        val usedStoredValue: Boolean,
        val differedFromMaskedRead: Boolean,
    )

    /**
     * Legacy getBoostDosing returns the raw stored value if present, or the
     * declared factory default. The masked UI read is used for comparison only.
     */
    fun <T> inspect(
        storedValue: T?,
        factoryDefault: T,
        maskedReadValue: T,
    ): Result<T> {
        val resolved = storedValue ?: factoryDefault
        return Result(
            resolvedValue = resolved,
            usedStoredValue = storedValue != null,
            differedFromMaskedRead = resolved != maskedReadValue,
        )
    }

    /**
     * UnitDouble defaults are mg/dL-canonical, while persisted values and
     * preference reads are in display units. The caller must provide the
     * converted fallback; this helper intentionally does not perform any
     * live glucose-unit conversion.
     */
    fun inspectUnitDouble(
        storedDisplayValue: Double?,
        convertedDefaultDisplayValue: Double,
        maskedReadDisplayValue: Double,
    ): Result<Double>? {
        if (!convertedDefaultDisplayValue.isFinite() ||
            !maskedReadDisplayValue.isFinite() ||
            (storedDisplayValue != null && !storedDisplayValue.isFinite())
        ) return null
        return inspect(storedDisplayValue,
            convertedDefaultDisplayValue, maskedReadDisplayValue)
    }
}
