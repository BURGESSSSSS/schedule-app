package com.burgess.schedule.school.sync.localadb

import java.net.URI
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevToolsCdpClientTest {
    @Test
    fun buildsOnlyThePhaseFourReadOnlyCommand() {
        val frameTree = String(buildCdpCommandPayload(1, DevToolsCdpMethod.PAGE_GET_FRAME_TREE))

        assertEquals(1, JSONObject(frameTree).getInt("id"))
        assertEquals("Page.getFrameTree", JSONObject(frameTree).getString("method"))
        assertFalse(JSONObject(frameTree).has("params"))
        assertFalse(frameTree.contains("Runtime."))
        assertFalse(frameTree.contains("Network."))
    }

    @Test
    fun acceptsAValidWebSocketUpgradeHandshake() {
        val response = (
            "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"
            ).toByteArray()

        val request = websocketHandshakeRequestDiagnostic(URI("ws://127.0.0.1/devtools/page/target"))
        val result = validateWebSocketHandshake(response, "dGhlIHNhbXBsZSBub25jZQ==", request)
        assertTrue(result is HandshakeValidation.Accepted)
        result as HandshakeValidation.Accepted
        assertEquals(101, result.diagnostic.responseStatus)
        assertEquals(DevToolsCdpFailure.CDP_HANDSHAKE_ACCEPTED, result.diagnostic.classification)
        assertEquals(
            listOf("Host", "Upgrade", "Connection", "Sec-WebSocket-Key", "Sec-WebSocket-Version"),
            result.diagnostic.request.headerNames
        )
        assertEquals("localhost", result.diagnostic.request.hostHeaderValue)
        assertFalse(result.diagnostic.request.originHeaderSent)
        assertEquals(null, result.diagnostic.request.originHeaderValue)
        assertEquals("websocket", result.diagnostic.request.upgradeHeaderValue)
        assertEquals("Upgrade", result.diagnostic.request.connectionHeaderValue)
        assertEquals("13", result.diagnostic.request.webSocketVersionHeaderValue)
        val requestText = String(
            buildWebSocketHandshakeRequest(URI("ws://127.0.0.1/devtools/page/target"), "test-key")
        )
        val actualHeaderNames = requestText.substringBefore("\r\n\r\n")
            .split("\r\n")
            .drop(1)
            .map { it.substringBefore(':') }
        assertEquals(result.diagnostic.request.headerNames, actualHeaderNames)
        assertFalse(requestText.contains("Origin:"))
        assertFalse(result.diagnostic.toString().contains("test-key"))
    }

    @Test
    fun rejectsInvalidWebSocketUpgradeHandshake() {
        val response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray()

        val result = validateWebSocketHandshake(response, "dGhlIHNhbXBsZSBub25jZQ==")
        assertTrue(result is HandshakeValidation.Rejected)
        result as HandshakeValidation.Rejected
        assertEquals(DevToolsCdpFailure.CDP_HANDSHAKE_HTTP_STATUS, result.failure)
        assertEquals("CDP_HANDSHAKE_HTTP_STATUS_200", result.diagnostic.classificationLabel)
        assertEquals(200, result.diagnostic.responseStatus)
        assertEquals("OK", result.diagnostic.responseReason)
        assertEquals("空正文", result.diagnostic.responseBodySummary)
    }

    @Test
    fun classifiesOriginRejectionAndKeepsResponseSummarySafe() {
        val response = (
            "HTTP/1.1 403 Forbidden\r\n" +
                "Content-Length: 99\r\n\r\n" +
                "Rejected an incoming WebSocket connection from the http://localhost origin?secret=value"
            ).toByteArray()
        val request = websocketHandshakeRequestDiagnostic(URI("ws://127.0.0.1/devtools/page/target"))

        val result = validateWebSocketHandshake(response, "unused-key", request)

        assertTrue(result is HandshakeValidation.Rejected)
        result as HandshakeValidation.Rejected
        assertEquals(DevToolsCdpFailure.CDP_REJECTED_ORIGIN, result.failure)
        assertEquals(403, result.diagnostic.responseStatus)
        assertEquals("Forbidden", result.diagnostic.responseReason)
        assertTrue(result.diagnostic.responseBodySummary.contains("Origin 被拒绝"))
        assertFalse(result.diagnostic.responseBodySummary.contains("localhost"))
        assertFalse(result.diagnostic.responseBodySummary.contains("secret"))
        assertFalse(result.diagnostic.request.originHeaderSent)
    }

    @Test
    fun distinguishesHostRejectionFromOtherHandshakeFailures() {
        val hostRejected = validateWebSocketHandshake(
            "HTTP/1.1 400 Bad Request\r\n\r\nHost header rejected".toByteArray(),
            "unused-key"
        ) as HandshakeValidation.Rejected
        val other = validateWebSocketHandshake(
            "HTTP/1.1 400 Bad Request\r\n\r\nMalformed request".toByteArray(),
            "unused-key"
        ) as HandshakeValidation.Rejected

        assertEquals(DevToolsCdpFailure.CDP_REJECTED_HOST, hostRejected.failure)
        assertEquals(DevToolsCdpFailure.CDP_HANDSHAKE_HTTP_STATUS, other.failure)
        assertEquals("CDP_HANDSHAKE_HTTP_STATUS_400", other.diagnostic.classificationLabel)
        assertEquals("正文提示 Host 被拒绝 · 20B", hostRejected.diagnostic.responseBodySummary)
    }

    @Test
    fun parsesFrameTreeMainFrameUrlAndRemovesQueryAndFragment() {
        val url = frameTreeUrlFromMessage(
            JSONObject(
                """{"frameTree":{"frame":{"url":"https://testjwgl.sdbi.edu.cn/h5/index.html?ticket=hidden#part"}}}"""
            )
        )

        assertEquals("https://testjwgl.sdbi.edu.cn/h5/index.html", url?.displayUrl)
        assertEquals("testjwgl.sdbi.edu.cn", url?.host)
        assertEquals("/h5/index.html", url?.path)
        assertFalse(url?.displayUrl.orEmpty().contains("ticket"))
        assertFalse(url?.displayUrl.orEmpty().contains("part"))
    }

    @Test
    fun extractsPayloadFromAFrameSplitAcrossInputChunks() {
        val frame = byteArrayOf(0x81.toByte(), 0x04, 't'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(), 't'.code.toByte())

        assertTrue(parseCdpWebSocketFrame(frame.copyOfRange(0, 3)) is CdpWebSocketFrameParseResult.NeedMore)
        val complete = parseCdpWebSocketFrame(frame)
        assertTrue(complete is CdpWebSocketFrameParseResult.Parsed)
        complete as CdpWebSocketFrameParseResult.Parsed
        assertEquals("test", complete.frame.payload.decodeToString())
        assertEquals(frame.size, complete.consumedBytes)
    }
}
