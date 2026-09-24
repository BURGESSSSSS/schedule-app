package com.burgess.schedule.school.sync.localadb

private val WEBVIEW_SOCKET_NAME_PATTERN =
    Regex("@?(webview_devtools_remote(?:_[A-Za-z0-9.-]+)?)")
private val SOCKET_SCAN_EXIT_CODE_PATTERN =
    Regex("(?:^|\\r?\\n)__LOCAL_ADB_SCAN_DONE__(\\d+)\\r?(?:\\n|$)")

/** Extract only abstract socket names from filtered `/proc/net/unix` rows or raw socket names. */
internal fun parseWebViewSocketNames(output: String): List<String> {
    val names = linkedSetOf<String>()
    output.lineSequence().forEach { line ->
        val candidate = line.trim().split(WHITESPACE).lastOrNull().orEmpty()
        val match = WEBVIEW_SOCKET_NAME_PATTERN.matchEntire(candidate) ?: return@forEach
        names += match.groupValues[1]
    }
    return names.toList()
}

internal fun socketScanExitCode(output: String): Int? =
    SOCKET_SCAN_EXIT_CODE_PATTERN.find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()

internal fun hasSocketScanFinished(output: String): Boolean = socketScanExitCode(output) != null

private val WHITESPACE = Regex("\\s+")
