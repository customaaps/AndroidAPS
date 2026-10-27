package app.aaps.plugins.boostport.telemetry

/**
 * Passive ordering model for the archived V5StateStore save/clear lifecycle.
 * The original stores the process cache before an asynchronous preference write
 * and clears both on reset. This produces an immutable event trace for offline
 * inspection only. No preference access, scheduling, APS or pump interaction.
 */
internal object BoostV5StateStoreSequenceAudit {
    enum class Operation { SAVE, CLEAR }
    enum class Event { CACHE_SET, PERSIST_REQUEST, CACHE_CLEAR, PERSIST_CLEAR_REQUEST }

    data class Trace(
        val operation: Operation,
        val events: List<Event>,
        val persistedFields: Map<String, String>?,
    )

    fun inspectSave(snapshot: BoostV5StateSnapshotCodec.Snapshot): Trace? {
        val fields = BoostV5StateSnapshotCodec.encode(snapshot) ?: return null
        return Trace(
            operation = Operation.SAVE,
            events = listOf(Event.CACHE_SET, Event.PERSIST_REQUEST),
            persistedFields = fields,
        )
    }

    fun inspectClear(): Trace = Trace(
        operation = Operation.CLEAR,
        events = listOf(Event.CACHE_CLEAR, Event.PERSIST_CLEAR_REQUEST),
        persistedFields = null,
    )
}
