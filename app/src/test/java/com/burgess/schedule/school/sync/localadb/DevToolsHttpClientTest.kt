package com.burgess.schedule.school.sync.localadb

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevToolsHttpClientTest {
    @Test
    fun parses200WithContentLengthAndJsonBody() {
        val body = "[{\"id\":\"a\",\"type\":\"page\"}]"
        val result = parseResponse("HTTP/1.1 200 OK\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body")

        assertTrue(result is DevToolsHttpParseResult.Complete)
        val response = (result as DevToolsHttpParseResult.Complete).response
        assertEquals(200, response.statusCode)
        assertEquals(body, response.body)
        assertEquals(body.toByteArray().size.toString(), response.headers["content-length"])
    }

    @Test
    fun completesWhenHeadersAndBodyArriveInSeveralChunks() {
        val body = "[{\"title\":\"我的课表\"}]"
        val response = "HTTP/1.1 200 OK\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body"
            .toByteArray(StandardCharsets.UTF_8)
        val chunks = listOf(
            response.copyOfRange(0, 8),
            response.copyOfRange(8, 29),
            response.copyOfRange(29, response.size - 3),
            response.copyOfRange(response.size - 3, response.size)
        )
        val accumulator = ByteArrayOutputStream()
        var result: DevToolsHttpParseResult = DevToolsHttpParseResult.NeedMore
        chunks.forEach { chunk ->
            accumulator.write(chunk)
            result = parseDevToolsHttpResponse(accumulator.toByteArray(), endOfStream = false)
        }

        assertTrue(result is DevToolsHttpParseResult.Complete)
        assertEquals(body, (result as DevToolsHttpParseResult.Complete).response.body)
    }

    @Test
    fun parsesConnectionCloseBodyOnlyAtEndOfStream() {
        val raw = "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n[]".toByteArray(StandardCharsets.UTF_8)

        assertEquals(DevToolsHttpParseResult.NeedMore, parseDevToolsHttpResponse(raw, endOfStream = false))
        val result = parseDevToolsHttpResponse(raw, endOfStream = true)
        assertTrue(result is DevToolsHttpParseResult.Complete)
        assertEquals("[]", (result as DevToolsHttpParseResult.Complete).response.body)
    }

    @Test
    fun parsesChunkedResponse() {
        val result = parseResponse(
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                "2\r\n[]\r\n0\r\n\r\n"
        )

        assertTrue(result is DevToolsHttpParseResult.Complete)
        assertEquals("[]", (result as DevToolsHttpParseResult.Complete).response.body)
    }

    @Test
    fun reportsNon200StatusAndLegacyEndpointFallbackRule() {
        val result = parseResponse("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n")

        assertTrue(result is DevToolsHttpParseResult.Complete)
        assertEquals(404, (result as DevToolsHttpParseResult.Complete).response.statusCode)
        assertTrue(shouldTryLegacyDevToolsEndpoint(404))
        assertTrue(shouldTryLegacyDevToolsEndpoint(405))
        assertTrue(shouldTryLegacyDevToolsEndpoint(501))
        assertFalse(shouldTryLegacyDevToolsEndpoint(500))
    }

    @Test
    fun rejectsMalformedJsonStatusHeadersAndTruncatedBodies() {
        assertEquals(
            DevToolsHttpFailure.MALFORMED_STATUS_LINE,
            (parseResponse("not http\r\nX: y\r\n\r\n") as DevToolsHttpParseResult.Failed).failure
        )
        assertEquals(
            DevToolsHttpFailure.MALFORMED_HEADERS,
            (parseResponse("HTTP/1.1 200 OK\r\ninvalid-header\r\n\r\n") as DevToolsHttpParseResult.Failed).failure
        )
        assertEquals(
            DevToolsHttpFailure.PREMATURE_EOF,
            (parseDevToolsHttpResponse(
                "HTTP/1.1 200 OK\r\nContent-Length: 8\r\n\r\n[]".toByteArray(StandardCharsets.UTF_8),
                endOfStream = true
            ) as DevToolsHttpParseResult.Failed).failure
        )
    }

    @Test
    fun requestIsLimitedToSpecifiedDiscoveryEndpointAndHeaders() {
        val request = String(buildDevToolsDiscoveryRequest(DevToolsEndpoint.JSON_LIST), StandardCharsets.US_ASCII)

        assertEquals(
            "GET /json/list HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n",
            request
        )
        assertFalse(request.contains("/json/version"))
        assertFalse(request.contains("webSocketDebuggerUrl"))
    }

    private fun parseResponse(raw: String): DevToolsHttpParseResult =
        parseDevToolsHttpResponse(raw.toByteArray(StandardCharsets.UTF_8), endOfStream = true)
}
