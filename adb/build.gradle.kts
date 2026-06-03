plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(26)
}

dependencies {
    api(libs.kotlin.stdlib)
    api(libs.ddmlib)
    implementation(project(":log"))
}
