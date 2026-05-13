plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.kotlin.stdlib)
    api(libs.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(fileTree("../studio-bundle/lib") { include("*.jar") })
    implementation(project(":log"))
    implementation(project(":adb"))
}
