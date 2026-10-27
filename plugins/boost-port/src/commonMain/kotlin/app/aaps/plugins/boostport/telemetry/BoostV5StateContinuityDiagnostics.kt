package app.aaps.plugins.boostport.telemetry

/**
 * Passive, immutable replay of the V5StateStore cache-precedes-persistence rule.
 *
 * Legacy V5 writes its in-process cache before an asynchronous preference write,
 * so a rapid second invocation must see the just-saved state, not a stale JSON
 * record. This represents that read order using caller-supplied data only.
 *
 * No actual cache, Preferences, JSON I/O, APS loop or pump integration exists.
 */
internal object BoostV5StateContinuityDiagnostics {

    enum class Source { MEMORY, PERSISTED, DEFAULT_EMPTY, REJECTED_PERSISTED }

    data class Memory(
        val current: BoostV5StateSnapshotCodec.Snapshot? = null,
    )

    data class Inspection(
        val snapshot: BoostV5StateSnapshotCodec.Snapshot,
        val source: Source,
        val needsResetForStaleEvidence: Boolean,
        val ageMinutes: Double?,
    )

    /** The caller decides when to make an immutable simulated cache update. */
    fun stageSave(
        memory: Memory,
        next: BoostV5StateSnapshotCodec.Snapshot,
    ): Memory? {
        // Use the source codec's strict validation before accepting a value.
        if (BoostV5StateSnapshotCodec.encode(next) == null) return null
        return memory.copy(current = next)
    }

    /**
     * Read the most recently staged snapshot first. A corrupt persisted
     * snapshot never silently turns into CONFIRMED or COMMITTED.
     *
     * Stale evidence is *reported*, not automatically replayed or applied.
     * The original engine, not V5StateStore, performs the stale-time reset.
     * Preserving the lock in the returned snapshot is crucial: a delivered
     * one-shot action must not be erased merely because the age clock expired.
     */
    fun inspect(
        memory: Memory,
        persistedFields: Map<String, String>?,
        nowMs: Long,
    ): Inspection? {
        if (nowMs < 0L) return null
        val cached = memory.current
        val snapshot: BoostV5StateSnapshotCodec.Snapshot
        val source: Source
        when {
            cached != null -> {
                if (BoostV5StateSnapshotCodec.encode(cached) == null) return null
                snapshot = cached
                source = Source.MEMORY
            }
            persistedFields == null || persistedFields.isEmpty() -> {
                snapshot = BoostV5StateSnapshotCodec.Snapshot()
                source = Source.DEFAULT_EMPTY
            }
            else -> {
                // A newer state schema may carry fields this codec cannot
                // represent. Never silently label a lossy restore as valid.
                val shape = BoostV5PersistedStateShapeAudit.inspect(persistedFields)
                val supported = shape.finding ==
                    BoostV5PersistedStateShapeAudit.Finding.CANONICAL ||
                    shape.finding == BoostV5PersistedStateShapeAudit.Finding.LEGACY_DEFAULTS
                val restored = if (supported)
                    BoostV5StateSnapshotCodec.decode(persistedFields) else null
                if (restored == null) {
                    snapshot = BoostV5StateSnapshotCodec.Snapshot()
                    source = Source.REJECTED_PERSISTED
                } else {
                    snapshot = restored
                    source = Source.PERSISTED
                }
            }
        }

        val state = snapshot.meal
        val age = if (nowMs > 0L && state.lastAgeMs > 0L) {
            BoostV5MealStateDiagnostics.staleStateMinutes(state, nowMs)
        } else null
        return Inspection(
            snapshot = snapshot,
            source = source,
            needsResetForStaleEvidence = age != null &&
                age > BoostV5MealStateDiagnostics.MAX_TIME_JUMP_MIN,
            ageMinutes = age,
        )
    }

    /**
     * A simulated process restart removes the in-memory preference cache.
     * The caller may only supply a previously encoded persisted snapshot.
     */
    fun restart(): Memory = Memory()
}
