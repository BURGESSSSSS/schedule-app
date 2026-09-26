package com.burgess.schedule.school.sync.localadb

import java.util.Locale

internal enum class GlobalSocketSnapshotSlot(val label: String) {
    A1("A1 · 关闭课表"),
    B1("B1 · 打开课表"),
    A2("A2 · 再次关闭"),
    B2("B2 · 再次打开")
}

internal enum class GlobalSocketNameCategory(val label: String) {
    DEVTOOLS("DEVTOOLS"),
    XWEB("XWEB"),
    WEBVIEW("WEBVIEW"),
    TBS_X5("TBS/X5"),
    WEWORK("WEWORK"),
    OTHER("OTHER")
}

internal val NamedUnixSocket.instanceIdentity: UnixSocketInstanceIdentity
    get() = UnixSocketInstanceIdentity(inode, type, state, socketName, isAbstract)

internal val NamedUnixSocket.endpointIdentity: UnixSocketEndpointIdentity
    get() = UnixSocketEndpointIdentity(type, state, socketName, isAbstract)

internal data class GlobalUnixSocketStats(
    val totalLines: Int,
    val namedSockets: Int,
    val abstractSockets: Int
)

internal data class GlobalUnixSocketTableResult(
    val state: ProcUnixScanState,
    val stats: GlobalUnixSocketStats?,
    val sockets: List<NamedUnixSocket>
)

internal data class GlobalSocketSnapshot(
    val stats: GlobalUnixSocketStats?,
    val sockets: List<NamedUnixSocket>,
    val processes: List<WeWorkProcess>,
    val procUnixState: ProcUnixScanState,
    val processQueryState: ProcessQueryScanState,
    val procUnixFailure: String? = null,
    val processQueryFailure: String? = null
)

internal data class UnixSocketInstanceIdentity(
    val inode: Long,
    val type: Int,
    val state: Int,
    val socketName: String,
    val isAbstract: Boolean
)

internal data class UnixSocketEndpointIdentity(
    val type: Int,
    val state: Int,
    val socketName: String,
    val isAbstract: Boolean
)

internal data class UnixSocketCycleDelta(
    val added: List<NamedUnixSocket>,
    val removed: List<NamedUnixSocket>
)

internal data class StableUnixSocketAddition(
    val firstCycle: NamedUnixSocket,
    val secondCycle: NamedUnixSocket
)

internal data class RepeatedUnixSocketDiff(
    val firstCycle: UnixSocketCycleDelta,
    val secondCycle: UnixSocketCycleDelta,
    val stableAdded: List<StableUnixSocketAddition>,
    val randomAddedFirstCycle: List<NamedUnixSocket>,
    val randomAddedSecondCycle: List<NamedUnixSocket>
) {
    val randomAddedCount: Int
        get() = randomAddedFirstCycle.size + randomAddedSecondCycle.size
}

internal enum class GlobalSocketExperimentClassification {
    STABLE_SOCKET_DELTA_FOUND,
    DEVTOOLS_LIKE_DELTA_FOUND,
    NO_STABLE_SOCKET_DELTA,
    PROC_UNIX_READ_FAILED
}

private val UNIX_SOCKET_HEADER = Regex("^Num\\s+RefCount\\s+Protocol\\s+Flags\\s+Type\\s+St\\s+Inode(?:\\s+Path)?$", RegexOption.IGNORE_CASE)
internal fun parseGlobalUnixSocketTable(output: String, exitCode: Int?): GlobalUnixSocketTableResult {
    val lines = output.lineSequence().map { it.trimEnd('\r') }.filter(String::isNotBlank).toList()
    var headerFound = false
    val socketsByInode = linkedMapOf<Long, NamedUnixSocket>()

    for (line in lines) {
        val columns = line.trim().split(Regex("\\s+"))
        if (UNIX_SOCKET_HEADER.matches(columns.joinToString(" "))) {
            headerFound = true
            continue
        }
        if (columns.firstOrNull().equals("Num", ignoreCase = true) &&
            columns.getOrNull(6).equals("Inode", ignoreCase = true)
        ) {
            headerFound = true
            continue
        }

        if (columns.size < 8) continue
        val type = columns[4].toIntOrNull(16) ?: continue
        val state = columns[5].toIntOrNull(16) ?: continue
        val inode = columns[6].toLongOrNull() ?: continue
        val socketName = columns.drop(7).joinToString(" ").takeIf(String::isNotBlank) ?: continue
        socketsByInode.putIfAbsent(
            inode,
            NamedUnixSocket(
                inode = inode,
                type = type,
                state = state,
                socketName = socketName,
                isAbstract = socketName.startsWith('@')
            )
        )
    }

    val sockets = socketsByInode.values.toList()
    val stats = if (headerFound) {
        GlobalUnixSocketStats(
            totalLines = lines.size,
            namedSockets = sockets.size,
            abstractSockets = sockets.count(NamedUnixSocket::isAbstract)
        )
    } else {
        null
    }
    val successful = exitCode == 0 && stats != null
    return GlobalUnixSocketTableResult(
        state = if (successful) ProcUnixScanState.PROC_UNIX_SUCCESS else ProcUnixScanState.PROC_UNIX_FAILED,
        stats = stats,
        sockets = if (successful) sockets else emptyList()
    )
}

internal fun classifyGlobalSocketName(name: String): Set<GlobalSocketNameCategory> {
    val normalized = name.lowercase(Locale.ROOT)
    val categories = linkedSetOf<GlobalSocketNameCategory>()
    if ("devtools" in normalized) categories += GlobalSocketNameCategory.DEVTOOLS
    if ("xweb" in normalized) categories += GlobalSocketNameCategory.XWEB
    if ("webview" in normalized) categories += GlobalSocketNameCategory.WEBVIEW
    if ("tbs" in normalized || "x5" in normalized) categories += GlobalSocketNameCategory.TBS_X5
    if ("wework" in normalized || "wecom" in normalized) categories += GlobalSocketNameCategory.WEWORK
    if (categories.isEmpty()) categories += GlobalSocketNameCategory.OTHER
    return categories
}

internal fun socketNameMatchesWeWorkPid(socketName: String, processes: List<WeWorkProcess>): Boolean {
    val suffix = pidStyleSuffix(socketName) ?: return false
    return processes.any { it.pid == suffix }
}

internal fun diffNamedUnixSockets(
    before: List<NamedUnixSocket>,
    after: List<NamedUnixSocket>
): UnixSocketCycleDelta {
    val beforeIdentities = before.mapTo(hashSetOf(), NamedUnixSocket::instanceIdentity)
    val afterIdentities = after.mapTo(hashSetOf(), NamedUnixSocket::instanceIdentity)
    return UnixSocketCycleDelta(
        added = after.filterNot { it.instanceIdentity in beforeIdentities },
        removed = before.filterNot { it.instanceIdentity in afterIdentities }
    )
}

internal fun repeatedSocketDiff(
    a1: List<NamedUnixSocket>,
    b1: List<NamedUnixSocket>,
    a2: List<NamedUnixSocket>,
    b2: List<NamedUnixSocket>
): RepeatedUnixSocketDiff {
    val first = diffNamedUnixSockets(a1, b1)
    val second = diffNamedUnixSockets(a2, b2)
    val firstByEndpoint = first.added.associateBy(NamedUnixSocket::endpointIdentity)
    val secondByEndpoint = second.added.associateBy(NamedUnixSocket::endpointIdentity)
    val stableKeys = firstByEndpoint.keys.intersect(secondByEndpoint.keys)
    val stable = stableKeys.mapNotNull { key ->
        val firstEntry = firstByEndpoint[key] ?: return@mapNotNull null
        val secondEntry = secondByEndpoint[key] ?: return@mapNotNull null
        StableUnixSocketAddition(firstEntry, secondEntry)
    }.sortedBy { it.firstCycle.socketName }

    return RepeatedUnixSocketDiff(
        firstCycle = first,
        secondCycle = second,
        stableAdded = stable,
        randomAddedFirstCycle = first.added.filterNot { it.endpointIdentity in stableKeys },
        randomAddedSecondCycle = second.added.filterNot { it.endpointIdentity in stableKeys }
    )
}

internal fun canCompareGlobalSocketSnapshots(snapshots: List<GlobalSocketSnapshot>): Boolean =
    snapshots.size == GlobalSocketSnapshotSlot.values().size &&
        snapshots.all { it.procUnixState == ProcUnixScanState.PROC_UNIX_SUCCESS }

internal fun classifyGlobalSocketExperiment(
    snapshots: List<GlobalSocketSnapshot>,
    diff: RepeatedUnixSocketDiff
): GlobalSocketExperimentClassification {
    if (!canCompareGlobalSocketSnapshots(snapshots)) {
        return GlobalSocketExperimentClassification.PROC_UNIX_READ_FAILED
    }
    if (diff.stableAdded.any { addition ->
            val categories = classifyGlobalSocketName(addition.firstCycle.socketName)
            GlobalSocketNameCategory.DEVTOOLS in categories ||
                GlobalSocketNameCategory.XWEB in categories ||
                GlobalSocketNameCategory.WEBVIEW in categories
        }
    ) {
        return GlobalSocketExperimentClassification.DEVTOOLS_LIKE_DELTA_FOUND
    }
    return if (diff.stableAdded.isNotEmpty()) {
        GlobalSocketExperimentClassification.STABLE_SOCKET_DELTA_FOUND
    } else {
        GlobalSocketExperimentClassification.NO_STABLE_SOCKET_DELTA
    }
}

internal data class WeWorkProcessDelta(
    val added: List<WeWorkProcess>,
    val removed: List<WeWorkProcess>
)

internal fun diffWeWorkProcesses(
    beforeState: ProcessQueryScanState,
    before: List<WeWorkProcess>,
    afterState: ProcessQueryScanState,
    after: List<WeWorkProcess>
): WeWorkProcessDelta? {
    if (beforeState != ProcessQueryScanState.PROCESS_QUERY_SUCCESS ||
        afterState != ProcessQueryScanState.PROCESS_QUERY_SUCCESS
    ) return null

    val beforeByPid = before.associateBy(WeWorkProcess::pid)
    val afterByPid = after.associateBy(WeWorkProcess::pid)
    return WeWorkProcessDelta(
        added = after.filter { beforeByPid[it.pid] != it },
        removed = before.filter { afterByPid[it.pid] != it }
    )
}
