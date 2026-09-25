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
    SCHOOL_TARGET_FOUND,
    SCHOOL_HOST_TARGET_FOUND,
    DEVTOOLS_CONNECTED_NO_SCHOOL_TARGET
}

internal enum class DevToolsEndpoint(val path: String) {
    JSON_LIST("/json/list"),
    JSON("/json")
}

internal enum class SchoolTargetMatch {
    NONE,
    SCHOOL_HOST_TARGET_FOUND,
    SCHOOL_TARGET_FOUND
}

internal data class SanitizedTargetUrl(
    val displayUrl: String,
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
    val schoolMatch: SchoolTargetMatch
)

internal fun classifySchoolTarget(host: String?, title: String, type: String = "page"): SchoolTargetMatch {
    if (!type.equals("page", ignoreCase = true)) return SchoolTargetMatch.NONE
    if (!host.equals(SCHOOL_HOST, ignoreCase = true)) return SchoolTargetMatch.NONE
    return if (title == SCHOOL_TITLE) {
        SchoolTargetMatch.SCHOOL_TARGET_FOUND
    } else {
        SchoolTargetMatch.SCHOOL_HOST_TARGET_FOUND
    }
}

internal fun sanitizeDevToolsTargetUrl(value: String): SanitizedTargetUrl? {
    if (value.isBlank() || value.length > MAX_TARGET_URL_CHARS) return null
    return runCatching {
        val uri = java.net.URI(value)
        val scheme = uri.scheme?.takeIf(String::isNotBlank) ?: return null
        val host = uri.host?.lowercase(java.util.Locale.ROOT)
        if (host != null) {
            val path = uri.rawPath.orEmpty().ifBlank { "/" }
            SanitizedTargetUrl("$scheme://$host$path", host, path)
        } else if (uri.isOpaque) {
            val safePart = uri.rawSchemeSpecificPart.orEmpty()
                .substringBefore('?')
                .substringBefore('#')
            SanitizedTargetUrl("$scheme:$safePart", null, safePart)
        } else {
            val path = uri.rawPath.orEmpty()
            SanitizedTargetUrl("$scheme:$path", null, path)
        }
    }.getOrNull()
}

private const val SCHOOL_HOST = "testjwgl.sdbi.edu.cn"
private const val SCHOOL_TITLE = "我的课表"
private const val MAX_TARGET_URL_CHARS = 8_192
