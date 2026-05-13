package com.jisungbin.networkinspector.adb

import com.android.ddmlib.IDevice

fun IDevice.listThirdPartyPackages(): List<String> =
    shell("pm list packages -3").lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("package:") }
        .map { it.removePrefix("package:") }
        .filter { it.isNotEmpty() }
        .sorted()
        .toList()

fun IDevice.resolveLauncherActivity(packageName: String): String? {
    val out = shell(
        "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $packageName"
    )
    val candidate = out.lineSequence()
        .map { it.trim() }
        .lastOrNull { it.startsWith("$packageName/") }
        ?: return null
    return candidate.takeIf { "/" in it }
}

fun IDevice.foregroundPackage(): String? {
    val out = shell("dumpsys activity activities | grep -E 'mResumedActivity|ResumedActivity'")
    val match = Regex("""([\w.]+)/[\w.\$]+""").find(out) ?: return null
    return match.groupValues.getOrNull(1)
}
