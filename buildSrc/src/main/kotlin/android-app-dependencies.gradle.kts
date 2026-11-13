plugins {
    id("com.android.application")
}

android {
    compileSdk = Versions.compileSdk
    defaultConfig {
        multiDexEnabled = true
        versionCode = Versions.versionCode
        version = Versions.appVersion

        //testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        // The vendor is opened by absolute nativeLibraryDir path, including its helper image.
        jniLibs.useLegacyPackaging = true
        // The pinned Libre2 vendor image must retain its original bytes.
        jniLibs.keepDebugSymbols += setOf("**/libcalibrat2.so", "**/libinit.so")
    }

    buildTypes {
        named("release") {
            isMinifyEnabled = false
            setProguardFiles(listOf(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"))
        }
        named("debug") {
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
    }

    compileOptions {
        sourceCompatibility = Versions.javaVersion
        targetCompatibility = Versions.javaVersion
    }

    lint {
        checkReleaseBuilds = false
        disable += "MissingTranslation"
        disable += "ExtraTranslation"
    }
}