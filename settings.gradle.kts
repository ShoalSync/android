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

// tidefell-ffi is built from a monorepo checkout: the pinned release's, which
// scripts/monorepo.sh puts in build/tidefell, or any other with -PtidefellFfiLocalPath=<path>
// (CONVENTIONS.md).
val monorepo = providers.gradleProperty("tidefellFfiLocalPath").orNull ?: "build/tidefell"
check(file("$monorepo/bindings/android").isDirectory) {
    "No monorepo checkout at $monorepo. Run scripts/monorepo.sh, or pass " +
        "-PtidefellFfiLocalPath=<path> to build against another checkout."
}
includeBuild("$monorepo/bindings/android") {
    dependencySubstitution {
        substitute(module("org.tidefell:tidefell-ffi")).using(project(":ffi"))
    }
}
