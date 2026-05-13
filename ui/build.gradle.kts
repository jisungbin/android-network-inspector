import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.foundation)
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    runtimeOnly(libs.logback.classic)
}

compose.desktop {
    application {
        mainClass = "com.jisungbin.networkinspector.ui.MainKt"
        jvmArgs += listOf(
            "-Dnetwork.inspector.studio.bundle=" +
                rootProject.layout.projectDirectory.dir("studio-bundle/device").asFile.absolutePath
        )
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "Network Inspector"
            packageVersion = "1.0.0"
        }
    }
}
