package com.burgess.schedule.school.sync.localadb

internal enum class DevToolsDiscoveryState {
    LOCAL_ADB_CONNECTED,
    DEVTOOLS_SOCKET_SCAN_START,
    DEVTOOLS_SOCKET_SCAN_SUCCESS,
    DEVTOOLS_SOCKET_SCAN_FAILED,
    DEVTOOLS_SOCKET_NOT_FOUND,
    DEVTOOLS_SOCKET_OPEN_START,
    DEVTOOLS_SOCKET_OPEN_SUCCESS,
    DEVTOOLS_SOCKET_OPEN_FAILED,
    DEVTOOLS_HTTP_START,
    DEVTOOLS_HTTP_SUCCESS,
    DEVTOOLS_HTTP_FAILED,
    TARGET_LIST_PARSE_FAILED,
    TARGET_LIST_PARSED,
    TARGET_CANDIDATE_FOUND,
    SCHOOL_TARGET_CANDIDATE_URL_UNKNOWN,
    HTTP_TARGET_URL_FOUND,
    HTTP_TARGET_URL_MISSING,
    CDP_CONNECT_START,
    CDP_CONNECT_SUCCESS,
    CDP_CONNECT_FAILED,
    CDP_FRAME_TREE_SUCCESS,
    CDP_NAVIGATION_HISTORY_SUCCESS,
    TARGET_URL_RESOLVED,
    SCHOOL_TARGET_FOUND,
    SCHOOL_HOST_TARGET_FOUND,
    DEVTOOLS_CONNECTED_NO_SCHOOL_TARGET
}

internal enum class DevToolsEndpoint(val path: String) {
    JSON_LIST("/json/list"),
    JSON("/json")
}

internal enum class TargetUrlValueKind {
    MISSING,
    NULL,
    EMPTY,
    HTTP_URL,
    HTTP_URL_LENIENT,
    NON_HTTP_URL,
    NON_HTTP_URL_LENIENT,
    INVALID_URL,
    OTHER_VALUE
}

internal enum class TargetDescriptionKind {
    MISSING,
    NULL,
    EMPTY,
    JSON_OBJECT,
    JSON_ARRAY,
    NON_JSON,
    OTHER_VALUE
}

internal data class DevToolsFieldSummary(
    val name: String,
    val kind: String,
    val stringLength: Int?
)

internal data class DevToolsEndpointSummary(
    val endpoint: DevToolsEndpoint,
    val httpStatus: Int,
    val targetId: String,
    val type: String,
    val title: String,
    val urlKind: TargetUrlValueKind,
    val urlStringLength: Int?,
    val strictUrlParseFailureType: String?,
    val strictUrlParseFailureIndex: Int?,
    val displayUrl: String,
    val hasWebSocketDebuggerUrl: Boolean,
    val webSocketUrlStringLength: Int?,
    val fields: List<DevToolsFieldSummary>,
    val descriptionKind: TargetDescriptionKind,
    val descriptionKeys: List<String>
)

internal enum class TargetUrlSource {
    HTTP_JSON_LIST,
    HTTP_JSON,
    HTTP_JSON_LIST_LENIENT,
    HTTP_JSON_LENIENT,
    CDP_PAGE_FRAME_TREE,
    CDP_NAVIGATION_HISTORY
}

internal enum class SchoolTargetMatch {
    NONE,
    SCHOOL_TARGET_CANDIDATE_URL_UNKNOWN,
    SCHOOL_HOST_TARGET_FOUND,
    SCHOOL_TARGET_FOUND
}

internal data class SanitizedTargetUrl(
    val displayUrl: String,
    val scheme: String,
    val host: String?,
    val path: String?
)

internal data class DevToolsTarget(
    val id: String,
    val type: String,
    val title: String,
    val displayUrl: String,
    val host: String?,
    val path: String?,
    val socketName: String,
    val processName: String?,
    val containerName: String?,
    val hasWebSocketDebuggerUrl: Boolean,
    val pathMatchesSchoolPage: Boolean,
    val schoolMatch: SchoolTargetMatch,
    val urlSource: TargetUrlSource?,
    val endpointSummaries: List<DevToolsEndpointSummary>
)

/** The raw WebSocket URL is retained only in memory for the one approved read-only CDP probe. */
internal class DevToolsCdpEndpoint(
    val targetId: String,
    val socketName: String,
    internal val rawWebSocketUrl: String
) {
    override fun toString(): String =
        "DevToolsCdpEndpoint(targetId=$targetId, socketName=$socketName, rawWebSocketUrl=<redacted>)"
}

internal data class ParsedDevToolsTarget(
    val target: DevToolsTarget,
    val cdpEndpoint: DevToolsCdpEndpoint?
)

internal fun classifySchoolTarget(
    host: String?,
    title: String,
    type: String = "page",
    containerName: String?,
    urlKind: TargetUrlValueKind,
    scheme: String? = null,
    path: String? = null
): SchoolTargetMatch {
    if (!type.equals("page", ignoreCase = true)) return SchoolTargetMatch.NONE
    if (containerName != WEWORK_PACKAGE) return SchoolTargetMatch.NONE
    if (!urlKind.isHttpUrl()) {
        return if (
            title == SCHOOL_TITLE && urlKind in setOf(
                TargetUrlValueKind.MISSING,
                TargetUrlValueKind.NULL,
                TargetUrlValueKind.EMPTY,
                TargetUrlValueKind.INVALID_URL,
                TargetUrlValueKind.OTHER_VALUE
            )
        ) {
            SchoolTargetMatch.SCHOOL_TARGET_CANDIDATE_URL_UNKNOWN
        } else {
            SchoolTargetMatch.NONE
        }
    }
    val isSchoolHttpsUrl =
        scheme.equals("https", ignoreCase = true) &&
            host.equals(SCHOOL_HOST, ignoreCase = true)
    if (title != SCHOOL_TITLE) {
        return if (isSchoolHttpsUrl) {
            SchoolTargetMatch.SCHOOL_HOST_TARGET_FOUND
        } else {
            SchoolTargetMatch.NONE
        }
    }
    val isKnownSchoolPath = path == SCHOOL_PAGE_PATH || path?.startsWith(SCHOOL_H5_PATH_PREFIX) == true
    return if (isSchoolHttpsUrl && isKnownSchoolPath) {
        SchoolTargetMatch.SCHOOL_TARGET_FOUND
    } else {
        SchoolTargetMatch.NONE
    }
}

internal fun sanitizeDevToolsTargetUrl(value: String): SanitizedTargetUrl? =
    TargetUrlNormalizer.normalize(value).url

internal fun TargetUrlValueKind.isHttpUrl(): Boolean =
    this == TargetUrlValueKind.HTTP_URL || this == TargetUrlValueKind.HTTP_URL_LENIENT

internal fun TargetUrlValueKind.isAnyValidUrl(): Boolean =
    isHttpUrl() || this == TargetUrlValueKind.NON_HTTP_URL || this == TargetUrlValueKind.NON_HTTP_URL_LENIENT

internal const val WEWORK_PACKAGE = "com.tencent.wework"
internal const val SCHOOL_HOST = "testjwgl.sdbi.edu.cn"
internal const val SCHOOL_TITLE = "我的课表"
internal const val SCHOOL_PAGE_PATH = "/h5/index.html"
internal const val SCHOOL_H5_PATH_PREFIX = "/h5/"
