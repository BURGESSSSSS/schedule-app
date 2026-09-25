package com.burgess.schedule.school.sync.localadb

import java.io.Closeable
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevToolsCdpClientTest {
    @Test
    fun onlyBuildsTheTwoAllowedReadOnlyCommands() {
        val frameTree = String(buildCdpCommandPayload(7, DevToolsCdpMethod.PAGE_GET_FRAME_TREE))
        val navigationHistory = String(buildCdpCommandPayload(8, DevToolsCdpMethod.PAGE_GET_NAVIGATION_HISTORY))

        assertEquals(7, JSONObject(frameTree).getInt("id"))
        assertEquals("Page.getFrameTree", JSONObject(frameTree).getString("method"))
        assertEquals(8, JSONObject(navigationHistory).getInt("id"))
        assertEquals("Page.getNavigationHistory", JSONObject(navigationHistory).getString("method"))
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

        assertEquals(
            HandshakeValidation.Accepted,
            validateWebSocketHandshake(response, "dGhlIHNhbXBsZSBub25jZQ==")
        )
    }

    @Test
    fun rejectsInvalidWebSocketUpgradeHandshake() {
        val response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray()

        assertEquals(
            HandshakeValidation.Rejected(DevToolsCdpFailure.HANDSHAKE_REJECTED),
            validateWebSocketHandshake(response, "dGhlIHNhbXBsZSBub25jZQ==")
        )
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
    fun navigationHistoryUsesOnlyTheCurrentEntry() {
        val url = navigationHistoryUrlFromMessage(
            JSONObject(
                """{"currentIndex":1,"entries":[{"url":"https://old.example/"},{"url":"https://testjwgl.sdbi.edu.cn/h5/index.html?secret=hidden"}]}"""
            )
        )

        assertEquals("https://testjwgl.sdbi.edu.cn/h5/index.html", url?.displayUrl)
        assertEquals("testjwgl.sdbi.edu.cn", url?.host)
        assertEquals(null, navigationHistoryUrlFromMessage(JSONObject("""{"currentIndex":3,"entries":[{}]}""")))
    }

    @Test
    fun frameTreeSuccessDoesNotSendNavigationHistory() = runBlocking {
        val session = FakeSession(
            DevToolsCdpCommandResult.Success(
                JSONObject("""{"frameTree":{"frame":{"url":"https://testjwgl.sdbi.edu.cn/h5/index.html?x=secret"}}}""")
            )
        )
        val states = mutableListOf<DevToolsDiscoveryState>()

        val result = resolveTargetUrlViaCdp(session, states::add)

        assertEquals(listOf(DevToolsCdpMethod.PAGE_GET_FRAME_TREE), session.methods)
        assertTrue(result is DevToolsCdpUrlResolution.Resolved)
        result as DevToolsCdpUrlResolution.Resolved
        assertEquals(TargetUrlSource.CDP_PAGE_FRAME_TREE, result.source)
        assertEquals("https://testjwgl.sdbi.edu.cn/h5/index.html", result.url.displayUrl)
        assertTrue(DevToolsDiscoveryState.CDP_FRAME_TREE_SUCCESS in states)
        assertTrue(DevToolsDiscoveryState.TARGET_URL_RESOLVED in states)
    }

    @Test
    fun navigationHistoryIsTheOnlyFallbackWhenFrameTreeHasNoUrl() = runBlocking {
        val session = FakeSession(
            DevToolsCdpCommandResult.Success(JSONObject("""{"frameTree":{"frame":{}}}""")),
            DevToolsCdpCommandResult.Success(
                JSONObject(
                    """{"currentIndex":0,"entries":[{"url":"https://testjwgl.sdbi.edu.cn/h5/index.html?auth=secret"}]}"""
                )
            )
        )

        val result = resolveTargetUrlViaCdp(session) { }

        assertEquals(
            listOf(
                DevToolsCdpMethod.PAGE_GET_FRAME_TREE,
                DevToolsCdpMethod.PAGE_GET_NAVIGATION_HISTORY
            ),
            session.methods
        )
        assertTrue(result is DevToolsCdpUrlResolution.Resolved)
        result as DevToolsCdpUrlResolution.Resolved
        assertEquals(TargetUrlSource.CDP_NAVIGATION_HISTORY, result.source)
        assertEquals("https://testjwgl.sdbi.edu.cn/h5/index.html", result.url.displayUrl)
    }

    @Test
    fun unsupportedFrameTreeCommandFallsBackToNavigationHistory() = runBlocking {
        val session = FakeSession(
            DevToolsCdpCommandResult.Failed(DevToolsCdpFailure.PROTOCOL_ERROR, -32601),
            DevToolsCdpCommandResult.Success(
                JSONObject("""{"currentIndex":0,"entries":[{"url":"https://example.com/path?secret=x"}]}""")
            )
        )

        val result = resolveTargetUrlViaCdp(session) { }

        assertEquals(2, session.methods.size)
        assertTrue(result is DevToolsCdpUrlResolution.Resolved)
        result as DevToolsCdpUrlResolution.Resolved
        assertEquals("https://example.com/path", result.url.displayUrl)
        assertEquals(
            SchoolTargetMatch.NONE,
            classifySchoolTarget(
                result.url.host,
                title = SCHOOL_TITLE,
                type = "page",
                containerName = WEWORK_PACKAGE,
                urlKind = TargetUrlValueKind.HTTP_URL
            )
        )
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
