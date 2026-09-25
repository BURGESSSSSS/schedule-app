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
    val endpointUsed: DevToolsEndpoint?,
    val cdpFailure: DevToolsCdpFailure? = null,
    val cdpHandshakeDiagnostic: DevToolsHandshakeDiagnostic? = null
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
    var candidateUrlMissing = false
    var endpointUsed: DevToolsEndpoint? = null
    var cdpFailure: DevToolsCdpFailure? = null
    var cdpHandshakeDiagnostic: DevToolsHandshakeDiagnostic? = null

    for (candidate in candidates) {
        var firstProbe = requestFromSocket(client, candidate, DevToolsEndpoint.JSON_LIST, onState)
        attempts += firstProbe.attempt
        openedAny = openedAny || firstProbe.attempt.opened

        if (firstProbe.response?.let { shouldTryLegacyDevToolsEndpoint(it.statusCode) } == true) {
            firstProbe = requestFromSocket(client, candidate, DevToolsEndpoint.JSON, onState)
            attempts += firstProbe.attempt
            openedAny = openedAny || firstProbe.attempt.opened
        }

        val firstResponse = firstProbe.response ?: continue
        if (firstResponse.statusCode !in 200..299) continue
        httpSucceeded = true
        endpointUsed = firstProbe.endpoint

        val firstParsed = parseResponse(firstResponse, firstProbe.endpoint, candidate, processes)
        when (firstParsed) {
            ParseResponse.Invalid -> {
                targetListParseFailed = true
                markLastAttemptFailure(attempts, "INVALID_JSON")
                onState(DevToolsDiscoveryState.TARGET_LIST_PARSE_FAILED)
                continue
            }
            is ParseResponse.Valid -> {
                targetListParsed = true
                onState(DevToolsDiscoveryState.TARGET_LIST_PARSED)
            }
        }
        val firstEntries = (firstParsed as ParseResponse.Valid).targets
        targets += displayableTargets(firstEntries)
        val pageCandidate = firstEntries.firstOrNull {
            it.target.schoolMatch == SchoolTargetMatch.SCHOOL_TARGET_FOUND
        } ?: firstEntries.firstOrNull { it.target.isSchoolTitleCandidate() }
        if (pageCandidate == null) continue

        onState(DevToolsDiscoveryState.TARGET_CANDIDATE_FOUND)
        if (pageCandidate.target.schoolMatch == SchoolTargetMatch.SCHOOL_TARGET_CANDIDATE_URL_UNKNOWN) {
            onState(DevToolsDiscoveryState.SCHOOL_TARGET_CANDIDATE_URL_UNKNOWN)
        }
        if (pageCandidate.target.schoolMatch == SchoolTargetMatch.SCHOOL_TARGET_FOUND) {
            onState(DevToolsDiscoveryState.HTTP_TARGET_URL_FOUND)
            onState(DevToolsDiscoveryState.SCHOOL_TARGET_FOUND)
            return result(
                DevToolsDiscoveryState.SCHOOL_TARGET_FOUND,
                candidates,
                attempts,
                targets,
                endpointUsed,
                cdpFailure
            )
        }

        if (pageCandidate.target.hasHttpUrl()) {
            onState(DevToolsDiscoveryState.HTTP_TARGET_URL_FOUND)
            continue
        }

        candidateUrlMissing = true
        onState(DevToolsDiscoveryState.HTTP_TARGET_URL_MISSING)
        var mergedCandidate = pageCandidate

        if (firstProbe.endpoint == DevToolsEndpoint.JSON_LIST) {
            val secondProbe = requestFromSocket(client, candidate, DevToolsEndpoint.JSON, onState)
            attempts += secondProbe.attempt
            openedAny = openedAny || secondProbe.attempt.opened
            val secondResponse = secondProbe.response
            if (secondResponse != null && secondResponse.statusCode in 200..299) {
                httpSucceeded = true
                endpointUsed = secondProbe.endpoint
                when (val secondParsed = parseResponse(secondResponse, secondProbe.endpoint, candidate, processes)) {
                    ParseResponse.Invalid -> {
                        targetListParseFailed = true
                        markLastAttemptFailure(attempts, "INVALID_JSON")
                        onState(DevToolsDiscoveryState.TARGET_LIST_PARSE_FAILED)
                    }
                    is ParseResponse.Valid -> {
                        targetListParsed = true
                        onState(DevToolsDiscoveryState.TARGET_LIST_PARSED)
                        val sameId = secondParsed.targets.firstOrNull {
                            it.target.id == pageCandidate.target.id
                        }
                        mergedCandidate = mergeTargetEndpointMetadata(pageCandidate, sameId)
                    }
                }
            }
        }

        targets.removeAll { it.socketName == candidate.socketName && it.id == mergedCandidate.target.id }
        targets += mergedCandidate.target

        if (mergedCandidate.target.hasHttpUrl()) {
            candidateUrlMissing = false
            onState(DevToolsDiscoveryState.HTTP_TARGET_URL_FOUND)
            if (mergedCandidate.target.schoolMatch == SchoolTargetMatch.SCHOOL_TARGET_FOUND) {
                onState(DevToolsDiscoveryState.SCHOOL_TARGET_FOUND)
                return result(
                    DevToolsDiscoveryState.SCHOOL_TARGET_FOUND,
                    candidates,
                    attempts,
                    targets,
                    endpointUsed,
                    cdpFailure
                )
            }
            continue
        }

        if (!mergedCandidate.target.isUrlMissing()) {
            candidateUrlMissing = true
            onState(DevToolsDiscoveryState.HTTP_TARGET_URL_MISSING)
            continue
        }

        onState(DevToolsDiscoveryState.HTTP_TARGET_URL_MISSING)
        val cdpEndpoint = mergedCandidate.cdpEndpoint
        if (cdpEndpoint == null) {
            cdpFailure = DevToolsCdpFailure.URL_MISSING
            continue
        }

        onState(DevToolsDiscoveryState.CDP_CONNECT_START)
        val stream = try {
            client.openDevToolsSocket(cdpEndpoint.socketName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            cdpFailure = DevToolsCdpFailure.IO_ERROR
            onState(DevToolsDiscoveryState.CDP_CONNECT_FAILED)
            continue
        }
        when (val connected = DevToolsCdpClient.connect(stream, cdpEndpoint.rawWebSocketUrl)) {
            is DevToolsCdpConnectResult.Failed -> {
                cdpFailure = connected.failure
                cdpHandshakeDiagnostic = connected.handshakeDiagnostic
                onState(DevToolsDiscoveryState.CDP_CONNECT_FAILED)
            }
            is DevToolsCdpConnectResult.Connected -> {
                cdpHandshakeDiagnostic = connected.handshakeDiagnostic
                onState(DevToolsDiscoveryState.CDP_CONNECT_SUCCESS)
                // Phase 3.2 diagnoses the handshake only; it must not send a CDP method.
                connected.client.close()
            }
        }
    }

    val finalState = when {
        targets.any { it.schoolMatch == SchoolTargetMatch.SCHOOL_HOST_TARGET_FOUND } ->
            DevToolsDiscoveryState.SCHOOL_HOST_TARGET_FOUND
        cdpFailure != null || candidateUrlMissing -> DevToolsDiscoveryState.HTTP_TARGET_URL_MISSING
        targetListParsed -> DevToolsDiscoveryState.DEVTOOLS_CONNECTED_NO_SCHOOL_TARGET
        httpSucceeded && targetListParseFailed -> DevToolsDiscoveryState.TARGET_LIST_PARSE_FAILED
        httpSucceeded -> DevToolsDiscoveryState.DEVTOOLS_HTTP_FAILED
        openedAny -> DevToolsDiscoveryState.DEVTOOLS_HTTP_FAILED
        else -> DevToolsDiscoveryState.DEVTOOLS_SOCKET_OPEN_FAILED
    }
    onState(finalState)
    return result(
        finalState,
        candidates,
        attempts,
        targets,
        endpointUsed,
        cdpFailure,
        cdpHandshakeDiagnostic
    )
}

private fun parseResponse(
    response: DevToolsHttpResponse,
    endpoint: DevToolsEndpoint,
    candidate: DevToolsSocketCandidate,
    processes: List<WeWorkProcess>
): ParseResponse = when (
    val parsed = parseDevToolsTargetList(
        response.body,
        candidate.socketName,
        processes,
        endpoint,
        response.statusCode
    )
) {
    DevToolsTargetParseResult.InvalidJson -> ParseResponse.Invalid
    is DevToolsTargetParseResult.Parsed -> ParseResponse.Valid(parsed.targets)
}

private fun displayableTargets(entries: List<ParsedDevToolsTarget>): List<DevToolsTarget> =
    entries.map { it.target }.filter { it.containerName == WEWORK_PACKAGE && it.type.equals("page", true) }

private fun DevToolsTarget.isSchoolTitleCandidate(): Boolean =
    containerName == WEWORK_PACKAGE &&
        type.equals("page", ignoreCase = true) &&
        title == SCHOOL_TITLE

private fun DevToolsTarget.isUrlMissing(): Boolean =
    host.isNullOrBlank() && endpointSummaries.isNotEmpty() && endpointSummaries.all {
        it.urlKind in setOf(
            TargetUrlValueKind.MISSING,
            TargetUrlValueKind.NULL,
            TargetUrlValueKind.EMPTY,
            TargetUrlValueKind.INVALID_URL,
            TargetUrlValueKind.OTHER_VALUE,
            TargetUrlValueKind.HTTP_URL,
            TargetUrlValueKind.HTTP_URL_LENIENT,
            TargetUrlValueKind.NON_HTTP_URL,
            TargetUrlValueKind.NON_HTTP_URL_LENIENT
        )
    }

private fun DevToolsTarget.hasHttpUrl(): Boolean =
    host != null && endpointSummaries.any { it.urlKind.isHttpUrl() }

private fun DevToolsTarget.withResolvedUrl(
    resolved: SanitizedTargetUrl,
    source: TargetUrlSource
): DevToolsTarget {
    val kind = if (
        resolved.scheme.equals("http", ignoreCase = true) ||
        resolved.scheme.equals("https", ignoreCase = true)
    ) {
        TargetUrlValueKind.HTTP_URL
    } else {
        TargetUrlValueKind.NON_HTTP_URL
    }
    return copy(
        displayUrl = resolved.displayUrl,
        host = resolved.host,
        path = resolved.path,
        pathMatchesSchoolPage = type.equals("page", true) && resolved.path == SCHOOL_PAGE_PATH,
        schoolMatch = classifySchoolTarget(
            resolved.host,
            title,
            type,
            containerName,
            kind,
            scheme = resolved.scheme,
            path = resolved.path
        ),
        urlSource = source
    )
}

private fun markLastAttemptFailure(attempts: MutableList<DevToolsSocketAttempt>, failure: String) {
    val index = attempts.lastIndex
    if (index >= 0) attempts[index] = attempts[index].copy(failure = failure, targetCount = null)
}

private fun result(
    state: DevToolsDiscoveryState,
    candidates: List<DevToolsSocketCandidate>,
    attempts: List<DevToolsSocketAttempt>,
    targets: List<DevToolsTarget>,
    endpointUsed: DevToolsEndpoint?,
    cdpFailure: DevToolsCdpFailure?,
    cdpHandshakeDiagnostic: DevToolsHandshakeDiagnostic? = null
): DevToolsTargetDiscoveryResult = DevToolsTargetDiscoveryResult(
    finalState = state,
    candidates = candidates,
    attempts = attempts,
    targets = targets.distinctBy { Triple(it.socketName, it.id, it.displayUrl) },
    endpointUsed = endpointUsed,
    cdpFailure = cdpFailure,
    cdpHandshakeDiagnostic = cdpHandshakeDiagnostic
)

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
            endpoint,
            null,
            DevToolsSocketAttempt(
                candidate.socketName,
                candidate.inode,
                endpoint.path,
                opened = false,
                httpStatus = null,
                targetCount = null,
                failure = exception.javaClass.simpleName.ifBlank { "SOCKET_OPEN_FAILED" }
            )
        )
    }

    onState(DevToolsDiscoveryState.DEVTOOLS_SOCKET_OPEN_SUCCESS)
    onState(DevToolsDiscoveryState.DEVTOOLS_HTTP_START)
    return when (val readResult = readDevToolsHttpResponse(stream, endpoint)) {
        is DevToolsHttpReadResult.Failed -> {
            onState(DevToolsDiscoveryState.DEVTOOLS_HTTP_FAILED)
            SocketProbe(
                endpoint,
                null,
                DevToolsSocketAttempt(
                    candidate.socketName,
                    candidate.inode,
                    endpoint.path,
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
                endpoint,
                readResult.response,
                DevToolsSocketAttempt(
                    candidate.socketName,
                    candidate.inode,
                    endpoint.path,
                    opened = true,
                    httpStatus = readResult.response.statusCode,
                    targetCount = null,
                    failure = if (readResult.response.statusCode in 200..299) {
                        null
                    } else {
                        "HTTP_" + readResult.response.statusCode
                    }
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

private sealed interface ParseResponse {
    data object Invalid : ParseResponse
    data class Valid(val targets: List<ParsedDevToolsTarget>) : ParseResponse
}

private const val MAX_DEVTOOLS_SOCKET_CANDIDATES = 24
