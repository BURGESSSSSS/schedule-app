package com.burgess.schedule.school.sync.localadb

import java.util.Locale

internal enum class DevtoolsSocketKind {
    XWEB,
    WEBVIEW,
    OTHER
}

internal data class UnixSocketStats(
    val totalLines: Int,
    val namedSockets: Int,
    val abstractSockets: Int
)

internal data class DevtoolsSocket(
    val name: String,
    val kind: DevtoolsSocketKind,
    val pidSuffix: Int?
)

internal data class WeWorkProcess(
    val pid: Int,
    val processName: String
)

internal data class SocketPidMatch(
    val socketName: String,
    val process: WeWorkProcess
)

internal data class LocalAdbScanSnapshot(
    val stats: UnixSocketStats?,
    val sockets: List<DevtoolsSocket>,
    val weWorkProcesses: List<WeWorkProcess>,
    val pidMatches: List<SocketPidMatch>,
    val procUnixReadFailed: Boolean,
    val processQueryFailed: Boolean
)

private const val SOCKET_RECORD_PREFIX = "__DEVTOOLS_SOCKET__|"
private const val PROCESS_RECORD_PREFIX = "__WXWORK_PROCESS__|"
private val SUMMARY_PATTERN = Regex("^__PROC_UNIX_SUMMARY__\\|(\\d+)\\|(\\d+)\\|(\\d+)$")
private val PROC_EXIT_PATTERN = Regex("^__PROC_UNIX_EXIT__\\|(\\d+)$")
private val SOCKET_PID_SUFFIX_PATTERN = Regex("_(\\d+)$")
private val CANDIDATE_NAME_TOKENS = listOf("devtools", "xweb", "webview", "tbs")
private const val TARGET_PROCESS = "com.tencent.wework"

/** Parses only the scanner's reduced records; raw `/proc/net/unix` rows are never retained for UI. */
internal fun parseDevtoolsSocketRecords(output: String): List<DevtoolsSocket> {
    val sockets = linkedMapOf<String, DevtoolsSocket>()
    output.lineSequence().forEach { line ->
        if (!line.startsWith(SOCKET_RECORD_PREFIX)) return@forEach
        val name = line.substring(SOCKET_RECORD_PREFIX.length).trim().removeSuffix("\r")
        if (name.isBlank()) return@forEach
        val normalized = name.lowercase(Locale.ROOT)
        if (CANDIDATE_NAME_TOKENS.none { normalized.contains(it) }) return@forEach

        val kind = when {
            "xweb" in normalized -> DevtoolsSocketKind.XWEB
            "webview" in normalized -> DevtoolsSocketKind.WEBVIEW
            else -> DevtoolsSocketKind.OTHER
        }
        val pid = SOCKET_PID_SUFFIX_PATTERN.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
        sockets.putIfAbsent(
            normalized,
            DevtoolsSocket(name = name, kind = kind, pidSuffix = pid)
        )
    }
    return sockets.values.toList()
}

internal fun parseWeWorkProcessRecords(output: String): List<WeWorkProcess> {
    val processes = linkedMapOf<Int, WeWorkProcess>()
    output.lineSequence().forEach { line ->
        if (!line.startsWith(PROCESS_RECORD_PREFIX)) return@forEach
        val fields = line.substring(PROCESS_RECORD_PREFIX.length).split('|', limit = 2)
        if (fields.size != 2) return@forEach
        val pid = fields[0].toIntOrNull() ?: return@forEach
        val processName = fields[1].trim().removeSuffix("\r")
        if (pid <= 0 || !isWeWorkProcessName(processName)) return@forEach
        processes.putIfAbsent(pid, WeWorkProcess(pid, processName))
    }
    return processes.values.toList()
}

internal fun isWeWorkProcessName(processName: String): Boolean =
    processName == TARGET_PROCESS || processName.startsWith("$TARGET_PROCESS:")

internal fun matchSocketPids(
    sockets: List<DevtoolsSocket>,
    processes: List<WeWorkProcess>
): List<SocketPidMatch> {
    val processesByPid = processes.associateBy(WeWorkProcess::pid)
    return sockets.mapNotNull { socket ->
        socket.pidSuffix?.let(processesByPid::get)?.let { process ->
            SocketPidMatch(socket.name, process)
        }
    }
}

/** Parses summary and filtered records emitted by the one-shot read-only ADB scan. */
internal fun parseLocalAdbScanSnapshot(output: String): LocalAdbScanSnapshot {
    val stats = output.lineSequence().mapNotNull { line ->
        SUMMARY_PATTERN.matchEntire(line.trim())?.let { match ->
            UnixSocketStats(
                totalLines = match.groupValues[1].toIntOrNull() ?: 0,
                namedSockets = match.groupValues[2].toIntOrNull() ?: 0,
                abstractSockets = match.groupValues[3].toIntOrNull() ?: 0
            )
        }
    }.lastOrNull()
    val procExitCode = output.lineSequence().mapNotNull { line ->
        PROC_EXIT_PATTERN.matchEntire(line.trim())?.groupValues?.getOrNull(1)?.toIntOrNull()
    }.lastOrNull()
    val scanCompleted = output.lineSequence().any { it.trim() == "__LOCAL_ADB_SCAN_DONE__" }
    val processQueryFailed = output.lineSequence().none { it.trim() == "__WXWORK_QUERY_DONE__" }
    val procUnixReadFailed =
        !scanCompleted || stats == null || stats.totalLines <= 0 || procExitCode != 0

    val sockets = parseDevtoolsSocketRecords(output)
    val processes = parseWeWorkProcessRecords(output)
    return LocalAdbScanSnapshot(
        stats = stats,
        sockets = sockets,
        weWorkProcesses = processes,
        pidMatches = matchSocketPids(sockets, processes),
        procUnixReadFailed = procUnixReadFailed,
        processQueryFailed = processQueryFailed
    )
}

internal fun newlyAppearedSockets(
    baseline: List<DevtoolsSocket>,
    current: List<DevtoolsSocket>
): List<DevtoolsSocket> {
    val baselineNames = baseline.mapTo(hashSetOf()) { it.name.lowercase(Locale.ROOT) }
    return current.filterNot { it.name.lowercase(Locale.ROOT) in baselineNames }
}

internal fun newlyAppearedWeWorkProcesses(
    baseline: List<WeWorkProcess>,
    current: List<WeWorkProcess>
): List<WeWorkProcess> {
    val baselineProcesses = baseline.mapTo(hashSetOf()) { it.pid to it.processName }
    return current.filterNot { (it.pid to it.processName) in baselineProcesses }
}
