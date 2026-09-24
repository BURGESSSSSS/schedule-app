package com.burgess.schedule.school.sync.localadb

import java.util.Locale

internal enum class ProcFdScanState {
    ENUMERATED,
    PERMISSION_DENIED,
    PROCESS_DISAPPEARED,
    NOT_SCANNED
}

internal enum class ProcMapsScanState {
    READABLE,
    READ_FAILED,
    NOT_SCANNED
}

internal enum class WebViewKernelKind(val displayName: String) {
    ANDROID_SYSTEM_WEBVIEW("Android System WebView"),
    XWEB("XWeb"),
    TBS_X5("TBS/X5"),
    OTHER_CHROMIUM("其他 Chromium 容器"),
    UNKNOWN("未命中指定库名")
}

internal data class UnixSocketStats(
    val totalLines: Int,
    val namedSockets: Int,
    val abstractSockets: Int
)

internal data class WeWorkProcess(
    val pid: Int,
    val processName: String
)

internal data class OwnedUnixSocket(
    val inode: Long,
    val presentInProcUnix: Boolean,
    val socketName: String?
)

internal data class WebViewKernelLibrary(
    val basename: String,
    val keywords: Set<String>
)

internal data class WeWorkProcessAudit(
    val process: WeWorkProcess,
    val fdScanState: ProcFdScanState,
    val mapsScanState: ProcMapsScanState,
    val sockets: List<OwnedUnixSocket>,
    val libraries: List<WebViewKernelLibrary>
)

internal data class OwnedSocketReference(
    val process: WeWorkProcess,
    val socket: OwnedUnixSocket
)

internal data class LocalAdbScanSnapshot(
    val stats: UnixSocketStats?,
    val processes: List<WeWorkProcessAudit>,
    val procUnixReadFailed: Boolean,
    val processQueryFailed: Boolean
)

internal enum class SocketOwnershipClassification {
    OWNED_DEVTOOLS_SOCKET_FOUND,
    OWNED_UNIX_SOCKET_FOUND,
    NO_OWNED_DEVTOOLS_SOCKET,
    PROC_FD_PERMISSION_DENIED,
    PROCESS_NOT_FOUND,
    PROCESS_QUERY_FAILED,
    PROCESS_SCAN_INCOMPLETE,
    PROC_UNIX_READ_FAILED
}

private const val TARGET_PROCESS = "com.tencent.wework"
private val SUMMARY_PATTERN = Regex("^__PROC_UNIX_SUMMARY__\\|(\\d+)\\|(\\d+)\\|(\\d+)$")
private val PROC_EXIT_PATTERN = Regex("^__PROC_UNIX_EXIT__\\|(\\d+)$")
private val PROCESS_PATTERN = Regex("^__WXWORK_PROCESS__\\|(\\d+)\\|(.+)$")
private val FD_SOCKET_PATTERN = Regex("^__OWNED_SOCKET__\\|(\\d+)\\|(\\d+)\\|([01])\\|(.*)$")
private val MAPS_LIBRARY_PATTERN = Regex("^__WX_MAPS_LIBRARY__\\|(\\d+)\\|([^|]+)\\|([^|]*)$")
private val MAPS_KEYWORDS = setOf("xweb", "webview", "chromium", "tbs", "x5")

internal fun isWeWorkProcessName(processName: String): Boolean =
    processName == TARGET_PROCESS || processName.startsWith("$TARGET_PROCESS:")

internal fun parseLocalAdbScanSnapshot(output: String): LocalAdbScanSnapshot {
    val lines = output.lineSequence().map(String::trim).toList()
    val stats = lines.mapNotNull { line ->
        SUMMARY_PATTERN.matchEntire(line)?.let { match ->
            UnixSocketStats(
                totalLines = match.groupValues[1].toIntOrNull() ?: 0,
                namedSockets = match.groupValues[2].toIntOrNull() ?: 0,
                abstractSockets = match.groupValues[3].toIntOrNull() ?: 0
            )
        }
    }.lastOrNull()
    val procExitCode = lines.mapNotNull { line ->
        PROC_EXIT_PATTERN.matchEntire(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }.lastOrNull()
    val scanCompleted = lines.any { it == "__LOCAL_ADB_SCAN_DONE__" }
    val unixMapRead = lines.any { it == "__PROC_UNIX_MAP_OK__" }
    val unixMapFailed = lines.any { it == "__PROC_UNIX_MAP_FAILED__" }
    val procUnixReadFailed =
        !scanCompleted || stats == null || stats.totalLines <= 0 || procExitCode != 0 || !unixMapRead || unixMapFailed
    val processQueryFailed = lines.any { it == "__WXWORK_QUERY_FAILED__" } ||
        lines.none { it == "__WXWORK_QUERY_DONE__" }

    val processes = linkedMapOf<Int, MutableProcessAudit>()
    lines.forEach { line ->
        PROCESS_PATTERN.matchEntire(line)?.let { match ->
            val pid = match.groupValues[1].toIntOrNull() ?: return@let
            val name = match.groupValues[2]
            if (pid > 0 && isWeWorkProcessName(name)) {
                processes.putIfAbsent(pid, MutableProcessAudit(WeWorkProcess(pid, name)))
            }
        }
    }

    lines.forEach { line ->
        val fields = line.split('|')
        val pid = fields.getOrNull(1)?.toIntOrNull()
        when (fields.firstOrNull()) {
            "__PROC_FD_OK__" -> pid?.let { processes[it]?.fdScanState = ProcFdScanState.ENUMERATED }
            "__PROC_FD_PERMISSION_DENIED__", "__PROC_FD_ENTRY_UNREADABLE__" ->
                pid?.let { processes[it]?.fdScanState = ProcFdScanState.PERMISSION_DENIED }
            "__PROC_FD_PROCESS_GONE__" -> pid?.let { processes[it]?.fdScanState = ProcFdScanState.PROCESS_DISAPPEARED }
            "__PROC_MAPS_READ_OK__" -> pid?.let { processes[it]?.mapsScanState = ProcMapsScanState.READABLE }
            "__PROC_MAPS_READ_FAILED__" -> pid?.let { processes[it]?.mapsScanState = ProcMapsScanState.READ_FAILED }
            else -> Unit
        }

        FD_SOCKET_PATTERN.matchEntire(line)?.let { match ->
            val socketPid = match.groupValues[1].toIntOrNull() ?: return@let
            val inode = match.groupValues[2].toLongOrNull() ?: return@let
            processes[socketPid]?.sockets?.putIfAbsent(
                inode,
                OwnedUnixSocket(
                    inode = inode,
                    presentInProcUnix = match.groupValues[3] == "1",
                    socketName = match.groupValues[4].ifBlank { null }
                )
            )
        }

        MAPS_LIBRARY_PATTERN.matchEntire(line)?.let { match ->
            val mapsPid = match.groupValues[1].toIntOrNull() ?: return@let
            val basename = match.groupValues[2]
            val keywords = match.groupValues[3]
                .split(',')
                .map(String::trim)
                .filter { it in MAPS_KEYWORDS }
                .toSet()
            if (basename.isNotBlank() && '/' !in basename && keywords.isNotEmpty()) {
                processes[mapsPid]?.libraries?.putIfAbsent(
                    basename.lowercase(Locale.ROOT),
                    WebViewKernelLibrary(basename, keywords)
                )
            }
        }
    }

    return LocalAdbScanSnapshot(
        stats = stats,
        processes = processes.values.map(MutableProcessAudit::toImmutable),
        procUnixReadFailed = procUnixReadFailed,
        processQueryFailed = processQueryFailed
    )
}

internal fun newlyAppearedProcesses(
    baseline: List<WeWorkProcessAudit>,
    current: List<WeWorkProcessAudit>
): List<WeWorkProcess> {
    val previous = baseline.mapTo(hashSetOf()) { it.process.pid to it.process.processName }
    return current.map(WeWorkProcessAudit::process).filterNot { process ->
        (process.pid to process.processName) in previous
    }
}

internal fun ownedSocketReferences(snapshot: LocalAdbScanSnapshot): List<OwnedSocketReference> =
    snapshot.processes.flatMap { audit ->
        audit.sockets.map { socket -> OwnedSocketReference(audit.process, socket) }
    }

/** A socket is newly owned if this PID did not hold this inode in the baseline. */
internal fun newlyOwnedSockets(
    baseline: LocalAdbScanSnapshot,
    current: LocalAdbScanSnapshot
): List<OwnedSocketReference> {
    val previous = ownedSocketReferences(baseline).mapTo(hashSetOf()) { reference ->
        reference.process.pid to reference.socket.inode
    }
    return ownedSocketReferences(current).filterNot { reference ->
        (reference.process.pid to reference.socket.inode) in previous
    }
}

internal fun newlyObservedSocketInodes(
    baseline: LocalAdbScanSnapshot,
    current: LocalAdbScanSnapshot
): Set<Long> {
    val previous = ownedSocketReferences(baseline).mapTo(hashSetOf()) { it.socket.inode }
    return ownedSocketReferences(current).map { it.socket.inode }.filterNot { it in previous }.toSet()
}

internal fun identifyWebViewKernel(libraries: List<WebViewKernelLibrary>): WebViewKernelKind {
    val keywords = libraries.flatMapTo(hashSetOf()) { it.keywords }
    return when {
        "xweb" in keywords -> WebViewKernelKind.XWEB
        "tbs" in keywords || "x5" in keywords -> WebViewKernelKind.TBS_X5
        "webview" in keywords -> WebViewKernelKind.ANDROID_SYSTEM_WEBVIEW
        "chromium" in keywords -> WebViewKernelKind.OTHER_CHROMIUM
        else -> WebViewKernelKind.UNKNOWN
    }
}

internal fun classifySocketOwnership(
    snapshot: LocalAdbScanSnapshot,
    baseline: LocalAdbScanSnapshot?
): SocketOwnershipClassification {
    if (snapshot.procUnixReadFailed) return SocketOwnershipClassification.PROC_UNIX_READ_FAILED
    if (snapshot.processQueryFailed) return SocketOwnershipClassification.PROCESS_QUERY_FAILED
    if (snapshot.processes.isEmpty()) return SocketOwnershipClassification.PROCESS_NOT_FOUND

    val hasOwnedDebugSocket = ownedSocketReferences(snapshot).any { reference ->
        val name = reference.socket.socketName.orEmpty().lowercase(Locale.ROOT)
        "devtools" in name && ("remote" in name || "xweb" in name || "webview" in name)
    }
    if (hasOwnedDebugSocket) return SocketOwnershipClassification.OWNED_DEVTOOLS_SOCKET_FOUND
    if (snapshot.processes.any { it.fdScanState == ProcFdScanState.PERMISSION_DENIED }) {
        return SocketOwnershipClassification.PROC_FD_PERMISSION_DENIED
    }
    if (baseline?.processes?.any { it.fdScanState == ProcFdScanState.PERMISSION_DENIED } == true) {
        return SocketOwnershipClassification.PROC_FD_PERMISSION_DENIED
    }
    if (snapshot.processes.any { it.fdScanState != ProcFdScanState.ENUMERATED }) {
        return SocketOwnershipClassification.PROCESS_SCAN_INCOMPLETE
    }
    if (baseline?.processes?.any { it.fdScanState != ProcFdScanState.ENUMERATED } == true) {
        return SocketOwnershipClassification.PROCESS_SCAN_INCOMPLETE
    }
    if (baseline?.procUnixReadFailed == true) return SocketOwnershipClassification.PROC_UNIX_READ_FAILED
    if (baseline?.processQueryFailed == true) return SocketOwnershipClassification.PROCESS_QUERY_FAILED
    if (baseline != null && newlyOwnedSockets(baseline, snapshot).isNotEmpty()) {
        return SocketOwnershipClassification.OWNED_UNIX_SOCKET_FOUND
    }
    return SocketOwnershipClassification.NO_OWNED_DEVTOOLS_SOCKET
}

private class MutableProcessAudit(val process: WeWorkProcess) {
    var fdScanState: ProcFdScanState = ProcFdScanState.NOT_SCANNED
    var mapsScanState: ProcMapsScanState = ProcMapsScanState.NOT_SCANNED
    val sockets = linkedMapOf<Long, OwnedUnixSocket>()
    val libraries = linkedMapOf<String, WebViewKernelLibrary>()

    fun toImmutable() = WeWorkProcessAudit(
        process = process,
        fdScanState = fdScanState,
        mapsScanState = mapsScanState,
        sockets = sockets.values.toList(),
        libraries = libraries.values.toList()
    )
}
