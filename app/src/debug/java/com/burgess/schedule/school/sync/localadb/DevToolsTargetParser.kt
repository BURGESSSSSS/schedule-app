package com.burgess.schedule.school.sync.localadb

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal sealed interface DevToolsTargetParseResult {
    data class Parsed(val targets: List<DevToolsTarget>) : DevToolsTargetParseResult
    data object InvalidJson : DevToolsTargetParseResult
}

internal fun parseDevToolsTargetList(
    json: String,
    socketName: String,
    processes: List<WeWorkProcess>
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
    val containerName = process?.processName?.substringBefore(':')
    val targets = buildList {
        for (index in 0 until array.length()) {
            val target = array.optJSONObject(index) ?: continue
            val id = target.safeString("id").take(MAX_TARGET_ID_CHARS)
            val type = target.safeString("type").take(MAX_TARGET_TYPE_CHARS)
            val title = target.safeString("title").take(MAX_TARGET_TITLE_CHARS)
            val sanitizedUrl = sanitizeDevToolsTargetUrl(target.safeString("url"))
            val host = sanitizedUrl?.host
            add(
                DevToolsTarget(
                    id = id,
                    type = type,
                    title = title,
                    displayUrl = sanitizedUrl?.displayUrl.orEmpty(),
                    host = host,
                    path = sanitizedUrl?.path,
                    socketName = socketName,
                    processName = process?.processName,
                    containerName = containerName,
                    hasWebSocketDebuggerUrl = target.safeString("webSocketDebuggerUrl").isNotBlank(),
                    pathMatchesSchoolPage = type.equals("page", ignoreCase = true) &&
                        sanitizedUrl?.path == SCHOOL_PAGE_PATH,
                    schoolMatch = classifySchoolTarget(host, title, type)
                )
            )
        }
    }
    return DevToolsTargetParseResult.Parsed(targets)
}

private fun JSONObject.safeString(name: String): String =
    if (!has(name) || isNull(name)) "" else optString(name, "")

private const val MAX_TARGET_JSON_CHARS = 512 * 1024
private const val MAX_TARGET_ID_CHARS = 256
private const val MAX_TARGET_TYPE_CHARS = 80
private const val MAX_TARGET_TITLE_CHARS = 512
private const val SCHOOL_PAGE_PATH = "/h5/index.html"
