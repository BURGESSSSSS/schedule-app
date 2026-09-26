package com.burgess.schedule.school.sync.localadb

import com.burgess.schedule.school.sync.importpreview.SchoolScheduleSnapshot

internal enum class CourseBodyState {
    WAITING_FOR_RESPONSE,
    WAITING_FOR_BODY,
    BODY_FETCH_START,
    COURSE_JSON_CONFIRMED,
    BODY_JSON_UNCONFIRMED,
    BODY_NOT_AVAILABLE,
    BODY_REQUEST_ID_INVALID,
    BODY_CDP_ERROR,
    BODY_DECODE_ERROR,
    BODY_NOT_JSON,
    BODY_TOO_LARGE,
    HTTP_NOT_OK,
    RESPONSE_MISSING,
    REQUEST_FAILED
}

internal data class CourseRequestSummary(
    val number: Int,
    internal val requestId: String,
    val method: String,
    val host: String,
    val path: String,
    val status: Int?,
    val mimeType: String?,
    val resourceType: String?,
    val bodyState: CourseBodyState,
    val payload: CoursePayloadSummary? = null,
    val scheduleSnapshot: SchoolScheduleSnapshot? = null,
    val cdpErrorCode: Int? = null,
    val cdpErrorMessage: String? = null
) {
    override fun toString(): String =
        "CourseRequestSummary(number=$number, requestId=<redacted>, path=$path, bodyState=$bodyState)"
}

internal data class CourseCaptureSnapshot(
    val schoolRequestCount: Int,
    val recentRequests: List<CourseRequestSummary>
)

internal sealed interface CourseCaptureAction {
    data object None : CourseCaptureAction
    data class FetchBody(val requestId: String) : CourseCaptureAction
}

/** Tracks only matching request IDs and sanitized metadata; response bodies are never retained. */
internal class CourseResponseCapture {
    private val records = LinkedHashMap<String, MutableCourseRequest>()
    private var nextNumber = 1
    private var schoolRequestCount = 0

    fun hasTrackedRequest(requestId: String): Boolean = requestId in records

    fun onEvent(event: CdpNetworkEvent): CourseCaptureAction {
        return when (event) {
        is CdpNetworkEvent.RequestWillBeSent -> {
            if (CourseRequestMatcher.isSchoolHost(event.url)) schoolRequestCount++
            if (!CourseRequestMatcher.isCourseSchedule(event.url)) return CourseCaptureAction.None
            val url = event.url ?: return CourseCaptureAction.None
            val existing = records[event.requestId]
            if (existing == null) {
                records[event.requestId] = MutableCourseRequest(
                    number = nextNumber++,
                    requestId = event.requestId,
                    method = event.method.ifBlank { "UNKNOWN" },
                    host = url.host.orEmpty(),
                    path = url.path.orEmpty()
                )
                trimRecords()
            } else {
                existing.method = event.method.ifBlank { existing.method }
                existing.host = url.host.orEmpty()
                existing.path = url.path.orEmpty()
            }
            CourseCaptureAction.None
        }
        is CdpNetworkEvent.ResponseReceived -> {
            records[event.requestId]?.let { record ->
                record.status = event.status
                record.mimeType = event.mimeType
                record.resourceType = event.resourceType
            }
            CourseCaptureAction.None
        }
        is CdpNetworkEvent.LoadingFinished -> {
            val record = records[event.requestId] ?: return CourseCaptureAction.None
            if (record.bodyState != CourseBodyState.WAITING_FOR_RESPONSE) return CourseCaptureAction.None
            when (record.status) {
                200 -> {
                    record.bodyState = CourseBodyState.BODY_FETCH_START
                    CourseCaptureAction.FetchBody(event.requestId)
                }
                null -> {
                    record.bodyState = CourseBodyState.RESPONSE_MISSING
                    CourseCaptureAction.None
                }
                else -> {
                    record.bodyState = CourseBodyState.HTTP_NOT_OK
                    CourseCaptureAction.None
                }
            }
        }
        is CdpNetworkEvent.LoadingFailed -> {
            records[event.requestId]?.bodyState = CourseBodyState.REQUEST_FAILED
            CourseCaptureAction.None
        }
        }
    }

    fun recordBodyResult(requestId: String, result: CourseBodyParseResult) {
        val record = records[requestId] ?: return
        when (result) {
            is CourseBodyParseResult.Confirmed -> {
                record.bodyState = CourseBodyState.COURSE_JSON_CONFIRMED
                record.payload = result.summary
                record.scheduleSnapshot = result.scheduleSnapshot
            }
            is CourseBodyParseResult.JsonUnconfirmed -> {
                record.bodyState = CourseBodyState.BODY_JSON_UNCONFIRMED
                record.payload = result.summary
            }
            CourseBodyParseResult.NotJson -> record.bodyState = CourseBodyState.BODY_NOT_JSON
            CourseBodyParseResult.DecodeError -> record.bodyState = CourseBodyState.BODY_DECODE_ERROR
            CourseBodyParseResult.TooLarge -> record.bodyState = CourseBodyState.BODY_TOO_LARGE
        }
    }

    fun recordBodyFailure(
        requestId: String,
        state: CourseBodyState,
        code: Int? = null,
        message: String? = null
    ) {
        val record = records[requestId] ?: return
        record.bodyState = state
        record.cdpErrorCode = code
        record.cdpErrorMessage = message?.take(160)
    }

    fun snapshot(): CourseCaptureSnapshot = CourseCaptureSnapshot(
        schoolRequestCount = schoolRequestCount,
        recentRequests = records.values.toList().takeLast(MAX_VISIBLE_COURSE_REQUESTS).map(MutableCourseRequest::toSummary)
    )

    private fun trimRecords() {
        while (records.size > MAX_TRACKED_COURSE_REQUESTS) {
            val completed = records.entries.firstOrNull {
                it.value.bodyState != CourseBodyState.WAITING_FOR_RESPONSE &&
                    it.value.bodyState != CourseBodyState.BODY_FETCH_START
            }
            records.remove(completed?.key ?: records.keys.first())
        }
    }

    private data class MutableCourseRequest(
        val number: Int,
        val requestId: String,
        var method: String,
        var host: String,
        var path: String,
        var status: Int? = null,
        var mimeType: String? = null,
        var resourceType: String? = null,
        var bodyState: CourseBodyState = CourseBodyState.WAITING_FOR_RESPONSE,
        var payload: CoursePayloadSummary? = null,
        var scheduleSnapshot: SchoolScheduleSnapshot? = null,
        var cdpErrorCode: Int? = null,
        var cdpErrorMessage: String? = null
    ) {
        fun toSummary() = CourseRequestSummary(
            number = number,
            requestId = requestId,
            method = method,
            host = host,
            path = path,
            status = status,
            mimeType = mimeType,
            resourceType = resourceType,
            bodyState = bodyState,
            payload = payload,
            scheduleSnapshot = scheduleSnapshot,
            cdpErrorCode = cdpErrorCode,
            cdpErrorMessage = cdpErrorMessage
        )
    }

    private companion object {
        const val MAX_TRACKED_COURSE_REQUESTS = 32
        const val MAX_VISIBLE_COURSE_REQUESTS = 5
    }
}
