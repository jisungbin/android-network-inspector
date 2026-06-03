plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(26)
}

dependencies {
    // api: ui constructs InspectorMcpServer(store: AppStore) and renders McpServerStatus/McpLog.
    api(project(":core"))
    implementation(project(":engine"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mcp.kotlin.sdk)
    implementation(libs.ktor.server.cio)
}
