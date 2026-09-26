package com.burgess.schedule.school.sync.localadb

import org.json.JSONObject

/** Only sanitized request metadata crosses from raw CDP messages into the observer. */
internal sealed interface CdpNetworkEvent {
    val requestId: String

    data class RequestWillBeSent(
        override val requestId: String,
        val method: String,
        val url: SanitizedTargetUrl?
    ) : CdpNetworkEvent

    data class ResponseReceived(
        override val requestId: String,
        val status: Int?,
        val mimeType: String?,
        val resourceType: String?,
        val url: SanitizedTargetUrl?
    ) : CdpNetworkEvent

    data class LoadingFinished(override val requestId: String) : CdpNetworkEvent

    data class LoadingFailed(override val requestId: String) : CdpNetworkEvent
}

internal sealed interface CdpNetworkEventReadResult {
    data class Event(val event: CdpNetworkEvent) : CdpNetworkEventReadResult
    data class Closed(val failure: DevToolsCdpFailure) : CdpNetworkEventReadResult
}

internal fun parseCdpNetworkEvent(json: JSONObject): CdpNetworkEvent? {
    val method = json.optString("method")
    if (!method.startsWith("Network.")) return null
    val params = json.optJSONObject("params") ?: return null
    val requestId = params.optString("requestId").takeIf(String::isNotBlank) ?: return null

    return when (method) {
        "Network.requestWillBeSent" -> {
            val request = params.optJSONObject("request") ?: return null
            CdpNetworkEvent.RequestWillBeSent(
                requestId = requestId,
                method = request.optString("method").take(16),
                url = sanitizeDevToolsTargetUrl(request.optString("url"))
            )
        }
        "Network.responseReceived" -> {
            val response = params.optJSONObject("response") ?: return null
            CdpNetworkEvent.ResponseReceived(
                requestId = requestId,
                status = (response.opt("status") as? Number)?.toInt(),
                mimeType = response.optString("mimeType").takeIf(String::isNotBlank)?.take(80),
                resourceType = params.optString("type").takeIf(String::isNotBlank)?.take(32),
                url = sanitizeDevToolsTargetUrl(response.optString("url"))
            )
        }
        "Network.loadingFinished" -> CdpNetworkEvent.LoadingFinished(requestId)
        "Network.loadingFailed" -> CdpNetworkEvent.LoadingFailed(requestId)
        else -> null
    }
}

internal object CourseRequestMatcher {
    fun isSchoolHost(url: SanitizedTargetUrl?): Boolean =
        url?.host.equals(SCHOOL_HOST, ignoreCase = true)

    fun isCourseSchedule(url: SanitizedTargetUrl?): Boolean =
        isSchoolHost(url) && url?.path == COURSE_SCHEDULE_PATH
}

internal const val COURSE_SCHEDULE_PATH = "/wap/mycourseschedule.action"
