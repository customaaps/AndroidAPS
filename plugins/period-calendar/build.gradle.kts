plugins {
    id("kmp-test-defaults")
    kotlin("multiplatform")
    // NOT com.android.library. AGP 9 refuses that plugin together with the multiplatform plugin.
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.metro)
    id("kotlinx-serialization")
}

kotlin {
    android {
        namespace = "app.aaps.plugins.periodcalendar"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        androidResources { enable = true }
        withHostTest {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        compilerOptions { jvmTarget.set(Versions.jvmTarget) }

        lint {
            checkReleaseBuilds = false
            disable += "MissingTranslation"
            disable += "ExtraTranslation"
        }
    }

    iosArm64()
    iosSimulatorArm64()

    // Desktop (Windows/macOS/Linux). The implementation is Android-only for now; the other targets
    // are declared so the module has the same shape as the rest of the plugins.
    jvm()

    sourceSets {
        androidMain {
            dependencies {
                implementation(project(":core:data"))
                implementation(project(":core:interfaces"))
                implementation(project(":core:keys"))
                implementation(project(":core:objects"))
                implementation(project(":core:ui"))

                api(project.dependencies.platform(libs.androidx.compose.bom))
                api(libs.androidx.ui)
                api(libs.androidx.compose.material3)
                api(libs.androidx.compose.material.icons.extended)
                implementation(libs.androidx.compose.ui.tooling)
                implementation(libs.androidx.lifecycle.runtime.compose)

                implementation(libs.kotlinx.serialization.json)
                implementation(libs.androidx.core)
            }
        }

        getByName("androidHostTest") {
            dependencies {
                implementation(project(":shared:tests"))
                implementation(project(":shared:impl"))
                implementation(project(":implementation"))
                implementation(libs.org.junit.jupiter)
                implementation(libs.org.junit.jupiter.api)
                implementation(libs.org.mockito.junit.jupiter)
                implementation(libs.org.mockito.kotlin)
                implementation(libs.com.google.truth)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project.dependencies.platform(libs.androidx.compose.bom))
                implementation(libs.androidx.compose.ui.test.junit4)
                implementation(libs.androidx.compose.ui.test.manifest)
                implementation(libs.org.robolectric)
                implementation(libs.org.json.android)
                runtimeOnly(libs.org.junit.vintage.engine)
                runtimeOnly(libs.org.junit.platform.launcher)
            }
        }
    }
}
