package com.burgess.schedule.school.sync.localadb

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal sealed interface DevToolsTargetParseResult {
    data class Parsed(val targets: List<ParsedDevToolsTarget>) : DevToolsTargetParseResult
    data object InvalidJson : DevToolsTargetParseResult
}

internal fun parseDevToolsTargetList(
    json: String,
    socketName: String,
    processes: List<WeWorkProcess>,
    endpoint: DevToolsEndpoint = DevToolsEndpoint.JSON_LIST,
    httpStatus: Int = 200
): DevToolsTargetParseResult {
    if (json.length > MAX_TARGET_JSON_CHARS) return DevToolsTargetParseResult.InvalidJson
    val array = try {
        JSONArray(json)
    } catch (_: JSONException) {
        return DevToolsTargetParseResult.InvalidJson
    } catch (_: RuntimeException) {
        return DevToolsTargetParseResult.InvalidJson
    }

    val process = pidStyleSuffix(socketName)?.let { socketPid ->
        processes.firstOrNull { it.pid == socketPid }
    }
    val processName = process?.processName
    val containerName = processName?.substringBefore(':')
    val targets = buildList {
        for (index in 0 until array.length()) {
            val target = array.optJSONObject(index) ?: continue
            val id = target.safeString("id").take(MAX_TARGET_ID_CHARS)
            val type = target.safeString("type").take(MAX_TARGET_TYPE_CHARS)
            val title = target.safeString("title").take(MAX_TARGET_TITLE_CHARS)
            val urlField = target.fieldSummary("url")
            val rawUrl = target.safeString("url")
            val urlNormalization = TargetUrlNormalizer.normalize(rawUrl)
            val sanitizedUrl = urlNormalization.url
            val urlKind = classifyUrlValue(target, urlField, urlNormalization)
            val description = target.descriptionSummary()
            val fields = TARGET_DIAGNOSTIC_KEYS.map(target::fieldSummary)
            val websocketField = target.fieldSummary("webSocketDebuggerUrl")
            val rawWebSocketUrl = target.safeString("webSocketDebuggerUrl")
                .takeIf { websocketField.kind == FIELD_STRING && it.length <= MAX_WEBSOCKET_URL_CHARS }
            val cdpEndpoint = rawWebSocketUrl
                ?.takeIf(::isSupportedWebSocketUrl)
                ?.let { DevToolsCdpEndpoint(id, socketName, it) }
            val match = classifySchoolTarget(
                host = sanitizedUrl?.host,
                title = title,
                type = type,
                containerName = containerName,
                urlKind = urlKind,
                scheme = sanitizedUrl?.scheme,
                path = sanitizedUrl?.path
            )
            val endpointSummary = DevToolsEndpointSummary(
                endpoint = endpoint,
                httpStatus = httpStatus,
                targetId = id,
                type = type,
                title = title,
                urlKind = urlKind,
                urlStringLength = urlField.stringLength,
                strictUrlParseFailureType = urlNormalization.strictParseFailureType,
                strictUrlParseFailureIndex = urlNormalization.strictParseFailureIndex,
                displayUrl = sanitizedUrl?.displayUrl.orEmpty(),
                hasWebSocketDebuggerUrl = websocketField.kind == FIELD_STRING,
                webSocketUrlStringLength = websocketField.stringLength,
                fields = fields,
                descriptionKind = description.kind,
                descriptionKeys = description.keys
            )
            add(
                ParsedDevToolsTarget(
                    target = DevToolsTarget(
                        id = id,
                        type = type,
                        title = title,
                        displayUrl = sanitizedUrl?.displayUrl.orEmpty(),
                        host = sanitizedUrl?.host,
                        path = sanitizedUrl?.path,
                        socketName = socketName,
                        processName = processName,
                        containerName = containerName,
                        hasWebSocketDebuggerUrl = rawWebSocketUrl != null,
                        pathMatchesSchoolPage = type.equals("page", ignoreCase = true) &&
                            sanitizedUrl?.path == SCHOOL_PAGE_PATH,
                        schoolMatch = match,
                        urlSource = endpoint.toTargetUrlSource(urlKind).takeIf { urlKind.isAnyValidUrl() },
                        endpointSummaries = listOf(endpointSummary)
                    ),
                    cdpEndpoint = cdpEndpoint
                )
            )
        }
    }
    return DevToolsTargetParseResult.Parsed(targets)
}

internal fun mergeTargetEndpointMetadata(
    preferred: ParsedDevToolsTarget,
    additional: ParsedDevToolsTarget?
): ParsedDevToolsTarget {
    if (additional == null || additional.target.id != preferred.target.id) return preferred

    val primaryTarget = preferred.target
    val otherTarget = additional.target
    val selected = when {
        primaryTarget.host != null && primaryTarget.endpointSummaries.any {
            it.urlKind == TargetUrlValueKind.HTTP_URL
        } -> primaryTarget
        otherTarget.host != null && otherTarget.endpointSummaries.any {
            it.urlKind == TargetUrlValueKind.HTTP_URL
        } -> otherTarget
        primaryTarget.host != null && primaryTarget.endpointSummaries.any {
            it.urlKind == TargetUrlValueKind.HTTP_URL_LENIENT
        } -> primaryTarget
        otherTarget.host != null && otherTarget.endpointSummaries.any {
            it.urlKind == TargetUrlValueKind.HTTP_URL_LENIENT
        } -> otherTarget
        primaryTarget.host != null && primaryTarget.urlSource != null -> primaryTarget
        otherTarget.host != null && otherTarget.urlSource != null -> otherTarget
        primaryTarget.urlSource != null -> primaryTarget
        otherTarget.urlSource != null -> otherTarget
        else -> primaryTarget
    }
    val endpointSummaries = (primaryTarget.endpointSummaries + otherTarget.endpointSummaries)
        .distinctBy { it.endpoint }
    val chosenMatch = when {
        selected.urlSource != null -> selected.schoolMatch
        selected.schoolMatch == SchoolTargetMatch.SCHOOL_TARGET_FOUND -> selected.schoolMatch
        selected.schoolMatch == SchoolTargetMatch.SCHOOL_HOST_TARGET_FOUND -> selected.schoolMatch
        primaryTarget.schoolMatch == SchoolTargetMatch.SCHOOL_TARGET_CANDIDATE_URL_UNKNOWN ||
            otherTarget.schoolMatch == SchoolTargetMatch.SCHOOL_TARGET_CANDIDATE_URL_UNKNOWN ->
            SchoolTargetMatch.SCHOOL_TARGET_CANDIDATE_URL_UNKNOWN
        else -> SchoolTargetMatch.NONE
    }
    val mergedTarget = selected.copy(
        id = primaryTarget.id,
        type = primaryTarget.type,
        title = primaryTarget.title,
        hasWebSocketDebuggerUrl = primaryTarget.hasWebSocketDebuggerUrl || otherTarget.hasWebSocketDebuggerUrl,
        processName = primaryTarget.processName ?: otherTarget.processName,
        containerName = primaryTarget.containerName ?: otherTarget.containerName,
        schoolMatch = chosenMatch,
        endpointSummaries = endpointSummaries
    )
    return ParsedDevToolsTarget(
        target = mergedTarget,
        cdpEndpoint = preferred.cdpEndpoint ?: additional.cdpEndpoint
    )
}

internal fun classifyUrlValue(
    target: JSONObject,
    field: DevToolsFieldSummary,
    normalization: TargetUrlNormalizationResult
): TargetUrlValueKind = when {
    !target.has("url") -> TargetUrlValueKind.MISSING
    target.isNull("url") -> TargetUrlValueKind.NULL
    field.kind == FIELD_OTHER -> TargetUrlValueKind.OTHER_VALUE
    field.kind == FIELD_EMPTY -> TargetUrlValueKind.EMPTY
    normalization.url == null -> TargetUrlValueKind.INVALID_URL
    normalization.url.scheme.equals("http", ignoreCase = true) ||
        normalization.url.scheme.equals("https", ignoreCase = true) ->
        if (normalization.mode == TargetUrlNormalizationMode.LENIENT_BASE) {
            TargetUrlValueKind.HTTP_URL_LENIENT
        } else {
            TargetUrlValueKind.HTTP_URL
        }
    normalization.mode == TargetUrlNormalizationMode.LENIENT_BASE -> TargetUrlValueKind.NON_HTTP_URL_LENIENT
    else -> TargetUrlValueKind.NON_HTTP_URL
}

private fun isSupportedWebSocketUrl(value: String): Boolean =
    runCatching {
        val uri = java.net.URI(value)
        (uri.scheme.equals("ws", ignoreCase = true) || uri.scheme.equals("wss", ignoreCase = true)) &&
            !uri.host.isNullOrBlank() &&
            !uri.rawPath.isNullOrBlank()
    }.getOrDefault(false)

private data class DescriptionSummary(
    val kind: TargetDescriptionKind,
    val keys: List<String>
)

private fun JSONObject.descriptionSummary(): DescriptionSummary {
    if (!has("description")) return DescriptionSummary(TargetDescriptionKind.MISSING, emptyList())
    if (isNull("description")) return DescriptionSummary(TargetDescriptionKind.NULL, emptyList())
    val value = opt("description")
    if (value !is String) return DescriptionSummary(TargetDescriptionKind.OTHER_VALUE, emptyList())
    if (value.isEmpty()) return DescriptionSummary(TargetDescriptionKind.EMPTY, emptyList())

    val objectDescription = try {
        JSONObject(value)
    } catch (_: JSONException) {
        null
    } catch (_: RuntimeException) {
        null
    }
    if (objectDescription != null) {
        val keys = objectDescription.keys().asSequence()
            .map { it.take(MAX_DESCRIPTION_KEY_CHARS) }
            .sorted()
            .take(MAX_DESCRIPTION_KEYS)
            .toList()
        return DescriptionSummary(TargetDescriptionKind.JSON_OBJECT, keys)
    }
    val arrayDescription = try {
        JSONArray(value)
    } catch (_: JSONException) {
        null
    } catch (_: RuntimeException) {
        null
    }
    return DescriptionSummary(
        if (arrayDescription != null) TargetDescriptionKind.JSON_ARRAY else TargetDescriptionKind.NON_JSON,
        emptyList()
    )
}

private fun JSONObject.fieldSummary(name: String): DevToolsFieldSummary {
    if (!has(name)) return DevToolsFieldSummary(name, FIELD_MISSING, null)
    if (isNull(name)) return DevToolsFieldSummary(name, FIELD_NULL, null)
    val value = opt(name)
    if (value !is String) return DevToolsFieldSummary(name, FIELD_OTHER, null)
    return DevToolsFieldSummary(name, if (value.isEmpty()) FIELD_EMPTY else FIELD_STRING, value.length)
}

private fun JSONObject.safeString(name: String): String =
    if (!has(name) || isNull(name)) "" else optString(name, "")

private fun DevToolsEndpoint.toTargetUrlSource(urlKind: TargetUrlValueKind): TargetUrlSource = when {
    urlKind == TargetUrlValueKind.HTTP_URL_LENIENT || urlKind == TargetUrlValueKind.NON_HTTP_URL_LENIENT ->
        when (this) {
            DevToolsEndpoint.JSON_LIST -> TargetUrlSource.HTTP_JSON_LIST_LENIENT
            DevToolsEndpoint.JSON -> TargetUrlSource.HTTP_JSON_LENIENT
        }
    this == DevToolsEndpoint.JSON_LIST -> TargetUrlSource.HTTP_JSON_LIST
    else -> TargetUrlSource.HTTP_JSON
}

private const val MAX_TARGET_JSON_CHARS = 512 * 1024
private const val MAX_TARGET_ID_CHARS = 256
private const val MAX_TARGET_TYPE_CHARS = 80
private const val MAX_TARGET_TITLE_CHARS = 512
private const val MAX_WEBSOCKET_URL_CHARS = 4_096
private const val MAX_DESCRIPTION_KEY_CHARS = 80
private const val MAX_DESCRIPTION_KEYS = 64
private const val FIELD_MISSING = "missing"
private const val FIELD_NULL = "null"
private const val FIELD_EMPTY = "empty"
private const val FIELD_STRING = "string"
private const val FIELD_OTHER = "other"
private val TARGET_DIAGNOSTIC_KEYS = listOf(
    "id",
    "type",
    "title",
    "url",
    "description",
    "devtoolsFrontendUrl",
    "webSocketDebuggerUrl",
    "faviconUrl"
)
