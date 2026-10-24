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

rootProject.name = "shoal-android"

include(":app")

// shoal-ffi is built from a monorepo checkout: the pinned release's, which
// scripts/monorepo.sh puts in build/shoal, or any other with -PshoalFfiLocalPath=<path>
// (CONVENTIONS.md).
val monorepo = providers.gradleProperty("shoalFfiLocalPath").orNull ?: "build/shoal"
check(file("$monorepo/bindings/android").isDirectory) {
    "No monorepo checkout at $monorepo. Run scripts/monorepo.sh, or pass " +
        "-PshoalFfiLocalPath=<path> to build against another checkout."
}
includeBuild("$monorepo/bindings/android") {
    dependencySubstitution {
        substitute(module("org.shoalsync:shoal-ffi")).using(project(":ffi"))
    }
}
