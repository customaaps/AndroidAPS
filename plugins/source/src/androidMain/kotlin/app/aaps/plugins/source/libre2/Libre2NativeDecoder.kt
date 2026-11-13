package app.aaps.plugins.source.libre2

import androidx.annotation.Keep

/** Native entry points. Only initialize in the dedicated sensor process. */
@Keep
internal object Libre2NativeDecoder {
    init { System.loadLibrary("aaps_libre2_adapter") }

    @JvmStatic external fun probe(): Boolean
    @JvmStatic external fun load(absoluteVendorLibraryPath: String)
    @JvmStatic external fun v1(code: Int, handle: Int, first: ByteArray?, second: ByteArray?): Int
    @JvmStatic external fun v2(code: Int, handle: Int, first: ByteArray?, second: ByteArray?): ByteArray?
    @JvmStatic external fun activationPayload(uid: ByteArray, patchInfo: ByteArray): ByteArray
    @JvmStatic external fun streamingPayload(uid: ByteArray, patchInfo: ByteArray, unixSeconds: Long): ByteArray
    @JvmStatic external fun unlockPayload(uid: ByteArray, patchInfo: ByteArray, person: Byte, enabledAtSeconds: Long, counter: Int): ByteArray
    @JvmStatic external fun decodeNfc(
        uid: ByteArray, patchInfo: ByteArray, fram: ByteArray, unixSeconds: Long,
        metadata: LongArray, arrays: Array<ByteArray>,
    ): Array<Any>
    @JvmStatic external fun decodeBluetooth(
        uid: ByteArray, patchInfo: ByteArray, decryptedPacket: ByteArray, unixSeconds: Long,
        metadata: LongArray, arrays: Array<ByteArray>,
    ): Array<Any>
}
