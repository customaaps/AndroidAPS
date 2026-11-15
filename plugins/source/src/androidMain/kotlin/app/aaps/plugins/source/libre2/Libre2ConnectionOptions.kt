package app.aaps.plugins.source.libre2

import android.content.Context
import android.os.Bundle
import java.io.IOException

internal data class Libre2ConnectionOptions(
    val autoRecovery: Boolean = true,
    val keepAwake: Boolean = false,
) {
    fun toBundle() = Bundle().apply {
        putBoolean("autoRecovery", autoRecovery)
        putBoolean("keepAwake", keepAwake)
    }

    companion object {
        fun fromBundle(data: Bundle) = Libre2ConnectionOptions(
            data.getBoolean("autoRecovery", true), data.getBoolean("keepAwake", false))
    }
}

/** Main process only. The decoder receives settings over its authenticated Messenger session. */
internal class Libre2ConnectionSettings(context: Context) {
    private val preferences = context.getSharedPreferences("libre2_connection", Context.MODE_PRIVATE)
    fun load() = Libre2ConnectionOptions(preferences.getBoolean("autoRecovery", true), preferences.getBoolean("keepAwake", false))
    fun save(options: Libre2ConnectionOptions) {
        if (!preferences.edit().putBoolean("autoRecovery", options.autoRecovery).putBoolean("keepAwake", options.keepAwake).commit())
            throw IOException("Cannot save Libre2 connection settings")
    }
}

internal data class Libre2Diagnostics(
    val lastFragment: Long = 0,
    val lastPacket: Long = 0,
    val lastDecoded: Long = 0,
    val fragments: Long = 0,
    val packets: Long = 0,
    val decoded: Long = 0,
    val decoderStatus: Int = Int.MIN_VALUE,
    val quality: Int = Int.MIN_VALUE,
    val sampleId: Int = -1,
    val reconnects: Int = 0,
    val lastReconnect: Long = 0,
    val reconnectReason: String = "",
    val wakeProtectionHeld: Boolean = false,
) {
    fun writeTo(data: Bundle) {
        data.putLong("lastFragment", lastFragment)
        data.putLong("lastPacket", lastPacket)
        data.putLong("lastDecoded", lastDecoded)
        data.putLong("fragments", fragments)
        data.putLong("packets", packets)
        data.putLong("decoded", decoded)
        data.putInt("decoderStatus", decoderStatus)
        data.putInt("quality", quality)
        data.putInt("sampleId", sampleId)
        data.putInt("reconnects", reconnects)
        data.putLong("lastReconnect", lastReconnect)
        data.putString("reconnectReason", reconnectReason)
        data.putBoolean("wakeProtectionHeld", wakeProtectionHeld)
    }

    companion object {
        fun fromBundle(data: Bundle) = Libre2Diagnostics(
            data.getLong("lastFragment"), data.getLong("lastPacket"), data.getLong("lastDecoded"),
            data.getLong("fragments"), data.getLong("packets"), data.getLong("decoded"),
            data.getInt("decoderStatus", Int.MIN_VALUE), data.getInt("quality", Int.MIN_VALUE),
            data.getInt("sampleId", -1), data.getInt("reconnects"), data.getLong("lastReconnect"),
            data.getString("reconnectReason").orEmpty(), data.getBoolean("wakeProtectionHeld"))
    }
}
