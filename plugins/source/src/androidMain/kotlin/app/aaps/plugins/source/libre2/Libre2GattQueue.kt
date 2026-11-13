package app.aaps.plugins.source.libre2

import java.util.ArrayDeque

/** GATT accepts one write/descriptor operation at a time, even if a notification arrives early. */
internal class Libre2GattQueue {
    private val pending = ArrayDeque<Pair<Any, () -> Unit>>()
    private var current: Any? = null
    private var closed = false

    fun enqueue(key: Any, operation: () -> Unit) {
        if (closed) return
        pending.addLast(key to operation)
        advance()
    }

    fun complete(key: Any): Boolean {
        if (closed || current != key) return false
        current = null
        advance()
        return true
    }

    private fun advance() {
        if (closed || current != null || pending.isEmpty()) return
        val (key, operation) = pending.removeFirst()
        current = key
        operation()
    }

    fun close() {
        closed = true
        current = null
        pending.clear()
    }
}
