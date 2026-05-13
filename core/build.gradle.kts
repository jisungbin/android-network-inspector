plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)

    jvm()

    sourceSets {
        jvmMain.dependencies {
            api(libs.kotlin.stdlib)
            api(libs.coroutines.core)
            api(libs.ddmlib)
            api(libs.kotlinx.serialization.json)
            implementation(libs.slf4j.api)

            api(fileTree("../studio-bundle/lib") {
                include("*.jar")
            })
        }
    }
}
