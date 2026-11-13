package app.aaps.plugins.source.libre2

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Owned by the decoder process only. Persist before acknowledging pairing/unlock attempts. */
internal class Libre2PairingStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "libre2/pairing.bin"))

    fun load(): Libre2Pairing? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val bytes = file.readFully()
        require(bytes.size <= MAX_STATE + 128)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 0x4c324e31) { "Unsupported Libre2 pairing version" }
            val uid = ByteArray(8).also(input::readFully)
            val info = ByteArray(6).also(input::readFully)
            val address = input.readUTF()
            require(address.matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")))
            val authLength = input.readInt()
            require(authLength in listOf(0, 10, 12))
            val auth = ByteArray(authLength).also(input::readFully)
            val enabledAt = input.readLong()
            val gen2 = input.readBoolean()
            val counter = input.readInt()
            val lastSampleId = input.readInt()
            val lastReading = input.readLong()
            require(enabledAt > 0 && counter >= 0 && lastSampleId >= -1 && lastReading >= 0)
            require(!gen2 || authLength >= 10)
            val stateLength = input.readInt()
            require(stateLength in 0..MAX_STATE && stateLength <= input.available())
            val state = Libre2DecoderState.decode(ByteArray(stateLength).also(input::readFully))
            require(input.available() == 0)
            Libre2Pairing(uid, info, address, auth, enabledAt, gen2, state, counter, lastSampleId, lastReading)
        }
    }

    fun save(pairing: Libre2Pairing) {
        require(pairing.uid.size == 8 && pairing.patchInfo.size == 6)
        require(pairing.address.matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")))
        require(pairing.auth.size in listOf(0, 10, 12))
        require(!pairing.generation2 || pairing.auth.size >= 10)
        require(pairing.unlockCounter >= 0 && pairing.enabledAtSeconds > 0)
        require(pairing.lastSampleId >= -1 && pairing.lastReadingMillis >= 0)
        val state = pairing.state.encode()
        require(state.size <= MAX_STATE)
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(0x4c324e31)
                output.write(pairing.uid)
                output.write(pairing.patchInfo)
                output.writeUTF(pairing.address)
                output.writeInt(pairing.auth.size)
                output.write(pairing.auth)
                output.writeLong(pairing.enabledAtSeconds)
                output.writeBoolean(pairing.generation2)
                output.writeInt(pairing.unlockCounter)
                output.writeInt(pairing.lastSampleId)
                output.writeLong(pairing.lastReadingMillis)
                output.writeInt(state.size)
                output.write(state)
            }
        }.toByteArray()
        file.baseFile.parentFile?.mkdirs()
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    companion object { private const val MAX_STATE = 3 * 1024 * 1024 + 56 }
}
