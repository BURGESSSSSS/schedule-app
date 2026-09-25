package com.burgess.schedule.school.sync.localadb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevToolsTargetParserTest {
    @Test
    fun parsesMultipleTargetsAndKeepsOnlyBasicMetadata() {
        val result = parseDevToolsTargetList(
            """[
                {"id":"page-1","type":"page","title":"我的课表","url":"https://testjwgl.sdbi.edu.cn/h5/index.html?ticket=hidden#section","webSocketDebuggerUrl":"ws://127.0.0.1/devtools/page-1"},
                {"id":"worker-1","type":"service_worker","title":"worker","url":"https://testjwgl.sdbi.edu.cn/worker.js"},
                {"id":"page-2","type":"page","title":"另一个页面","url":"http://example.com/path?q=hidden"}
            ]""".trimIndent(),
            socketName = "@xweb_devtools_remote_12345",
            processes = listOf(WeWorkProcess(12345, "com.tencent.wework:wxa_container0"))
        )

        assertTrue(result is DevToolsTargetParseResult.Parsed)
        val targets = (result as DevToolsTargetParseResult.Parsed).targets
        assertEquals(3, targets.size)
        val school = targets.first()
        assertEquals("page-1", school.id)
        assertEquals("page", school.type)
        assertEquals("我的课表", school.title)
        assertEquals("https://testjwgl.sdbi.edu.cn/h5/index.html", school.displayUrl)
        assertEquals("testjwgl.sdbi.edu.cn", school.host)
        assertEquals("/h5/index.html", school.path)
        assertEquals("com.tencent.wework:wxa_container0", school.processName)
        assertEquals("com.tencent.wework", school.containerName)
        assertTrue(school.hasWebSocketDebuggerUrl)
        assertTrue(school.pathMatchesSchoolPage)
        assertEquals(SchoolTargetMatch.SCHOOL_TARGET_FOUND, school.schoolMatch)
        assertFalse(school.displayUrl.contains("ticket"))
        assertFalse(school.displayUrl.contains("section"))
    }

    @Test
    fun sameSchoolHostWithDifferentTitleIsOnlyHostMatch() {
        val target = parseOne(
            """{"id":"x","type":"page","title":"登录","url":"https://testjwgl.sdbi.edu.cn/login?x=secret"}"""
        )

        assertEquals(SchoolTargetMatch.SCHOOL_HOST_TARGET_FOUND, target.schoolMatch)
        assertEquals("https://testjwgl.sdbi.edu.cn/login", target.displayUrl)
        assertFalse(target.displayUrl.contains("secret"))
    }

    @Test
    fun sameTitleOnAnotherHostDoesNotMatchSchool() {
        val target = parseOne(
            """{"id":"x","type":"page","title":"我的课表","url":"https://example.com/h5/index.html"}"""
        )

        assertEquals(SchoolTargetMatch.NONE, target.schoolMatch)
    }

    @Test
    fun onlyPageTargetsCanMatchSchool() {
        val target = parseOne(
            """{"id":"x","type":"service_worker","title":"我的课表","url":"https://testjwgl.sdbi.edu.cn/h5/index.html"}"""
        )

        assertEquals(SchoolTargetMatch.NONE, target.schoolMatch)
        assertFalse(target.pathMatchesSchoolPage)
    }

    @Test
    fun queryAndFragmentAreRemovedFromHttpAndOpaqueUrls() {
        val http = sanitizeDevToolsTargetUrl("http://debugxweb.qq.com/?inspector=true#secret")
        val about = sanitizeDevToolsTargetUrl("about:blank?secret#fragment")

        assertEquals("http://debugxweb.qq.com/", http?.displayUrl)
        assertEquals("about:blank", about?.displayUrl)
        assertFalse(http?.displayUrl.orEmpty().contains("inspector"))
        assertFalse(about?.displayUrl.orEmpty().contains("secret"))
    }

    @Test
    fun invalidJsonAndOverlongUrlAreRejectedWithoutReturningRawInput() {
        assertTrue(parseDevToolsTargetList("not json", "@socket", emptyList()) is DevToolsTargetParseResult.InvalidJson)
        assertEquals(null, sanitizeDevToolsTargetUrl("x".repeat(8_193)))
    }

    private fun parseOne(json: String): DevToolsTarget =
        (parseDevToolsTargetList("[$json]", "@webview_devtools_remote_7", emptyList()) as DevToolsTargetParseResult.Parsed)
            .targets.single()
}
