/*
 * NFC command sequences adapted from JugglucoNG bb5757761a, AlgNfcV.java and Gen2.java.
 * Original copyright (C) 2021 Jaap Korthals Altes.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.aaps.plugins.source.libre2

import android.nfc.Tag
import android.nfc.tech.NfcV
import java.io.IOException

internal data class Libre2Pairing(
    val uid: ByteArray,
    val patchInfo: ByteArray,
    val address: String,
    val auth: ByteArray,
    val enabledAtSeconds: Long,
    val generation2: Boolean,
    val state: Libre2DecoderState,
    val unlockCounter: Int = 0,
    val lastSampleId: Int = -1,
    val lastReadingMillis: Long = 0,
)

internal class Libre2NfcReader(private val exchange: (ByteArray) -> ByteArray) {
    private fun command(bytes: ByteArray, minimum: Int = 0): ByteArray {
        val response = exchange(bytes)
        if (response.isEmpty() || response[0].toInt() and 1 != 0 || response.size - 1 < minimum)
            throw IOException("Libre2 rejected NFC command or returned a short response")
        return response.copyOfRange(1, response.size)
    }

    private fun finish(handle: Int) {
        if (handle > 0) runCatching { Libre2NativeDecoder.v1(37400, handle, null, null) }
    }

    private fun scanSession(uid: ByteArray, operation: Int): Pair<Int, ByteArray> {
        val extra = command(byteArrayOf(2, 0xa1.toByte(), uid[6], 34), 4)
        val nonce = (extra[2].toInt() and 255) or ((extra[3].toInt() and 255) shl 8)
        val challenge = command(byteArrayOf(2, 0xa1.toByte(), uid[6], 32))
        fun make(n: Int): Pair<Int, ByteArray>? {
            val handle = Libre2NativeDecoder.v1(28960, n, uid, null)
            if (handle < 0) return null
            try {
                val payload = Libre2NativeDecoder.v2(6440, handle, byteArrayOf(0, operation.toByte()), challenge)
                    ?: throw IOException("Libre2 NFC authorization returned no payload")
                require(payload.size <= 19) { "Invalid Libre2 NFC authentication payload" }
                val request = payload.copyOf(19)
                byteArrayOf(2, 0xa1.toByte(), 7, operation.toByte()).copyInto(request)
                return handle to request
            } catch (error: Exception) { finish(handle); throw error }
        }
        return make(nonce) ?: if (nonce != 0) make(0)
            ?: throw IOException("Libre2 NFC authorization failed") else throw IOException("Libre2 NFC authorization failed")
    }

    private fun gen2Operation(uid: ByteArray, operation: Int, outputLength: Int): ByteArray {
        val (handle, request) = scanSession(uid, operation)
        try {
            val response = command(request)
            return Libre2NativeDecoder.v2(22321, handle, byteArrayOf(0, outputLength.toByte()), response)
                ?.also { require(it.size == outputLength) } ?: throw IOException("Libre2 NFC operation failed")
        } finally { finish(handle) }
    }

    private fun gen2Read(uid: ByteArray): ByteArray {
        val (handle, request) = scanSession(uid, 0x1f)
        try {
            val response = command(request)
            if (Libre2NativeDecoder.v1(29465, handle, byteArrayOf(0), response) < 0)
                throw IOException("Libre2 NFC read authentication failed")
            val blocks = 43
            val encoded = ByteArray(blocks * 8)
            var block = 0
            while (block < blocks) {
                val count = minOf(3, blocks - block)
                val data = command(byteArrayOf(2, 0xa1.toByte(), 7, 33, block.toByte(), (count - 1).toByte()), (count + 1) * 8)
                data.copyInto(encoded, block * 8, 8, 8 + count * 8)
                block += count
            }
            return Libre2NativeDecoder.v2(6520, handle, byteArrayOf(0, blocks.toByte()), encoded)
                ?.also { require(it.size == 344) } ?: throw IOException("Libre2 FRAM decryption failed")
        } finally { finish(handle) }
    }

    private fun gen1Read(): ByteArray {
        val frame = ByteArray(344)
        var block = 0
        while (block < 43) {
            val count = minOf(3, 43 - block)
            val data = command(byteArrayOf(2, 35, block.toByte(), (count - 1).toByte()), count * 8)
            data.copyInto(frame, block * 8, 0, count * 8)
            block += count
        }
        return frame
    }

    private fun gen2Enable(uid: ByteArray): Pair<String, ByteArray> {
        val (handle, request) = scanSession(uid, 0x1e)
        try {
            val response = command(request)
            val addressAndTail = Libre2NativeDecoder.v2(22321, handle, byteArrayOf(0, 9), response)
                ?: throw IOException("Libre2 pairing failed")
            require(addressAndTail.size == 9)
            val auth = Libre2NativeDecoder.v2(6145, handle, byteArrayOf(9), null)
                ?: throw IOException("Libre2 pairing returned no authentication data")
            require(auth.size == 9)
            val combined = auth.copyOfRange(0, auth.size - 2) +
                addressAndTail.copyOfRange(6, 9) + auth.copyOfRange(auth.size - 2, auth.size)
            require(combined.size == 12)
            return mac(addressAndTail.copyOfRange(0, 6)) to combined
        } finally { finish(handle) }
    }

    fun read(uid: ByteArray, activate: Boolean, nowSeconds: Long, previous: Libre2Pairing?): Pair<Libre2Pairing, Libre2DecodeResult> {
        require(uid.size == 8 && uid[6].toInt() and 255 == 7 && uid[7].toInt() and 255 == 0xe0) { "Not an Abbott ISO15693 sensor" }
        val info = command(byteArrayOf(2, 0xa1.toByte(), 7), 6).also { require(it.size == 6) }
        val generation = Libre2Generation.fromPatchInfo(info)
        if (activate) {
            if (generation == Libre2Generation.SECOND) gen2Operation(uid, 27, 5)
            else command(Libre2NativeDecoder.activationPayload(uid, info))
        }
        val frame = if (generation == Libre2Generation.SECOND) gen2Read(uid) else gen1Read()
        val old = previous?.takeIf { it.uid.contentEquals(uid) && it.patchInfo.contentEquals(info) }
        val state = old?.state ?: Libre2DecoderState()
        val decoded = Libre2DecodeResult.fromNative(Libre2NativeDecoder.decodeNfc(uid, info, frame, nowSeconds, state.metadata(), state.arrays))
        if (decoded.status != 0) throw IOException("Libre2 NFC decoder status: ${decoded.status}")
        if (decoded.removed) throw IOException("Libre2 sensor has ended or was removed")
        // A routine scan must not replace an established streaming key or reset its unlock counter.
        if (old != null && !activate) return old.copy(state = decoded.state) to decoded
        val pairing: Pair<String, ByteArray>
        var gen2 = generation == Libre2Generation.SECOND
        if (gen2) pairing = gen2Enable(uid)
        else {
            pairing = try {
                val payload = Libre2NativeDecoder.streamingPayload(uid, info, nowSeconds)
                val address = command(byteArrayOf(2, 0xa1.toByte(), 7) + payload, 6)
                require(address.size == 6)
                mac(address) to byteArrayOf()
            } catch (error: Exception) {
                if (generation != null) throw error
                gen2 = true
                gen2Enable(uid)
            }
        }
        return Libre2Pairing(uid.copyOf(), info, pairing.first, pairing.second, nowSeconds, gen2, decoded.state) to decoded
    }

    companion object {
        internal fun mac(bytes: ByteArray): String {
            require(bytes.size == 6)
            return bytes.reversed().joinToString(":") { "%02X".format(it.toInt() and 255) }
        }
        fun read(tag: Tag, activate: Boolean, previous: Libre2Pairing?): Pair<Libre2Pairing, Libre2DecodeResult> {
            val nfc = NfcV.get(tag) ?: throw IOException("Sensor does not support NFC-V")
            try {
                nfc.connect()
                return Libre2NfcReader(nfc::transceive).read(tag.id, activate, System.currentTimeMillis() / 1000, previous)
            } finally { runCatching { nfc.close() } }
        }
    }
}
