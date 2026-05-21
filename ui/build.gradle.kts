import kotlinx.benchmark.gradle.JvmBenchmarkTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlinx.benchmark)
}

kotlin {
    jvmToolchain(21)
}

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

sourceSets {
    create("benchmarks") {
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

val benchmarksImplementation: Configuration by configurations.getting {
    extendsFrom(configurations["implementation"])
}
configurations["benchmarksRuntimeOnly"].extendsFrom(configurations["runtimeOnly"])

benchmark {
    configurations {
        named("main") {
            warmups = 3
            iterations = 5
            iterationTime = 2
            iterationTimeUnit = "s"
            mode = "AverageTime"
            outputTimeUnit = "ms"
        }
    }
    targets {
        register("benchmarks") {
            this as JvmBenchmarkTarget
            jmhVersion = "1.37"
        }
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.foundation)
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    runtimeOnly(libs.logback.classic)

    benchmarksImplementation(libs.kotlinx.benchmark.runtime)
    benchmarksImplementation(libs.kotlinx.serialization.json)
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
