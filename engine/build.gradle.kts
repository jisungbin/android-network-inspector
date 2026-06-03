plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(26)
}

dependencies {
    api(libs.kotlin.stdlib)
    api(libs.coroutines.core)
    api(project(":adb"))
    api(project(":protocol"))
    api(project(":log"))
}
