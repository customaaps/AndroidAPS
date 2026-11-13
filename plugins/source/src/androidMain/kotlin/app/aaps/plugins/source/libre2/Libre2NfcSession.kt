/*
 * NFC patch-info command adapted from JugglucoNG bb5757761a, AlgNfcV.java.
 * Original copyright (C) 2021 Jaap Korthals Altes.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.aaps.plugins.source.libre2

import android.nfc.Tag
import android.nfc.tech.NfcV
import android.os.Looper
import java.io.Closeable
import java.io.IOException

/** A single connected ISO15693 session. No automatic activation or repeated writes. */
internal class Libre2NfcSession private constructor(
    private val connection: NfcV,
) : Closeable {
    fun readPatchInfo(): ByteArray =
        Libre2NfcResponse.patchInfo(connection.transceive(byteArrayOf(0x02, 0xa1.toByte(), 0x07)))

    override fun close() = connection.close()

    companion object {
        /** Call on an IO dispatcher. Always use use{} to close, including on authentication failure. */
        fun open(tag: Tag): Libre2NfcSession {
            check(Looper.myLooper() != Looper.getMainLooper()) { "NFC IO must run off the main thread" }
            val connection = NfcV.get(tag) ?: throw IOException("Tag does not support ISO15693")
            if (tag.id.size != 8) throw IOException("Invalid ISO15693 UID length")
            try {
                connection.connect()
                return Libre2NfcSession(connection)
            } catch (error: Exception) {
                runCatching { connection.close() }
                throw error
            }
        }
    }
}

internal object Libre2NfcResponse {
    fun patchInfo(response: ByteArray): ByteArray {
        if (response.isEmpty()) throw IOException("Empty NFC response")
        if ((response[0].toInt() and 1) != 0) throw IOException("Sensor rejected NFC command")
        if (response.size != 7) throw IOException("Invalid NFC patch-info length")
        return response.copyOfRange(1, 7)
    }
}

/**
 * Regional Libre 2 patch-info may not match the known generation markers.
 * Preserve AlgNfcV.enableStreaming's fallback instead of marking such a sensor NFC-only.
 * Each attempt must own/close its native authentication context before returning false.
 */
internal object Libre2Bootstrap {
    fun enable(patchInfo: ByteArray, firstGeneration: () -> Boolean, secondGeneration: () -> Boolean): Boolean {
        require(patchInfo.size == 6) { "Invalid patch-info length" }
        return when (Libre2Generation.fromPatchInfo(patchInfo)) {
            Libre2Generation.FIRST -> firstGeneration()
            Libre2Generation.SECOND -> secondGeneration()
            null -> firstGeneration() || secondGeneration()
        }
    }
}
