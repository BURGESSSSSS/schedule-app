package com.burgess.schedule.school.sync.localadb

import java.net.URI
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CdpReadOnlyProbeTest {
    @Test
    fun handshakeUsesOnlyLocalHostAndWebSocketPathWithoutOriginOrQuery() {
        val uri = URI("ws://127.0.0.1:9222/devtools/page/target-id?secret=hidden")
        val request = String(buildWebSocketHandshakeRequest(uri, "safe-test-key"), StandardCharsets.US_ASCII)
        val diagnostic = websocketHandshakeRequestDiagnostic(uri)

        assertTrue(request.startsWith("GET /devtools/page/target-id HTTP/1.1\r\n"))
        assertTrue(request.contains("Host: localhost\r\n"))
        assertTrue(request.contains("Upgrade: websocket\r\n"))
        assertTrue(request.contains("Connection: Upgrade\r\n"))
        assertTrue(request.contains("Sec-WebSocket-Version: 13\r\n"))
        assertFalse(request.contains("Origin:"))
        assertFalse(request.contains("secret"))
        assertFalse(diagnostic.originHeaderSent)
        assertEquals("localhost", diagnostic.hostHeaderValue)
    }

    @Test
    fun handshakeClassifiesInvalidAcceptAndUpgradeHeaders() {
        val request = websocketHandshakeRequestDiagnostic(URI("ws://localhost/devtools/page/id"))
        val badAccept = (
            "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: incorrect\r\n\r\n"
            ).toByteArray()
        val badUpgrade = (
            "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: h2c\r\nConnection: keep-alive\r\n" +
                "Sec-WebSocket-Accept: ${websocketAcceptValue("dGhlIHNhbXBsZSBub25jZQ==")}\r\n\r\n"
            ).toByteArray()

        val acceptResult = validateWebSocketHandshake(badAccept, "dGhlIHNhbXBsZSBub25jZQ==", request)
            as HandshakeValidation.Rejected
        val upgradeResult = validateWebSocketHandshake(badUpgrade, "dGhlIHNhbXBsZSBub25jZQ==", request)
            as HandshakeValidation.Rejected

        assertEquals(DevToolsCdpFailure.CDP_INVALID_ACCEPT, acceptResult.failure)
        assertEquals("CDP_INVALID_ACCEPT", acceptResult.diagnostic.classificationLabel)
        assertEquals(DevToolsCdpFailure.CDP_UPGRADE_HEADER_INVALID, upgradeResult.failure)
        assertEquals("CDP_UPGRADE_HEADER_INVALID", upgradeResult.diagnostic.classificationLabel)
    }

    @Test
    fun handshakeClassifiesHttpStatusesOriginAndHost() {
        val key = "dGhlIHNhbXBsZSBub25jZQ=="
        val cases = listOf(
            "HTTP/1.1 403 Forbidden\r\n\r\nRejected origin" to "CDP_REJECTED_ORIGIN",
            "HTTP/1.1 400 Bad Request\r\n\r\nHost header rejected" to "CDP_REJECTED_HOST",
            "HTTP/1.1 500 Internal Server Error\r\n\r\nUnavailable" to "CDP_HANDSHAKE_HTTP_STATUS_500"
        )

        cases.forEach { (response, expected) ->
            val result = validateWebSocketHandshake(response.toByteArray(), key) as HandshakeValidation.Rejected
            assertEquals(expected, result.diagnostic.classificationLabel)
        }
    }

    @Test
    fun handshakeAccumulatorHandlesSplitResponseAndPreservesFollowingFrame() {
        val key = "dGhlIHNhbXBsZSBub25jZQ=="
        val response = (
            "HTTP/1.1 101 Switching Protocols\r\n" +
                "uPgRaDe: WebSocket\r\n" +
                "cOnNeCtIoN: keep-alive, Upgrade\r\n" +
                "Sec-WebSocket-Accept: ${websocketAcceptValue(key)}\r\n\r\n"
            ).toByteArray(StandardCharsets.US_ASCII)
        val nextFrame = byteArrayOf(0x81.toByte(), 0x04, 't'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(), 't'.code.toByte())
        val combined = response + nextFrame
        val splitAt = response.size - 3
        val accumulator = WebSocketHandshakeAccumulator()

        assertNull(accumulator.append(combined, 0, splitAt))
        val parsed = accumulator.append(combined, splitAt, combined.size - splitAt)!!

        assertArrayEquals(response, parsed.headerBytes)
        assertArrayEquals(nextFrame, parsed.remainingBytes)
        val validation = validateWebSocketHandshake(parsed.headerBytes, key)
        assertTrue(validation is HandshakeValidation.Accepted)
    }

    @Test
    fun clientFramesAreMaskedAndSupportShortAndExtendedPayloadLengths() {
        val mask = byteArrayOf(1, 2, 3, 4)
        val shortPayload = "test".toByteArray()
        val shortFrame = buildClientWebSocketFrame(0x1, shortPayload, mask)
        assertEquals(0x81, shortFrame[0].toInt() and 0xFF)
        assertTrue(shortFrame[1].toInt() and 0x80 != 0)
        assertEquals(4, shortFrame[1].toInt() and 0x7F)
        assertParsedPayload(shortFrame, shortPayload)

        val extendedPayload = ByteArray(126) { it.toByte() }
        val extendedFrame = buildClientWebSocketFrame(0x1, extendedPayload, mask)
        assertEquals(126, extendedFrame[1].toInt() and 0x7F)
        assertEquals(0, extendedFrame[2].toInt() and 0xFF)
        assertEquals(126, extendedFrame[3].toInt() and 0xFF)
        assertParsedPayload(extendedFrame, extendedPayload)
    }

    @Test
    fun serverTextFrameAndFragmentedTextAreHandled() {
        val serverFrame = byteArrayOf(0x81.toByte(), 0x04, 'p'.code.toByte(), 'o'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte())
        val parsed = parseCdpWebSocketFrame(serverFrame) as CdpWebSocketFrameParseResult.Parsed
        assertEquals("pong", parsed.frame.payload.decodeToString())

        val assembler = CdpWebSocketMessageAssembler()
        assertTrue(assembler.accept(CdpWebSocketFrame(false, 0x1, "hel".toByteArray())) is CdpIncomingFrameAction.Continue)
        val message = assembler.accept(CdpWebSocketFrame(true, 0x0, "lo".toByteArray()))
            as CdpIncomingFrameAction.TextMessage
        assertEquals("hello", message.payload.decodeToString())
    }

    @Test
    fun pingRequestsPongAndCloseIsHandled() {
        val assembler = CdpWebSocketMessageAssembler()
        val pingPayload = "alive".toByteArray()
        val pong = assembler.accept(CdpWebSocketFrame(true, 0x9, pingPayload)) as CdpIncomingFrameAction.PongRequired
        assertArrayEquals(pingPayload, pong.payload)
        assertTrue(assembler.accept(CdpWebSocketFrame(true, 0xA, pingPayload)) is CdpIncomingFrameAction.PongReceived)

        val closePayload = byteArrayOf(0x03, 0xE8.toByte())
        val close = assembler.accept(CdpWebSocketFrame(true, 0x8, closePayload)) as CdpIncomingFrameAction.CloseReceived
        assertArrayEquals(closePayload, close.payload)
        val closeResponse = buildClientWebSocketFrame(0x8, close.payload, byteArrayOf(1, 2, 3, 4))
        assertParsedPayload(closeResponse, closePayload)
    }

    @Test
    fun responseParserIgnoresEventsAndUnrelatedIdsThenAcceptsIdOne() {
        assertTrue(
            parseCdpResponseMessage("""{"method":"Page.frameNavigated","params":{}}""", 1)
                is CdpResponseParseResult.Ignore
        )
        assertTrue(
            parseCdpResponseMessage("""{"id":9,"result":{}}""", 1)
                is CdpResponseParseResult.Ignore
        )
        val response = parseCdpResponseMessage("""{"id":1,"result":{"frameTree":{"frame":{"url":"about:blank"}}}}""", 1)
            as CdpResponseParseResult.Success
        assertEquals("about:blank", response.result.getJSONObject("frameTree").getJSONObject("frame").getString("url"))
    }

    @Test
    fun protocolErrorsExposeCodeAndSanitizedMessage() {
        val parsed = parseCdpResponseMessage(
            """{"id":1,"error":{"code":-32000,"message":"Denied https://example.test/path?token=secret Authorization: Bearer another-secret"}}""",
            1
        ) as CdpResponseParseResult.Error

        assertEquals(-32000, parsed.code)
        assertFalse(parsed.message.orEmpty().contains("secret"))
        assertFalse(parsed.message.orEmpty().contains("?token"))
    }

    @Test
    fun readOnlyProbeSendsOnlyGetFrameTreeAndNormalizesTheMainFrameUrl() = runBlocking {
        val session = FakeSession(
            DevToolsCdpCommandResult.Success(
                JSONObject("""{"frameTree":{"frame":{"id":"main","url":"https://testjwgl.sdbi.edu.cn/h5/index.html?ticket=private"}}}""")
            )
        )

        val result = requestMainFrameUrl(session)

        assertEquals(listOf(DevToolsCdpMethod.PAGE_GET_FRAME_TREE), session.methods)
        assertTrue(result is CdpFrameTreeProbeResult.Success)
        result as CdpFrameTreeProbeResult.Success
        assertEquals("https://testjwgl.sdbi.edu.cn/h5/index.html", result.mainFrameUrl.displayUrl)
        assertTrue(isSchoolMainFrameUrl(result.mainFrameUrl))
        assertEquals("MATCH", compareHttpAndCdpTargetUrls(result.mainFrameUrl.displayUrl, result.mainFrameUrl))
    }

    @Test
    fun differingHttpAndCdpUrlIsMismatchAndOtherHostCannotConfirmSchoolFrame() {
        val schoolUrl = TargetUrlNormalizer.normalize("https://testjwgl.sdbi.edu.cn/h5/index.html")
            .url!!
        val otherUrl = TargetUrlNormalizer.normalize("https://example.com/h5/index.html")
            .url!!

        assertEquals("MISMATCH", compareHttpAndCdpTargetUrls(schoolUrl.displayUrl, otherUrl))
        assertFalse(isSchoolMainFrameUrl(otherUrl))
    }

    @Test
    fun onlyMainFrameUrlIsReadAndMissingUrlDoesNotTriggerFallbackCommand() = runBlocking {
        val session = FakeSession(DevToolsCdpCommandResult.Success(JSONObject("""{"frameTree":{"frame":{"id":"main"}}}""")))

        val result = requestMainFrameUrl(session)

        assertEquals(CdpFrameTreeProbeResult.MainFrameUrlMissing, result)
        assertEquals(listOf(DevToolsCdpMethod.PAGE_GET_FRAME_TREE), session.methods)
    }

    private fun assertParsedPayload(frame: ByteArray, expected: ByteArray) {
        val parsed = parseCdpWebSocketFrame(frame) as CdpWebSocketFrameParseResult.Parsed
        assertArrayEquals(expected, parsed.frame.payload)
    }

    private class FakeSession(vararg results: DevToolsCdpCommandResult) : DevToolsCdpCommandSession {
        private val remaining = ArrayDeque(results.toList())
        val methods = mutableListOf<DevToolsCdpMethod>()

        override suspend fun sendCommand(method: DevToolsCdpMethod): DevToolsCdpCommandResult {
            methods += method
            return remaining.removeFirst()
        }

        override fun close() = Unit
    }
}
