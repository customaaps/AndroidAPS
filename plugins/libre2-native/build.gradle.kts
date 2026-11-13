import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.library)
}

abstract class PrepareLibre2Vendor : DefaultTask() {
    @get:OutputDirectory abstract val destination: DirectoryProperty
    @get:LocalState abstract val archive: RegularFileProperty
    @get:InputFile @get:Optional abstract val suppliedApk: RegularFileProperty
    @get:Input val sourceUrl = "https://github.com/ctqvva/JugglucoNG/releases/download/1.2.3-Alpha/JugglucoNG-1.2.3-Alpha.apk"
    @get:Input val archiveSha256 = "6374954ca6c9c7681434343220437437a8378748c303975af92c46c7d74b74b1"
    @get:Input val librarySha256 = mapOf(
        "lib/arm64-v8a/libcalibrat2.so" to "c15a1efa60a7d2b17ac6cab8e3efffbcb5d6d4e5c8085e68d5812c3d4bf49a93",
        "lib/arm64-v8a/libinit.so" to "0d1b9696af9c52781df8f7d7ec16584e74ac3aaf41abcd337b6a606eef2c07d1",
        "lib/armeabi-v7a/libcalibrat2.so" to "6aefcef0d7fc995ace8f1b2c4e3809c8ce349e013ad0a53fbab97c02d16d1f6a",
        "lib/armeabi-v7a/libinit.so" to "0d1b9696af9c52781df8f7d7ec16584e74ac3aaf41abcd337b6a606eef2c07d1"
    )

    private fun digest(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @TaskAction
    fun restore() {
        val apk = archive.get().asFile
        apk.parentFile.mkdirs()
        suppliedApk.orNull?.asFile?.let { local ->
            if (digest(local.readBytes()) != archiveSha256) throw GradleException("Supplied Libre2 APK checksum mismatch")
            local.copyTo(apk, overwrite = true)
        }
        if (!apk.isFile || digest(apk.readBytes()) != archiveSha256) {
            val temporary = File(apk.parentFile, apk.name + ".download")
            try {
                val connection = URI(sourceUrl).toURL().openConnection().apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                }
                connection.getInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                if (digest(temporary.readBytes()) != archiveSha256)
                    throw GradleException("Libre2 source APK checksum mismatch")
                temporary.copyTo(apk, overwrite = true)
            } finally { temporary.delete() }
        }
        ZipFile(apk).use { zip ->
            for ((path, expectedHash) in librarySha256) {
                val entry = zip.getEntry(path) ?: throw GradleException("Libre2 vendor library missing: $path")
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                if (digest(bytes) != expectedHash) throw GradleException("Libre2 vendor library checksum mismatch: $path")
                val target = destination.get().file(path.removePrefix("lib/")).asFile
                target.parentFile.mkdirs()
                target.writeBytes(bytes)
            }
        }
    }
}

val prepareLibre2Vendor = tasks.register<PrepareLibre2Vendor>("prepareLibre2Vendor") {
    destination.set(layout.buildDirectory.dir("generated/vendorJniLibs"))
    archive.set(layout.buildDirectory.file("vendorCache/juggluco-source.apk"))
    providers.gradleProperty("libre2VendorApk").orNull?.let { suppliedApk.fileValue(file(it)) }
}

android {
    namespace = "app.aaps.plugins.libre2native"
    compileSdk = Versions.compileSdk
    ndkVersion = "29.0.14206865"
    defaultConfig {
        minSdk = Versions.minSdk
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
                targets += listOf("aaps_libre2_adapter", "aaps_libre2_compat")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("../source/src/androidMain/cpp/libre2/CMakeLists.txt")
            version = "4.1.2"
        }
    }
    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += setOf("**/libcalibrat2.so", "**/libinit.so")
    }
}
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareLibre2Vendor, PrepareLibre2Vendor::destination)
    }
}
