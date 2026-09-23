pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tidefell-android"

include(":app")

// FFI development: -PtidefellFfiLocalPath=<monorepo checkout> builds the AAR from source instead
// of using the pinned release (CONVENTIONS.md, ADR 0014).
providers.gradleProperty("tidefellFfiLocalPath").orNull?.let { monorepo ->
    includeBuild("$monorepo/bindings/android") {
        dependencySubstitution {
            substitute(module("org.tidefell:tidefell-ffi")).using(project(":ffi"))
        }
    }
}
