/*
 * Libre 2 protocol portions adapted from JugglucoNG bb5757761a:
 * SensorGlucoseData.hpp::getgeneration and Libre2GattCallback.java.
 * Original copyright (C) 2021 Jaap Korthals Altes.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.aaps.plugins.source.libre2

/** Generation is determined from NFC patch information, never from the sales region. */
internal enum class Libre2Generation {
    FIRST, SECOND;

    companion object {
        fun fromPatchInfo(patchInfo: ByteArray): Libre2Generation? {
            if (patchInfo.size != 6) return null
            val version = patchInfo[2].toInt() and 0xff
            return when (version ushr 4) {
                3 -> if ((version and 0x0f) < 9) FIRST else SECOND
                7 -> if ((version and 0x0f) < 4) FIRST else SECOND
                else -> null
            }
        }
    }
}

/**
 * Assembles encrypted CompositeRawData notifications; this does NOT decode glucose.
 * Both generations use 20 + 18 + 8 bytes, after generation-specific authentication.
 * Call [reset] whenever a GATT session ends. Supply Android elapsedRealtime(), not wall time.
 */
internal class Libre2PacketAssembler(private val timeoutMs: Long = 5_000L) {
    init { require(timeoutMs > 0) }

    private var packet = ByteArray(46)
    private var nextLength = 20
    private var startedAt = 0L

    @Synchronized
    fun reset() {
        packet.fill(0)
        nextLength = 20
        startedAt = 0L
    }

    @Synchronized
    fun accept(fragment: ByteArray, elapsedMs: Long): ByteArray? {
        if (elapsedMs < 0) {
            reset()
            return null
        }
        if (nextLength != 20 && (elapsedMs < startedAt || elapsedMs - startedAt > timeoutMs)) reset()
        // A new first fragment always starts a new packet.
        if (fragment.size == 20) {
            reset()
            fragment.copyInto(packet)
            startedAt = elapsedMs
            nextLength = 18
            return null
        }
        if (fragment.size != nextLength || nextLength == 20) {
            reset()
            return null
        }
        if (nextLength == 18) {
            fragment.copyInto(packet, 20)
            nextLength = 8
            return null
        }
        fragment.copyInto(packet, 38)
        val complete = packet.copyOf()
        reset()
        return complete
    }
}
