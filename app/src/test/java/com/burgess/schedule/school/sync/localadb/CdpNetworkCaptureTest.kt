package com.burgess.schedule.school.sync.localadb

import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CdpNetworkCaptureTest {
    @Test
    fun requestMatcherIgnoresOtherHostsAndPathsAndMatchesOnlyCourseEndpoint() {
        val nonSchool = requestEvent("1", "https://example.com/wap/mycourseschedule.action")
        val otherSchoolPath = requestEvent("2", "https://testjwgl.sdbi.edu.cn/wap/other.action")
        val course = requestEvent(
            "3",
            "https://testjwgl.sdbi.edu.cn/wap/mycourseschedule.action?ticket=never-retained"
        )

        assertFalse(CourseRequestMatcher.isSchoolHost((nonSchool as CdpNetworkEvent.RequestWillBeSent).url))
        assertTrue(CourseRequestMatcher.isSchoolHost((otherSchoolPath as CdpNetworkEvent.RequestWillBeSent).url))
        assertFalse(CourseRequestMatcher.isCourseSchedule(otherSchoolPath.url))
        assertTrue(CourseRequestMatcher.isCourseSchedule((course as CdpNetworkEvent.RequestWillBeSent).url))
        assertEquals("/wap/mycourseschedule.action", course.url?.path)
        assertFalse(course.url?.displayUrl.orEmpty().contains("ticket"))
        assertFalse(course.toString().contains("never-retained"))
    }

    @Test
    fun requestParserNeverCarriesRequestHeadersIntoEventModel() {
        val event = parseCdpNetworkEvent(
            JSONObject(
                """{"method":"Network.requestWillBeSent","params":{"requestId":"r1","request":{"url":"https://testjwgl.sdbi.edu.cn/wap/mycourseschedule.action?secret=x","method":"GET","headers":{"Cookie":"private","Authorization":"private"}}}}"""
            )
        ) as CdpNetworkEvent.RequestWillBeSent

        assertEquals("GET", event.method)
        assertEquals("/wap/mycourseschedule.action", event.url?.path)
        assertFalse(event.toString().contains("Cookie"))
        assertFalse(event.toString().contains("Authorization"))
        assertFalse(event.toString().contains("secret"))
    }

    @Test
    fun responseCorrelationIsPerRequestIdAndOnlySuccessfulFinishedRequestFetchesBody() {
        val capture = CourseResponseCapture()
        capture.onEvent(requestEvent("r1", courseUrl()))
        capture.onEvent(requestEvent("r2", courseUrl()))
        capture.onEvent(responseEvent("r2", 500))
        capture.onEvent(responseEvent("r1", 200))

        assertEquals(CourseCaptureAction.None, capture.onEvent(finishedEvent("r2")))
        assertEquals(CourseCaptureAction.FetchBody("r1"), capture.onEvent(finishedEvent("r1")))

        val requests = capture.snapshot().recentRequests.associateBy { it.requestId }
        assertEquals(CourseBodyState.HTTP_NOT_OK, requests.getValue("r2").bodyState)
        assertEquals(CourseBodyState.BODY_FETCH_START, requests.getValue("r1").bodyState)
        assertEquals(2, capture.snapshot().schoolRequestCount)
    }

    @Test
    fun responseMetadataAndLoadingFailureAreAssociatedOnlyWithMatchingRequest() {
        val capture = CourseResponseCapture()
        capture.onEvent(requestEvent("r1", courseUrl()))
        capture.onEvent(
            CdpNetworkEvent.ResponseReceived(
                requestId = "r1",
                status = 200,
                mimeType = "application/json",
                resourceType = "XHR",
                url = TargetUrlNormalizer.normalize(courseUrl()).url
            )
        )
        capture.onEvent(CdpNetworkEvent.LoadingFailed("unknown"))
        val response = capture.snapshot().recentRequests.single()
        assertEquals(200, response.status)
        assertEquals("application/json", response.mimeType)
        assertEquals("XHR", response.resourceType)

        capture.onEvent(CdpNetworkEvent.LoadingFailed("r1"))
        assertEquals(CourseBodyState.REQUEST_FAILED, capture.snapshot().recentRequests.single().bodyState)
    }

    @Test
    fun finishedRequestWithoutResponseIsDiagnosedAndDoesNotFetchBody() {
        val capture = CourseResponseCapture()
        capture.onEvent(requestEvent("r1", courseUrl()))

        assertEquals(CourseCaptureAction.None, capture.onEvent(finishedEvent("r1")))
        assertEquals(CourseBodyState.RESPONSE_MISSING, capture.snapshot().recentRequests.single().bodyState)
    }

    @Test
    fun capturesOnlyFiveMostRecentSummaries() {
        val capture = CourseResponseCapture()
        (1..7).forEach { capture.onEvent(requestEvent("r$it", courseUrl())) }

        assertEquals(listOf(3, 4, 5, 6, 7), capture.snapshot().recentRequests.map { it.number })
    }

    @Test
    fun parsesPlainJsonAndConfirmsCoursePayloadFields() {
        val result = parseCdpResponseBody(
            JSONObject().put("body", scheduleJson()).put("base64Encoded", false)
        ) as CourseBodyParseResult.Confirmed

        assertEquals("2026", result.summary.academicYear)
        assertEquals("0", result.summary.term)
        assertEquals("04", result.summary.currentWeek)
        assertEquals("19", result.summary.maximumWeek)
        assertEquals("2026-09-21", result.summary.startDate)
        assertEquals("2027-01-10", result.summary.endDate)
        assertEquals("12", result.summary.maximumPeriod)
        assertEquals(2, result.summary.weekItemCounts[1])
        assertEquals(1, result.summary.weekItemCounts[3])
        assertTrue(result.summary.isCourseSchedulePayload)
    }

    @Test
    fun parsesBase64Json() {
        val encoded = Base64.getEncoder().encodeToString(scheduleJson().toByteArray(Charsets.UTF_8))
        val result = parseCdpResponseBody(
            JSONObject().put("body", encoded).put("base64Encoded", true),
            base64Decoder = { Base64.getDecoder().decode(it) }
        ) as CourseBodyParseResult.Confirmed

        assertEquals("04", result.summary.currentWeek)
    }

    @Test
    fun malformedBase64AndMalformedUtf8AreDecodeErrors() {
        val invalidBase64 = parseCdpResponseBody(
            JSONObject().put("body", "%%%not-base64%%%").put("base64Encoded", true),
            base64Decoder = { Base64.getDecoder().decode(it) }
        )
        assertEquals(CourseBodyParseResult.DecodeError, invalidBase64)

        val invalidUtf8 = parseCdpResponseBody(
            JSONObject().put("body", "x").put("base64Encoded", true),
            base64Decoder = { byteArrayOf(0xC3.toByte(), 0x28) }
        )
        assertEquals(CourseBodyParseResult.DecodeError, invalidUtf8)
    }

    @Test
    fun nonJsonAndJsonWithoutCourseFieldsAreDifferentResults() {
        assertEquals(
            CourseBodyParseResult.NotJson,
            parseCdpResponseBody(JSONObject().put("body", "not json").put("base64Encoded", false))
        )
        val unconfirmed = parseCdpResponseBody(
            JSONObject().put("body", """{"message":"ok","week1":[]}""").put("base64Encoded", false)
        ) as CourseBodyParseResult.JsonUnconfirmed
        assertFalse(unconfirmed.summary.isCourseSchedulePayload)
        assertEquals(mapOf(1 to 0), unconfirmed.summary.weekItemCounts)
    }

    @Test
    fun allEmptyWeekArraysStillConfirmShapeAndPreserveCurrentWeekAndMaxWeek() {
        val json = JSONObject().put("xn", 2026).put("zc", "04").put("maxzc", 19)
        (1..7).forEach { json.put("week$it", org.json.JSONArray()) }
        val result = parseCdpResponseBody(
            JSONObject().put("body", json.toString()).put("base64Encoded", false)
        ) as CourseBodyParseResult.Confirmed

        assertEquals("04", result.summary.currentWeek)
        assertEquals("19", result.summary.maximumWeek)
        assertEquals((1..7).associateWith { 0 }, result.summary.weekItemCounts)
    }

    @Test
    fun oversizedBodyIsRejectedBeforeJsonParsing() {
        val result = parseCdpResponseBody(
            JSONObject().put("body", "0123456789").put("base64Encoded", false),
            maxBodyBytes = 4
        )
        assertEquals(CourseBodyParseResult.TooLarge, result)
    }

    @Test
    fun observerEnablesNetworkAndFetchesBodyOnlyAfterFinishedEvent() = runBlocking {
        val fake = FakeNetworkSession(
            events = listOf(
                CdpNetworkEvent.RequestWillBeSent("r1", "GET", TargetUrlNormalizer.normalize(courseUrl()).url),
                CdpNetworkEvent.ResponseReceived("r1", 200, "text/plain", "XHR", TargetUrlNormalizer.normalize(courseUrl()).url),
                CdpNetworkEvent.LoadingFinished("r1"),
                null
            ),
            body = scheduleJson()
        )
        val snapshots = mutableListOf<CourseCaptureSnapshot>()
        val states = mutableListOf<CdpNetworkObserverState>()
        val observer = CdpNetworkObserver(fake, onState = states::add, onSnapshot = snapshots::add)

        val result = observer.observe()

        assertTrue(result is CdpNetworkObserverResult.Disconnected)
        assertEquals(
            listOf(
                DevToolsCdpMethod.NETWORK_ENABLE,
                DevToolsCdpMethod.NETWORK_GET_RESPONSE_BODY,
                DevToolsCdpMethod.NETWORK_DISABLE
            ),
            fake.methods
        )
        assertEquals("r1", fake.bodyRequestIds.single())
        assertTrue(states.contains(CdpNetworkObserverState.NETWORK_ENABLED))
        assertTrue(states.contains(CdpNetworkObserverState.WAITING_FOR_COURSE_REQUEST))
        assertTrue(states.contains(CdpNetworkObserverState.COURSE_REQUEST_DETECTED))
        assertTrue(states.contains(CdpNetworkObserverState.COURSE_RESPONSE_DETECTED))
        assertTrue(states.contains(CdpNetworkObserverState.COURSE_BODY_FETCH_START))
        assertTrue(states.contains(CdpNetworkObserverState.COURSE_BODY_FETCH_SUCCESS))
        assertTrue(states.contains(CdpNetworkObserverState.COURSE_JSON_CONFIRMED))
        val summary = snapshots.last().recentRequests.single()
        assertEquals(CourseBodyState.COURSE_JSON_CONFIRMED, summary.bodyState)
        assertTrue(summary.payload?.isCourseSchedulePayload == true)
        assertFalse(summary.toString().contains("private-course-name"))
    }

    @Test
    fun bodyFailureClassesDistinguishMissingResourceInvalidIdAndOtherCdpError() {
        assertEquals(
            CourseBodyState.BODY_NOT_AVAILABLE,
            classifyBodyFetchFailure(-32000, "No resource with given identifier found")
        )
        assertEquals(
            CourseBodyState.BODY_REQUEST_ID_INVALID,
            classifyBodyFetchFailure(-32602, "requestId is invalid")
        )
        assertEquals(
            CourseBodyState.BODY_CDP_ERROR,
            classifyBodyFetchFailure(-32000, "backend error")
        )
    }

    @Test
    fun disconnectIsClassifiedByWhetherAppWasBackgrounded() {
        assertEquals(CdpDisconnectContext.FOREGROUND, classifyCdpDisconnect(true))
        assertEquals(CdpDisconnectContext.BACKGROUND, classifyCdpDisconnect(false))
    }

    private fun requestEvent(requestId: String, url: String): CdpNetworkEvent.RequestWillBeSent =
        parseCdpNetworkEvent(
            JSONObject()
                .put("method", "Network.requestWillBeSent")
                .put(
                    "params",
                    JSONObject()
                        .put("requestId", requestId)
                        .put("request", JSONObject().put("method", "GET").put("url", url))
                )
        ) as CdpNetworkEvent.RequestWillBeSent

    private fun responseEvent(requestId: String, status: Int): CdpNetworkEvent.ResponseReceived =
        parseCdpNetworkEvent(
            JSONObject()
                .put("method", "Network.responseReceived")
                .put(
                    "params",
                    JSONObject()
                        .put("requestId", requestId)
                        .put("type", "XHR")
                        .put(
                            "response",
                            JSONObject()
                                .put("status", status)
                                .put("mimeType", "application/json")
                                .put("url", courseUrl())
                        )
                )
        ) as CdpNetworkEvent.ResponseReceived

    private fun finishedEvent(requestId: String) = CdpNetworkEvent.LoadingFinished(requestId)

    private fun courseUrl() = "https://testjwgl.sdbi.edu.cn$COURSE_SCHEDULE_PATH?ticket=private"

    private fun scheduleJson() =
        """{"xn":"2026","xq":"0","zc":"04","maxzc":19,"qssj":"2026-09-21","jssj":"2027-01-10","maxjc":12,"week1":[{"name":"private-course-name"},{}],"week2":[],"week3":[{}]}"""

    private class FakeNetworkSession(
        events: List<CdpNetworkEvent?>,
        private val body: String
    ) : DevToolsCdpNetworkSession {
        private val remainingEvents = ArrayDeque(events)
        val methods = mutableListOf<DevToolsCdpMethod>()
        val bodyRequestIds = mutableListOf<String>()

        override suspend fun sendCommand(method: DevToolsCdpMethod): DevToolsCdpCommandResult {
            methods += method
            return DevToolsCdpCommandResult.Success(JSONObject())
        }

        override suspend fun sendCommand(
            method: DevToolsCdpMethod,
            params: JSONObject?
        ): DevToolsCdpCommandResult {
            methods += method
            if (method == DevToolsCdpMethod.NETWORK_GET_RESPONSE_BODY) {
                bodyRequestIds += params?.getString("requestId").orEmpty()
                return DevToolsCdpCommandResult.Success(
                    JSONObject().put("body", body).put("base64Encoded", false)
                )
            }
            return DevToolsCdpCommandResult.Success(JSONObject())
        }

        override suspend fun receiveNetworkEvent(): CdpNetworkEventReadResult =
            remainingEvents.removeFirstOrNull()?.let(CdpNetworkEventReadResult::Event)
                ?: CdpNetworkEventReadResult.Closed(DevToolsCdpFailure.STREAM_CLOSED)

        override fun close() = Unit
    }
}
