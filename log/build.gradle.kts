plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(26)
}

dependencies {
    implementation(libs.kotlin.stdlib)
}
