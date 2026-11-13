package app.aaps.plugins.source.libre2

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Libre2GattQueueTest {
    @Test
    fun `early notifications do not overlap authentication writes and descriptors`() {
        val queue = Libre2GattQueue()
        val sent = mutableListOf<String>()
        queue.enqueue("query") { sent += "query" }
        queue.enqueue("proof") { sent += "proof" }
        queue.enqueue("notifications") { sent += "notifications" }
        assertEquals(listOf("query"), sent)
        assertFalse(queue.complete("unrelated callback"))
        assertEquals(listOf("query"), sent)
        assertTrue(queue.complete("query"))
        assertEquals(listOf("query", "proof"), sent)
        assertTrue(queue.complete("proof"))
        assertEquals(listOf("query", "proof", "notifications"), sent)
        assertTrue(queue.complete("notifications"))
        assertFalse(queue.complete("notifications"))
    }

    @Test
    fun `disconnect drops queued operations and ignores late completions`() {
        val queue = Libre2GattQueue()
        val sent = mutableListOf<String>()
        queue.enqueue("query") { sent += "query" }
        queue.enqueue("proof") { sent += "proof" }
        queue.close()
        assertFalse(queue.complete("query"))
        queue.enqueue("late notification") { sent += "late notification" }
        assertEquals(listOf("query"), sent)
    }
}
