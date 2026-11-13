package app.aaps.plugins.source.libre2

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Libre2ProtocolTest {
    @Test
    fun `generation boundaries match donor for every patch version byte`() {
        for (version in 0..255) {
            val expected = when (version) {
                in 0x30..0x38, in 0x70..0x73 -> Libre2Generation.FIRST
                in 0x39..0x3f, in 0x74..0x7f -> Libre2Generation.SECOND
                else -> null
            }
            assertEquals(expected, Libre2Generation.fromPatchInfo(byteArrayOf(0, 0, version.toByte(), 0, 0, 0)), "version=$version")
        }
        for (size in listOf(0, 1, 2, 3, 5, 7)) assertNull(Libre2Generation.fromPatchInfo(ByteArray(size)))
    }

    @Test
    fun `only a complete ordered packet leaves the assembler and input is copied`() {
        val assembler = Libre2PacketAssembler()
        val first = ByteArray(20) { it.toByte() }
        assertNull(assembler.accept(first, 0))
        first.fill(99)
        assertNull(assembler.accept(ByteArray(18) { (it + 20).toByte() }, 100))
        val packet = assembler.accept(ByteArray(8) { (it + 38).toByte() }, 200)
        assertArrayEquals(ByteArray(46) { it.toByte() }, packet)
        assertNull(assembler.accept(ByteArray(8), 300))
    }

    @Test
    fun `disconnect timeout clock rollback and malformed fragment discard partial packets`() {
        for (invalidate in listOf<(Libre2PacketAssembler) -> Unit>(
            { it.reset() },
            { it.accept(ByteArray(18), 5_101) },
            { it.accept(ByteArray(18), 99) },
            { it.accept(ByteArray(17), 101) },
        )) {
            val assembler = Libre2PacketAssembler()
            assembler.accept(ByteArray(20), 100)
            invalidate(assembler)
            assertNull(assembler.accept(ByteArray(8), 5_102))
        }
    }

    @Test
    fun `new packet replaces unfinished packet and following minute can be received`() {
        val assembler = Libre2PacketAssembler()
        assembler.accept(ByteArray(20) { 1 }, 0)
        assembler.accept(ByteArray(20) { 2 }, 1)
        assembler.accept(ByteArray(18) { 2 }, 2)
        assertArrayEquals(ByteArray(46) { 2 }, assembler.accept(ByteArray(8) { 2 }, 3))
        assembler.accept(ByteArray(20) { 3 }, 60_000)
        assembler.accept(ByteArray(18) { 3 }, 60_001)
        assertArrayEquals(ByteArray(46) { 3 }, assembler.accept(ByteArray(8) { 3 }, 60_002))
    }
}
