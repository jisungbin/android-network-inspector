plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
}

val studioPath: String = providers.gradleProperty("android.studio.path").get()
val studioResources = "$studioPath/Contents/plugins/android/resources"
val studioLib = "$studioPath/Contents/plugins/android/lib"

tasks.register<Copy>("syncStudioBundle") {
    group = "studio"
    description = "Copy required jars and native binaries from Android Studio into studio-bundle/."

    val target = layout.projectDirectory.dir("studio-bundle")

    into(target)

    from(studioLib) {
        include("transport_java_proto.jar")
        include("network_inspector_java_proto.jar")
        include("studio-grpc.jar")
        include("studio-proto.jar")
        into("lib")
    }
    from(studioResources) {
        include("perfa.jar")
        include("app-inspection/network-inspector.jar")
        include("transport/**")
        into("device")
    }

    doFirst {
        require(file(studioPath).isDirectory) {
            "android.studio.path is not a valid Android Studio.app directory: $studioPath"
        }
    }

    doLast {
        logger.lifecycle("Studio bundle synced to: ${target.asFile}")
    }
}
