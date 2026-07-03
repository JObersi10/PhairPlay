pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
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
// test-runner excluded from device builds (requires Java 17 toolchain)
// include(":test-runner")
// project(":test-runner").projectDir = file("test-runner")
