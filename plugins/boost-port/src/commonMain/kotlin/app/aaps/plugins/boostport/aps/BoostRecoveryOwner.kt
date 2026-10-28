package app.aaps.plugins.boostport.aps

import app.aaps.core.data.model.TT
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.plugins.aps.openAPSBoost.replay.BoostProfileContext

/** Source exercise transitions and recovery TT ownership, scoped to one sequential APS owner. */
internal class BoostRecoveryOwner(private val persistence: PersistenceLayer) {
    data class Settings(val enabled: Boolean, val hours: Double, val targetMgdl: Double,
        val scale: Double, val minDurationMinutes: Int)
    data class State(val exerciseActive: Boolean = false, val exerciseStartMs: Long = 0,
        val lastExerciseState: String = "ACTIVE", val endMs: Long = 0,
        val scale: Double = 1.0, val targetOffset: Double = 0.0, val ownedTtStartMs: Long? = null)
    data class Update(val context: BoostProfileContext, val cancelledOwnedTarget: Boolean)
    var state = State()
        private set

    suspend fun prepare(settings: Settings, context: BoostProfileContext, recentLow60: Double,
        recoveryNote: String? = null, reboundNote: String? = null): Update {
        require(settings.hours.isFinite() && settings.hours in 0.5..8.0 && settings.targetMgdl.isFinite() &&
            settings.targetMgdl in 90.0..200.0 && settings.scale.isFinite() && settings.scale in 0.0..1.0 &&
            settings.minDurationMinutes in 1..120)
        var next = state
        val now = context.atMs
        if (settings.enabled) {
            val active = context.activityState in setOf("ACTIVE", "VIGOROUS_AEROBIC", "MODERATE_AEROBIC", "LIGHT_AEROBIC", "RESISTANCE")
            if (active && !next.exerciseActive) next = next.copy(exerciseStartMs = now)
            else if (!active && next.exerciseActive && (now - next.exerciseStartMs) / 60_000L >= settings.minDurationMinutes) {
                val (durationMultiplier, targetOffset, scaleMultiplier) = when (next.lastExerciseState) {
                    "VIGOROUS_AEROBIC" -> Triple(1.25, 0.0, 0.8)
                    "RESISTANCE" -> Triple(1.5, 10.0, 1.2)
                    "LIGHT_AEROBIC" -> Triple(0.5, 0.0, 1.4)
                    else -> Triple(1.0, 0.0, 1.0)
                }
                val duration = (settings.hours * 3_600_000L * durationMultiplier).toLong()
                next = next.copy(endMs = now + duration, scale = (settings.scale * scaleMultiplier).coerceIn(0.1, 1.0), targetOffset = targetOffset)
                if (persistence.getTemporaryTargetActiveAt(now) == null) {
                    val target = settings.targetMgdl + targetOffset
                    persistence.insertAndCancelCurrentTemporaryTarget(TT(timestamp = now, duration = duration,
                        reason = TT.Reason.ACTIVITY, lowTarget = target, highTarget = target),
                        Action.TT, Sources.Aaps, recoveryNote, listOf(ValueWithUnit.TETTReason(TT.Reason.ACTIVITY),
                            ValueWithUnit.Mgdl(target), ValueWithUnit.Minute((duration / 60_000L).toInt())))
                    next = next.copy(ownedTtStartMs = now)
                }
            }
            next = next.copy(exerciseActive = active,
                lastExerciseState = if (active) context.activityState else next.lastExerciseState)
        }
        var cancelled = false
        if (recentLow60 < 100.0 && context.glucose > recentLow60 + 20 && next.ownedTtStartMs != null) {
            val active = persistence.getTemporaryTargetActiveAt(now)
            if (active?.timestamp == next.ownedTtStartMs && active?.reason == TT.Reason.ACTIVITY) {
                persistence.cancelCurrentTemporaryTargetIfAny(now, Action.TT_REMOVED, Sources.Aaps, reboundNote,
                    listOf(ValueWithUnit.TETTReason(TT.Reason.ACTIVITY)))
                next = next.copy(endMs = 0L, ownedTtStartMs = null)
                cancelled = true
            }
        }
        state = next
        // Source v6Min/Max/Target are taken from the earlier activity result, even
        // when the recovery TT is cancelled later in this cycle. Only TT ownership
        // and the recovery window change now; a later cycle sees restored targets.
        return Update(context.copy(recoveryEnabled = settings.enabled, recoveryEndMs = next.endMs,
            recoveryScale = next.scale, temporaryTargetOverride = false.takeIf { cancelled }), cancelled)
    }
}
