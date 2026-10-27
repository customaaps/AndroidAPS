package app.aaps.plugins.boostport.telemetry

/**
 * First disabled adaptation seam for the original DetermineBasalBoostV5.decide().
 *
 * Maps the orchestrator's external dependencies without importing Android APS
 * types. NEVER creates a V5Decision, executes dosing, or authorizes delivery.
 * The historical orchestrator requires Boost-flavoured (not vanilla) oref
 * insulinReq, ML predictions, an intact state and Phase-3 safeguards.
 */
internal object BoostV5OrchestratorPortContract {
    enum class Dependency {
        BOOST_OREF_BASE_INSULIN_REQ,
        GLUCOSE_HISTORY,
        IOB_AND_CONSTRAINTS,
        MEAL_AND_HYPO_ML,
        PROFILE_AND_ACTIVITY,
        V5_PERSISTED_STATE,
        PHASE3_SAFETY_GATES,
        V1_FALLBACK_AND_OVERRIDE_SEAM,
    }

    enum class Finding { BLOCKED_MISSING_DEPENDENCIES, BLOCKED_LIVE_EXECUTION_UNSUPPORTED }

    data class Report(
        val finding: Finding,
        val missing: Set<Dependency>,
        val liveExecutionSupported: Boolean = false,
    )

    private val required = Dependency.entries.toSet()

    /**
     * Source dependencies supplied as declarations by migration tooling, not
     * runtime service instances. Even full coverage remains blocked because
     * the real orchestrator and clinical verification have not been ported.
     */
    fun inspect(available: Set<Dependency>): Report {
        val missing = required - available
        return Report(
            if (missing.isEmpty()) Finding.BLOCKED_LIVE_EXECUTION_UNSUPPORTED
            else Finding.BLOCKED_MISSING_DEPENDENCIES,
            missing,
        )
    }
}
