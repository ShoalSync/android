plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val ffiVersion: String = libs.versions.tidefell.get()
val localFfi = providers.gradleProperty("tidefellFfiLocalPath").isPresent
val pinnedAar = file("libs/tidefell-ffi-$ffiVersion.aar")

android {
    namespace = "org.tidefell.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.tidefell.android"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = ffiVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // The emulator test ABI is enough while developing: -Ptidefell.abis=x86_64 on the AAR.
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    if (localFfi) {
        implementation("org.tidefell:tidefell-ffi")
    } else {
        implementation(files(pinnedAar))
    }
    // The AAR has no POM; these are its own dependencies (bindings/android/README.md).
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.zxing.core)
    implementation(libs.work.runtime)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

val checkFfi by tasks.registering {
    description = "Fails early, with instructions, if the pinned AAR has not been fetched."
    onlyIf { !localFfi }
    doLast {
        check(pinnedAar.exists()) {
            "Missing ${pinnedAar.path}. Run scripts/fetch-ffi.sh, or build against a monorepo " +
                "checkout with -PtidefellFfiLocalPath=<path>."
        }
    }
}
tasks.named("preBuild") { dependsOn(checkFfi) }
