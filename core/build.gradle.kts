plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(26)
}

dependencies {
    // engine re-exports adb/protocol/log via api, so consumers of core also see
    // NetworkRow, AttachMode, RuleSender, the Studio proto/grpc types, etc.
    api(project(":engine"))
    api(libs.kotlin.stdlib)
    api(libs.coroutines.core)
    api(libs.kotlinx.serialization.json)
}
