plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.jisungbin.networkinspector.cli.MainKt")
    applicationDefaultJvmArgs = listOf(
        "-Dnetwork.inspector.studio.bundle=" +
            rootProject.layout.projectDirectory.dir("studio-bundle/device").asFile.absolutePath
    )
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    runtimeOnly(libs.logback.classic)
}
