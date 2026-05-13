plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.kotlin.stdlib)
    api(libs.ddmlib)
    implementation(project(":log"))
}
