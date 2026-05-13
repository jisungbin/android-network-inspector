rootProject.name = "network-inspector-mac"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
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
include(":cli")
include(":ui")
