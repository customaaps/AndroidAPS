package app.aaps.plugins.boostport.telemetry

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * KMP, read-only adaptation of BoostV5AutoConfigApply's per-key onboarding
 * rules. It returns a hypothetical plan, and contains NO Preferences writes,
 * state changes, knob application, APS/Pump linkage, or dosing instructions.
 *
 * Historically persisted factory defaults (including old release defaults)
 * do not count as explicit user tuning. A true override is left untouched.
 * The raise guard is applied only to the three dose-cap keys. The cumulative
 * cap's historical *suggestion* is recomputed from OPERATIVE per-shot values,
 * not the unrelated source derivation, to preserve the V5 invariant.
 */
internal object BoostV5ConfigResolutionAudit {
    const val TBR70_RAISE_GUARD = 4.0
    const val SEV54_RAISE_GUARD = 1.0
    const val DEFAULT_EPS = 1e-4

    enum class Knob {
        AGGRESSION, HYPO_CAUTION, CONFIRMED_CAP, COMMITTED_CAP,
        CUMULATIVE_SMB_CAP, MAX_IOB, BOLUS_CAP, PRIMER_CAP,
    }

    enum class Outcome {
        ALREADY_RESOLVED, KEEP_USER_TUNED, WITHHOLD_CAP_RAISE, WOULD_APPLY,
    }

    data class Resolution(
        val knob: Knob,
        val outcome: Outcome,
        val current: Double,
        val suggested: Double,
        val operative: Double,
    )

    data class Plan(
        val resolutions: List<Resolution>,
        val operative: Map<Knob, Double>,
        val raiseGuardTripped: Boolean,
    )

    private val order = listOf(
        Knob.AGGRESSION, Knob.HYPO_CAUTION, Knob.CONFIRMED_CAP,
        Knob.COMMITTED_CAP, Knob.CUMULATIVE_SMB_CAP, Knob.MAX_IOB,
        Knob.BOLUS_CAP, Knob.PRIMER_CAP,
    )
    private val capKeys = setOf(
        Knob.CONFIRMED_CAP, Knob.COMMITTED_CAP, Knob.CUMULATIVE_SMB_CAP
    )
    private val defaults = mapOf(
        Knob.AGGRESSION to 1.0,
        Knob.HYPO_CAUTION to 1.0,
        Knob.CONFIRMED_CAP to 2.5,
        Knob.COMMITTED_CAP to 0.5,
        Knob.CUMULATIVE_SMB_CAP to 10.0,
        Knob.MAX_IOB to 1.0,
        Knob.BOLUS_CAP to 2.5,
        Knob.PRIMER_CAP to 0.0,
    )
    private val olderDefaults = mapOf(
        Knob.CONFIRMED_CAP to listOf(1.0),
        Knob.COMMITTED_CAP to listOf(0.25),
        Knob.CUMULATIVE_SMB_CAP to listOf(1.5, 6.0),
    )

    fun isUserTuned(knob: Knob, storedValue: Double?): Boolean {
        if (storedValue == null) return false
        if (!storedValue.isFinite()) return true // reject it in inspect; never overwrite as default
        val factory = listOfNotNull(defaults[knob]) + (olderDefaults[knob] ?: emptyList())
        return factory.none { abs(storedValue - it) <= DEFAULT_EPS }
    }

    /** Parity with V5 cumulativeCap60Min, for inspection only. */
    fun cumulativeCapSuggestion(confirmedCap: Double, committedCap: Double): Double? {
        if (!confirmedCap.isFinite() || !committedCap.isFinite() ||
            confirmedCap < 0.0 || committedCap < 0.0
        ) return null
        val total = confirmedCap + 2.0 * committedCap
        if (!total.isFinite()) return null
        return ((total.coerceIn(1.0, 10.0) * 10.0).roundToInt() / 10.0)
    }

    /**
     * The caller provides immutable copies of existing, resolved, and
     * separately derived values. Absent derived caps/multipliers fail closed;
     * the derived cumulative value is intentionally NOT consumed.
     *
     * Null = invalid or insufficient diagnostic data. The result is never
     * written to live preference keys.
     */
    fun inspect(
        stored: Map<Knob, Double>,
        alreadyResolved: Set<Knob>,
        derived: Map<Knob, Double>,
        tbrBelow70Pct: Double,
        timeBelow54Pct: Double,
    ): Plan? {
        if (!tbrBelow70Pct.isFinite() || !timeBelow54Pct.isFinite() ||
            tbrBelow70Pct !in 0.0..100.0 || timeBelow54Pct !in 0.0..100.0 ||
            stored.values.any { !it.isFinite() || it < 0.0 } ||
            derived.values.any { !it.isFinite() || it < 0.0 }
        ) return null

        val guard = tbrBelow70Pct > TBR70_RAISE_GUARD ||
            timeBelow54Pct >= SEV54_RAISE_GUARD
        val resolutions = mutableListOf<Resolution>()
        val operative = mutableMapOf<Knob, Double>()
        for (key in order) {
            val current = stored[key] ?: defaults.getValue(key)
            val suggestion = if (key == Knob.CUMULATIVE_SMB_CAP) {
                cumulativeCapSuggestion(
                    operative.getValue(Knob.CONFIRMED_CAP),
                    operative.getValue(Knob.COMMITTED_CAP),
                ) ?: return null
            } else {
                derived[key] ?: return null
            }
            val outcome = when {
                key in alreadyResolved -> Outcome.ALREADY_RESOLVED
                isUserTuned(key, stored[key]) -> Outcome.KEEP_USER_TUNED
                key in capKeys && suggestion > current + DEFAULT_EPS && guard ->
                    Outcome.WITHHOLD_CAP_RAISE
                else -> Outcome.WOULD_APPLY
            }
            val effective = if (outcome == Outcome.WOULD_APPLY) suggestion else current
            resolutions += Resolution(key, outcome, current, suggestion, effective)
            operative[key] = effective
        }
        return Plan(resolutions, operative.toMap(), guard)
    }

    /**
     * Inspect V5's schema-v2 reopening rule without clearing any actual
     * preferences: resolved keys at a factory default become eligible again.
     */
    fun legacyFactoryFlagsToReopen(
        resolved: Set<Knob>,
        stored: Map<Knob, Double>,
        storedSchemaVersion: Int,
    ): Set<Knob> =
        if (storedSchemaVersion >= 2 || storedSchemaVersion < 0) emptySet()
        else resolved.filterTo(mutableSetOf()) { !isUserTuned(it, stored[it]) }
}
