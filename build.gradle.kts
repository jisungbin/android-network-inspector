import groovy.json.JsonSlurper
import java.io.File
import java.net.URI
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// Always tracks the current *stable* Android Studio: resolves it from the release feed, downloads
// the Linux tar.gz, and extracts only the inspector bundle into studio-bundle/. Any locally
// installed Studio is intentionally ignored (it may be an older version).
//
// Two payloads (see README / engine):
//   lib/    – proto + gRPC jars, consumed on the host as a compile classpath (protocol module)
//   device/ – perfa.jar, network-inspector.jar and the per-ABI transport/agent binaries pushed to
//             the device at attach time. These are device-ABI / JVM artifacts, so the Linux
//             distribution's copies work on any host OS.
abstract class SyncStudioBundleTask : DefaultTask() {

    @get:OutputDirectory
    abstract val bundleDir: DirectoryProperty

    /** Cache location for the downloaded archive; not part of the extracted output. */
    @get:Internal
    abstract val downloadDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @get:Inject
    abstract val archives: ArchiveOperations

    init {
        group = "studio"
        description = "Download the latest stable Android Studio (Linux) and extract the inspector bundle into studio-bundle/."
        // The point is to follow the moving "latest stable", so never report up-to-date.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun sync() {
        // Stable redirect target of jb.gg/android-studio-releases-list.json (TeamCity guest artifact).
        val feedUrl = "https://teamcity.jetbrains.com/guestAuth/repository/download/" +
            "AndroidStudioReleasesList/.lastSuccessful/android-studio-releases-list.json"
        @Suppress("UNCHECKED_CAST")
        val content = (JsonSlurper().parseText(URI(feedUrl).toURL().readText()) as Map<String, Any?>)["content"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val items = content["item"] as List<Map<String, Any?>>
        val latest = items.firstOrNull { it["channel"] == "Release" }
            ?: error("No stable (Release channel) Android Studio found in the release feed.")
        @Suppress("UNCHECKED_CAST")
        val downloads = latest["download"] as List<Map<String, Any?>>
        val linuxUrl = downloads.map { it["link"] as String }
            .firstOrNull { it.endsWith("-linux.tar.gz") }
            ?: error("No linux.tar.gz download for Android Studio ${latest["version"]}.")
        logger.lifecycle("Latest stable Android Studio: ${latest["version"]}")
        logger.lifecycle("Source: $linuxUrl")

        // Reuse a previously downloaded archive if present; deleted on success below.
        val dlDir = downloadDir.get().asFile.apply { mkdirs() }
        val tarFile = File(dlDir, linuxUrl.substringAfterLast('/'))
        if (tarFile.exists() && tarFile.length() > 0L) {
            logger.lifecycle("Using cached ${tarFile.name}")
        } else {
            logger.lifecycle("Downloading ${tarFile.name} (~1.5 GB), this may take a while...")
            URI(linuxUrl).toURL().openStream().use { input ->
                tarFile.outputStream().use { input.copyTo(it, 1 shl 20) }
            }
        }

        val target = bundleDir.get()
        fs.delete { delete(target) }
        fs.copy {
            into(target)
            // tarTree infers gzip from the .tar.gz extension.
            from(archives.tarTree(tarFile)) {
                include(
                    "**/plugins/android/lib/transport_java_proto.jar",
                    "**/plugins/android/lib/network_inspector_java_proto.jar",
                    "**/plugins/android/lib/studio-grpc.jar",
                    "**/plugins/android/lib/studio-proto.jar",
                    "**/plugins/android/resources/perfa.jar",
                    "**/plugins/android/resources/app-inspection/network-inspector.jar",
                    "**/plugins/android/resources/transport/**",
                )
                // Flatten lib jars to lib/, map resources/<x> to device/<x>.
                eachFile {
                    path = if ("/plugins/android/lib/" in path) "lib/$name"
                    else "device/" + path.substringAfter("/plugins/android/resources/")
                }
            }
            includeEmptyDirs = false
        }
        fs.delete { delete(tarFile) }
        logger.lifecycle("Studio bundle synced from Android Studio ${latest["version"]} to ${target.asFile}")
    }
}

tasks.register<SyncStudioBundleTask>("syncStudioBundle") {
    bundleDir.set(layout.projectDirectory.dir("studio-bundle"))
    downloadDir.set(layout.buildDirectory.dir("studio-download"))
}
