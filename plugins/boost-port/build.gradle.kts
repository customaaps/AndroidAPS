plugins {
    id("kmp-test-defaults")
    kotlin("multiplatform")
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.metro)
}

val v1ReferenceDir = layout.buildDirectory.dir("generated/v1Reference")
val generateV1Reference = tasks.register<Exec>("generateV1ReplayReference") {
    inputs.file("tools/v1_replay_sources.py")
    inputs.file("tools/activity_sources.py")
    inputs.file("port-source/DetermineBasalBoost.kt.source")
    inputs.file("port-source/Round.kt.source")
    inputs.file("../../core/interfaces/port-source/DynamicCarbRatioCalculator.kt.source")
    inputs.file("src/commonMain/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostV1ReplayEngine.kt")
    inputs.dir("src/commonMain/kotlin/app/aaps/plugins/aps/openAPSBoost/replay")
    inputs.dir("src/commonMain/kotlin/app/aaps/plugins/aps/openAPSBoost/activity")
    inputs.file("../../core/interfaces/src/commonMain/kotlin/app/aaps/core/interfaces/aps/DynamicCarbRatioCalculator.kt")
    inputs.dir("legacy-snapshot/plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost")
    inputs.file("legacy-snapshot/plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostV5/OpenAPSBoostV5Plugin.kt.source")
    inputs.file("legacy-snapshot/plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostV5/ConfirmTrancheController.kt.source")
    inputs.file("legacy-snapshot/plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostV5/V5StateStore.kt.source")
    outputs.dir(v1ReferenceDir)
    commandLine("python3", "tools/v1_replay_sources.py", "--reference-output", v1ReferenceDir.get().asFile.absolutePath)
}


kotlin {
    android {
        namespace = "app.aaps.plugins.boostport"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        compilerOptions { jvmTarget.set(Versions.jvmTarget) }
        lint { checkReleaseBuilds = false }
    }
    iosArm64()
    iosSimulatorArm64()
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":core:data"))
                implementation(project(":core:interfaces"))
                implementation(project(":core:keys"))
                implementation(libs.kotlinx.serialization.json)
            }
        }
        androidMain {
            dependencies {
                // Android-only; never enters the KMP common/iOS/JVM variants.
                implementation(libs.androidx.health.connect.client)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        getByName("jvmTest") {
            kotlin.srcDir(v1ReferenceDir)
            resources.srcDir("legacy-snapshot/app/src/main/assets")
            dependencies {
                // Original source JSON codec is compiled only into the reference test oracle.
                implementation(libs.org.json.android)
                implementation(libs.com.google.truth)
            }
        }
    }
}

tasks.matching { it.name == "compileTestKotlinJvm" }.configureEach {
    dependsOn(generateV1Reference)
}
