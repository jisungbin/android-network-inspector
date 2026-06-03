rootProject.name = "network-inspector-mac"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        google()
    }
}

include(":log")
include(":adb")
include(":protocol")
include(":engine")
include(":core")
include(":mcp")
include(":cli")
include(":ui")
