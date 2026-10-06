pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Lets Gradle fetch the exact JDK the toolchain asks for.
//
// Without this the build depends on whichever JDK happens to be on PATH, and
// AGP's system-modules transform is picky: a JDK that is too new, or a "nomod"
// runtime with no jlink, fails in a way whose error message points nowhere
// useful. Pinning the toolchain makes the build behave the same on any machine.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "WolfPC"
include(":app")
