plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

val ffiVersion: String = libs.versions.shoal.get()

android {
    namespace = "org.shoalsync.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.shoalsync.android"
        minSdk = 30
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

    // Android Lint fails on any new warning; existing ones are in lint-baseline.xml (ADR 0031).
    // The checks for newer releases are off: they ask the network, so a release upstream would
    // fail a build that changed nothing.
    lint {
        baseline = file("lint-baseline.xml")
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
        warningsAsErrors = true
        abortOnError = true
    }

    buildFeatures {
        compose = true
    }

    // The design tokens' Kotlin and resources, built from design/tokens (design/README.md).
    sourceSets.getByName("main") {
        kotlin.directories += "../design/dist/android/kotlin"
        res.directories += "../design/dist/android/res"
    }

    // Screenshot tests render every screen on the JVM with Robolectric (CONVENTIONS.md).
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        // The emulator test ABI is enough while developing: -Pshoal.abis=x86_64 on the AAR.
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    // Built from the monorepo checkout that settings.gradle.kts includes.
    implementation(libs.shoal.ffi)
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
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// Reference screenshots live beside the tests and are compared on every CI run:
// `./gradlew :app:recordRoborazziDebug` writes them after a deliberate change to a screen.
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}
