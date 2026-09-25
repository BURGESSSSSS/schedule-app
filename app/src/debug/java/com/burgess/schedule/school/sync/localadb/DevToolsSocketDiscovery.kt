package com.burgess.schedule.school.sync.localadb

import java.util.Locale
import kotlinx.coroutines.CancellationException

internal data class DevToolsSocketCandidate(
    val socketName: String,
    val inode: Long,
    val type: Int,
    val state: Int,
    val priority: Int
) {
    val adbServiceName: String
        get() = socketName.removePrefix("@")
}

internal data class DevToolsSocketAttempt(
    val socketName: String,
    val inode: Long,
    val endpointPath: String,
    val opened: Boolean,
    val httpStatus: Int?,
    val targetCount: Int?,
    val failure: String? = null
)

internal data class DevToolsTargetDiscoveryResult(
    val finalState: DevToolsDiscoveryState,
    val candidates: List<DevToolsSocketCandidate>,
    val attempts: List<DevToolsSocketAttempt>,
    val targets: List<DevToolsTarget>,
    val endpointUsed: DevToolsEndpoint?
)

internal fun findDevToolsSocketCandidates(sockets: List<NamedUnixSocket>): List<DevToolsSocketCandidate> =
    sockets.asSequence()
        .filter { it.isAbstract && it.socketName.startsWith('@') }
        .mapNotNull { socket ->
            val name = socket.socketName.lowercase(Locale.ROOT)
            val priority = when {
                "devtools" in name -> 0
                "webview" in name -> 1
                "xweb" in name -> 2
                "chromium" in name -> 3
                else -> return@mapNotNull null
            }
            DevToolsSocketCandidate(
                socketName = socket.socketName,
                inode = socket.inode,
                type = socket.type,
                state = socket.state,
                priority = priority
            )
        }
        .distinctBy { it.inode }
        .sortedWith(compareBy<DevToolsSocketCandidate> { it.priority }.thenBy { it.socketName.lowercase(Locale.ROOT) })
        .take(MAX_DEVTOOLS_SOCKET_CANDIDATES)
        .toList()

internal suspend fun discoverDevToolsTargets(
    client: LocalAdbClient,
    candidates: List<DevToolsSocketCandidate>,
    processes: List<WeWorkProcess>,
    onState: (DevToolsDiscoveryState) -> Unit
): DevToolsTargetDiscoveryResult {
    if (candidates.isEmpty()) {
        onState(DevToolsDiscoveryState.DEVTOOLS_SOCKET_NOT_FOUND)
        return DevToolsTargetDiscoveryResult(
            finalState = DevToolsDiscoveryState.DEVTOOLS_SOCKET_NOT_FOUND,
            candidates = emptyList(),
            attempts = emptyList(),
            targets = emptyList(),
            endpointUsed = null
        )
    }

    val attempts = mutableListOf<DevToolsSocketAttempt>()
    val targets = mutableListOf<DevToolsTarget>()
    var openedAny = false
    var httpSucceeded = false
    var targetListParsed = false
    var targetListParseFailed = false
    var endpointUsed: DevToolsEndpoint? = null

    for (candidate in candidates) {
        val processForSocket = pidStyleSuffix(candidate.socketName)?.let { pid ->
            processes.firstOrNull { it.pid == pid }
        }
        var probe = requestFromSocket(client, candidate, DevToolsEndpoint.JSON_LIST, onState)
        attempts += probe.attempt
        openedAny = openedAny || probe.attempt.opened

        if (probe.response?.let { shouldTryLegacyDevToolsEndpoint(it.statusCode) } == true) {
            probe = requestFromSocket(client, candidate, DevToolsEndpoint.JSON, onState)
            attempts += probe.attempt
            openedAny = openedAny || probe.attempt.opened
        }

        val response = probe.response ?: continue
        if (response.statusCode !in 200..299) continue
        httpSucceeded = true
        endpointUsed = probe.endpoint

        when (val parsed = parseDevToolsTargetList(response.body, candidate.socketName, processes)) {
            DevToolsTargetParseResult.InvalidJson -> {
                targetListParseFailed = true
                onState(DevToolsDiscoveryState.TARGET_LIST_PARSE_FAILED)
                val lastIndex = attempts.lastIndex
                if (lastIndex >= 0) {
                    attempts[lastIndex] = attempts[lastIndex].copy(
                        failure = "INVALID_JSON",
                        targetCount = null
                    )
                }
            }
            is DevToolsTargetParseResult.Parsed -> {
                targetListParsed = true
                onState(DevToolsDiscoveryState.TARGET_LIST_PARSED)
                val parsedTargets = parsed.targets.map { target ->
                    if (processForSocket == null) target else target.copy(
                        processName = processForSocket.processName,
                        containerName = processForSocket.processName.substringBefore(':')
                    )
                }
                targets += parsedTargets
                val lastIndex = attempts.lastIndex
                if (lastIndex >= 0) attempts[lastIndex] = attempts[lastIndex].copy(targetCount = parsedTargets.size)

                if (parsedTargets.any { it.schoolMatch == SchoolTargetMatch.SCHOOL_TARGET_FOUND }) {
                    onState(DevToolsDiscoveryState.SCHOOL_TARGET_FOUND)
                    return DevToolsTargetDiscoveryResult(
                        finalState = DevToolsDiscoveryState.SCHOOL_TARGET_FOUND,
                        candidates = candidates,
                        attempts = attempts,
                        targets = targets.distinctBy { Triple(it.socketName, it.id, it.displayUrl) },
                        endpointUsed = endpointUsed
                    )
                }
            }
        }
    }

    val finalState = when {
        targets.any { it.schoolMatch == SchoolTargetMatch.SCHOOL_HOST_TARGET_FOUND } ->
            DevToolsDiscoveryState.SCHOOL_HOST_TARGET_FOUND
        targetListParsed -> DevToolsDiscoveryState.DEVTOOLS_CONNECTED_NO_SCHOOL_TARGET
        httpSucceeded && targetListParseFailed -> DevToolsDiscoveryState.TARGET_LIST_PARSE_FAILED
        httpSucceeded -> DevToolsDiscoveryState.DEVTOOLS_HTTP_FAILED
        openedAny -> DevToolsDiscoveryState.DEVTOOLS_HTTP_FAILED
        else -> DevToolsDiscoveryState.DEVTOOLS_SOCKET_OPEN_FAILED
    }
    onState(finalState)
    return DevToolsTargetDiscoveryResult(
        finalState = finalState,
        candidates = candidates,
        attempts = attempts,
        targets = targets.distinctBy { Triple(it.socketName, it.id, it.displayUrl) },
        endpointUsed = endpointUsed
    )
}

private suspend fun requestFromSocket(
    client: LocalAdbClient,
    candidate: DevToolsSocketCandidate,
    endpoint: DevToolsEndpoint,
    onState: (DevToolsDiscoveryState) -> Unit
): SocketProbe {
    onState(DevToolsDiscoveryState.DEVTOOLS_SOCKET_OPEN_START)
    val stream = try {
        client.openDevToolsSocket(candidate.socketName)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (exception: Exception) {
        onState(DevToolsDiscoveryState.DEVTOOLS_SOCKET_OPEN_FAILED)
        return SocketProbe(
            endpoint = endpoint,
            response = null,
            attempt = DevToolsSocketAttempt(
                socketName = candidate.socketName,
                inode = candidate.inode,
                endpointPath = endpoint.path,
                opened = false,
                httpStatus = null,
                targetCount = null,
                failure = exception.javaClass.simpleName.ifBlank { "SOCKET_OPEN_FAILED" }
            )
        )
    }

    onState(DevToolsDiscoveryState.DEVTOOLS_SOCKET_OPEN_SUCCESS)
    onState(DevToolsDiscoveryState.DEVTOOLS_HTTP_START)
    val readResult = readDevToolsHttpResponse(stream, endpoint)
    return when (readResult) {
        is DevToolsHttpReadResult.Failed -> {
            onState(DevToolsDiscoveryState.DEVTOOLS_HTTP_FAILED)
            SocketProbe(
                endpoint = endpoint,
                response = null,
                attempt = DevToolsSocketAttempt(
                    socketName = candidate.socketName,
                    inode = candidate.inode,
                    endpointPath = endpoint.path,
                    opened = true,
                    httpStatus = null,
                    targetCount = null,
                    failure = readResult.failure.name
                )
            )
        }
        is DevToolsHttpReadResult.Success -> {
            if (readResult.response.statusCode in 200..299) {
                onState(DevToolsDiscoveryState.DEVTOOLS_HTTP_SUCCESS)
            } else {
                onState(DevToolsDiscoveryState.DEVTOOLS_HTTP_FAILED)
            }
            SocketProbe(
                endpoint = endpoint,
                response = readResult.response,
                attempt = DevToolsSocketAttempt(
                    socketName = candidate.socketName,
                    inode = candidate.inode,
                    endpointPath = endpoint.path,
                    opened = true,
                    httpStatus = readResult.response.statusCode,
                    targetCount = null,
                    failure = if (readResult.response.statusCode in 200..299) null else "HTTP_${readResult.response.statusCode}"
                )
            )
        }
    }
}

private data class SocketProbe(
    val endpoint: DevToolsEndpoint,
    val response: DevToolsHttpResponse?,
    val attempt: DevToolsSocketAttempt
)

private const val MAX_DEVTOOLS_SOCKET_CANDIDATES = 24
