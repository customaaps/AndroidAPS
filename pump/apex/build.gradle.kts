plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.metro)
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
}

android { namespace = "app.aaps.pump.apex" }

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:objects"))
    implementation(project(":core:utils"))
    implementation(project(":core:ui"))
    implementation(project(":pump:common"))
    implementation(libs.joda.time)
    implementation(libs.io.reactivex.rxjava3.rxkotlin)
    implementation(libs.io.reactivex.rxjava3.rxandroid)
    implementation(libs.kotlinx.coroutines.rx3)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(project(":shared:tests"))
}
