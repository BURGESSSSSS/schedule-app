package com.burgess.schedule.school.sync.localadb

import java.util.Locale

internal enum class ProcUnixScanState {
    PROC_UNIX_START,
    PROC_UNIX_SUCCESS,
    PROC_UNIX_FAILED
}

internal enum class ProcessQueryScanState {
    PROCESS_QUERY_START,
    PROCESS_QUERY_SUCCESS,
    PROCESS_QUERY_FAILED
}

internal enum class ProcFdScanState {
    FD_SCAN_START,
    FD_SCAN_SUCCESS,
    FD_SCAN_PERMISSION_DENIED,
    FD_SCAN_FAILED
}

internal enum class ProcMapsScanState {
    NOT_SCANNED,
    MAPS_SCAN_START,
    MAPS_SCAN_SUCCESS,
    MAPS_SCAN_PERMISSION_DENIED,
    MAPS_SCAN_FAILED
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

internal data class ProcUnixTableResult(
    val state: ProcUnixScanState,
    val stats: UnixSocketStats?,
    val socketNamesByInode: Map<Long, String?>
)

internal data class WeWorkProcess(
    val pid: Int,
    val processName: String
)

internal data class ProcessQueryResult(
    val state: ProcessQueryScanState,
    val processes: List<WeWorkProcess>
)

internal data class ProcFdScanResult(
    val pid: Int,
    val state: ProcFdScanState,
    val socketInodes: List<Long>
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

internal data class ProcMapsScanResult(
    val pid: Int,
    val state: ProcMapsScanState,
    val libraries: List<WebViewKernelLibrary>
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
    val procUnixState: ProcUnixScanState,
    val processQueryState: ProcessQueryScanState
) {
    val procUnixReadFailed: Boolean
        get() = procUnixState != ProcUnixScanState.PROC_UNIX_SUCCESS

    val processQueryFailed: Boolean
        get() = processQueryState != ProcessQueryScanState.PROCESS_QUERY_SUCCESS
}

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
private val SOCKET_DEBUG_TOKENS = listOf("devtools", "xweb", "webview")
private val MAPS_KEYWORDS = setOf("xweb", "webview", "chromium", "tbs", "x5")
private val SOCKET_FD_PATTERN = Regex("^__FD_SOCKET__\\|(\\d+)\\|(\\d+)$")
private val MAPS_LIBRARY_PATTERN = Regex("^__WX_MAPS_LIBRARY__\\|(\\d+)\\|([^|]+)\\|([^|]*)$")

internal fun isWeWorkProcessName(processName: String): Boolean =
    processName == TARGET_PROCESS || processName.startsWith("$TARGET_PROCESS:")

internal fun parseProcUnixTable(output: String, exitCode: Int?): ProcUnixTableResult {
    val lines = output.lineSequence().map { it.trimEnd('\r') }.filter(String::isNotBlank).toList()
    val namesByInode = linkedMapOf<Long, String?>()
    var headerFound = false

    lines.forEach { line ->
        val columns = line.trim().split(Regex("\\s+"))
        if (columns.firstOrNull().equals("Num", ignoreCase = true) && columns.getOrNull(6).equals("Inode", ignoreCase = true)) {
            headerFound = true
            return@forEach
        }
        val inode = columns.getOrNull(6)?.toLongOrNull() ?: return@forEach
        val name = columns.drop(7).joinToString(" ").ifBlank { null }
        namesByInode[inode] = name
    }

    val stats = if (headerFound) {
        val names = namesByInode.values.filterNotNull()
        UnixSocketStats(
            totalLines = lines.size,
            namedSockets = names.size,
            abstractSockets = names.count { it.startsWith("@") }
        )
    } else {
        null
    }
    val successful = exitCode == 0 && stats != null
    return ProcUnixTableResult(
        state = if (successful) ProcUnixScanState.PROC_UNIX_SUCCESS else ProcUnixScanState.PROC_UNIX_FAILED,
        stats = stats,
        socketNamesByInode = if (successful) namesByInode else emptyMap()
    )
}

internal fun parseProcessQuery(output: String, exitCode: Int?): ProcessQueryResult {
    var headerFound = false
    val processes = linkedMapOf<Int, WeWorkProcess>()
    output.lineSequence().map { it.trimEnd('\r') }.forEach { line ->
        val columns = line.trim().split(Regex("\\s+"))
        if (columns.size >= 2 && columns[0].equals("PID", ignoreCase = true) && columns[1].equals("NAME", ignoreCase = true)) {
            headerFound = true
            return@forEach
        }
        val pid = columns.firstOrNull()?.toIntOrNull() ?: return@forEach
        val processName = columns.getOrNull(1).orEmpty()
        if (pid > 0 && isWeWorkProcessName(processName)) {
            processes.putIfAbsent(pid, WeWorkProcess(pid, processName))
        }
    }
    val success = exitCode == 0 && headerFound
    return ProcessQueryResult(
        state = if (success) ProcessQueryScanState.PROCESS_QUERY_SUCCESS else ProcessQueryScanState.PROCESS_QUERY_FAILED,
        processes = if (success) processes.values.toList() else emptyList()
    )
}

internal fun parseProcessQueryForPackage(
    output: String,
    exitCode: Int?,
    packageName: String
): ProcessQueryResult {
    require(packageName.isNotBlank() && ':' !in packageName)
    val allProcesses = parseProcessQuery(output, exitCode)
    if (allProcesses.state != ProcessQueryScanState.PROCESS_QUERY_SUCCESS) return allProcesses
    val matches = output.lineSequence().map { it.trimEnd('\r') }.mapNotNull { line ->
        val columns = line.trim().split(Regex("\\s+"))
        val pid = columns.firstOrNull()?.toIntOrNull() ?: return@mapNotNull null
        val processName = columns.getOrNull(1).orEmpty()
        if (processName == packageName || processName.startsWith("$packageName:")) {
            WeWorkProcess(pid, processName)
        } else {
            null
        }
    }.distinctBy { it.pid }.toList()
    return allProcesses.copy(processes = matches)
}

internal fun parseProcFdScan(output: String, pid: Int, exitCode: Int?): ProcFdScanResult {
    require(pid > 0)
    val lines = output.lineSequence().map { it.trimEnd('\r').trim() }.toList()
    val successMarker = "__FD_SCAN_SUCCESS__|$pid"
    val permissionMarker = "__FD_SCAN_PERMISSION_DENIED__|$pid"
    val isSuccess = successMarker in lines && exitCode == 0
    val isDenied = permissionMarker in lines
    val state = when {
        isDenied -> ProcFdScanState.FD_SCAN_PERMISSION_DENIED
        isSuccess -> ProcFdScanState.FD_SCAN_SUCCESS
        else -> ProcFdScanState.FD_SCAN_FAILED
    }
    val inodes = if (state == ProcFdScanState.FD_SCAN_SUCCESS) {
        lines.mapNotNull { line ->
            SOCKET_FD_PATTERN.matchEntire(line)?.takeIf { it.groupValues[1].toIntOrNull() == pid }
                ?.groupValues?.getOrNull(2)?.toLongOrNull()
        }.distinct()
    } else {
        emptyList()
    }
    return ProcFdScanResult(pid, state, inodes)
}

internal fun parseProcMapsScan(output: String, pid: Int, exitCode: Int?): ProcMapsScanResult {
    require(pid > 0)
    val lines = output.lineSequence().map { it.trimEnd('\r').trim() }.toList()
    val success = "__MAPS_SCAN_SUCCESS__|$pid" in lines && exitCode == 0
    val denied = "__MAPS_SCAN_PERMISSION_DENIED__|$pid" in lines
    val state = when {
        denied -> ProcMapsScanState.MAPS_SCAN_PERMISSION_DENIED
        success -> ProcMapsScanState.MAPS_SCAN_SUCCESS
        else -> ProcMapsScanState.MAPS_SCAN_FAILED
    }
    val libraries = if (success) {
        lines.mapNotNull { line ->
            val match = MAPS_LIBRARY_PATTERN.matchEntire(line) ?: return@mapNotNull null
            if (match.groupValues[1].toIntOrNull() != pid) return@mapNotNull null
            val basename = match.groupValues[2]
            val keywords = match.groupValues[3].split(',').filter { it in MAPS_KEYWORDS }.toSet()
            if (basename.isBlank() || '/' in basename || keywords.isEmpty()) null
            else WebViewKernelLibrary(basename, keywords)
        }.distinctBy { it.basename.lowercase(Locale.ROOT) }
    } else {
        emptyList()
    }
    return ProcMapsScanResult(pid, state, libraries)
}

internal fun buildProcessAudit(
    process: WeWorkProcess,
    fdScan: ProcFdScanResult,
    socketNamesByInode: Map<Long, String?>,
    mapsScan: ProcMapsScanResult?
): WeWorkProcessAudit = WeWorkProcessAudit(
    process = process,
    fdScanState = fdScan.state,
    mapsScanState = mapsScan?.state ?: ProcMapsScanState.NOT_SCANNED,
    sockets = fdScan.socketInodes.map { inode ->
        OwnedUnixSocket(
            inode = inode,
            presentInProcUnix = socketNamesByInode.containsKey(inode),
            socketName = socketNamesByInode[inode]
        )
    },
    libraries = mapsScan?.libraries.orEmpty()
)

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
        "devtools" in name && SOCKET_DEBUG_TOKENS.any { token -> name.contains(token) }
    }
    if (hasOwnedDebugSocket) return SocketOwnershipClassification.OWNED_DEVTOOLS_SOCKET_FOUND
    if (snapshot.processes.any { it.fdScanState == ProcFdScanState.FD_SCAN_PERMISSION_DENIED }) {
        return SocketOwnershipClassification.PROC_FD_PERMISSION_DENIED
    }
    if (baseline?.processes?.any { it.fdScanState == ProcFdScanState.FD_SCAN_PERMISSION_DENIED } == true) {
        return SocketOwnershipClassification.PROC_FD_PERMISSION_DENIED
    }
    if (snapshot.processes.any { it.fdScanState != ProcFdScanState.FD_SCAN_SUCCESS }) {
        return SocketOwnershipClassification.PROCESS_SCAN_INCOMPLETE
    }
    if (baseline?.processes?.any { it.fdScanState != ProcFdScanState.FD_SCAN_SUCCESS } == true) {
        return SocketOwnershipClassification.PROCESS_SCAN_INCOMPLETE
    }
    if (baseline?.procUnixReadFailed == true) return SocketOwnershipClassification.PROC_UNIX_READ_FAILED
    if (baseline?.processQueryFailed == true) return SocketOwnershipClassification.PROCESS_QUERY_FAILED
    if (baseline != null && newlyOwnedSockets(baseline, snapshot).isNotEmpty()) {
        return SocketOwnershipClassification.OWNED_UNIX_SOCKET_FOUND
    }
    return SocketOwnershipClassification.NO_OWNED_DEVTOOLS_SOCKET
}
