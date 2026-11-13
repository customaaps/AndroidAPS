package app.aaps.plugins.source.libre2

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Fsync before atomic replacement; unlike AtomicFile.finishWrite, commit failures are propagated. */
internal class Libre2AtomicFile(private val base: File) {
    private val legacy = AtomicFile(base)
    private val backup get() = File(base.path + ".bak")
    fun exists() = base.exists() || backup.exists()

    fun read(maxBytes: Int): ByteArray = legacy.openRead().use { input ->
        if (input.channel.size() > maxBytes) throw IOException("Oversized Libre2 state file")
        input.readBytes()
    }

    fun write(bytes: ByteArray) {
        // Recover a backup left by the previous AtomicFile format before replacing it.
        if (backup.exists()) legacy.openRead().use { }
        val parent = base.parentFile ?: throw IOException("Libre2 state directory is missing")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create Libre2 state directory")
        val pending = File(base.path + ".pending")
        try {
            FileOutputStream(pending).use { output -> output.write(bytes); output.fd.sync() }
            // Same directory/filesystem. Do not fall back to a non-atomic copy or delete the original.
            Files.move(pending.toPath(), base.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (error: Exception) {
            pending.delete()
            throw error
        }
    }
}
