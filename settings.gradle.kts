pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

// Lets Gradle fetch a JDK 17 when the machine doesn't have one. `:test-runner` pins
// jvmToolchain(17), and without this, merely *configuring* that module fails on a machine whose
// only JDK is Android Studio's bundled 21 — which broke `:app:assemble*` too, since configuration
// is not lazy. That is why the module used to be commented out of this file entirely.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PhairPlay"
include(":app")
// The JVM protocol-test module. Included so CI can run `:test-runner:test`; it is a plain
// kotlin-jvm module, so `:app:assemble*` never builds it and device builds are unaffected.
include(":test-runner")
project(":test-runner").projectDir = file("test-runner")
