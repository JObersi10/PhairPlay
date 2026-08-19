// Root build file intentionally keeps task wiring minimal.
// Android/Gradle plugin tasks (including `build` and `lint`) are provided by included modules.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// Keep build output off the internal disk.
//
// The project moved to the MacBook but the toolchain did not, and the internal volume runs close to
// full -- app/build alone reaches ~1 GB. `android.buildDir` in gradle.properties is ignored by
// AGP 8; setting layout.buildDirectory is the mechanism that still works. Falls back to the normal
// in-tree location when the drive is not mounted, so a build without SABRENT still works rather
// than failing with a confusing path error.
val externalBuildRoot = file("/Volumes/SABRENT/phairplay-build")
if (externalBuildRoot.parentFile.exists()) {
    allprojects {
        layout.buildDirectory.set(File(externalBuildRoot, path.replace(':', '_').trim('_')))
    }
}

subprojects {
    configurations.configureEach {
        resolutionStrategy.force(
            "org.jetbrains.kotlin:kotlin-stdlib:1.9.23",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.23",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.23"
        )
    }
}
