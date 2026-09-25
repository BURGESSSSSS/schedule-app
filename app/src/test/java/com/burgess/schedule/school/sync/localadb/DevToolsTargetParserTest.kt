package com.burgess.schedule.school.sync.localadb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class DevToolsTargetParserTest {
    @Test
    fun parsesPageTargetAndSanitizesHttpUrl() {
        val target = parseOne(
            """{"id":"page-1","type":"page","title":"我的课表","url":"https://testjwgl.sdbi.edu.cn/h5/index.html?ticket=hidden#section","webSocketDebuggerUrl":"ws://127.0.0.1/devtools/page/page-1"}"""
        )

        assertEquals("page-1", target.id)
        assertEquals("page", target.type)
        assertEquals("我的课表", target.title)
        assertEquals("https://testjwgl.sdbi.edu.cn/h5/index.html", target.displayUrl)
        assertEquals("testjwgl.sdbi.edu.cn", target.host)
        assertEquals("/h5/index.html", target.path)
        assertEquals("com.tencent.wework:wxa_container0", target.processName)
        assertEquals("com.tencent.wework", target.containerName)
        assertTrue(target.hasWebSocketDebuggerUrl)
        assertEquals(SchoolTargetMatch.SCHOOL_TARGET_FOUND, target.schoolMatch)
        assertEquals(TargetUrlValueKind.HTTP_URL, target.endpointSummaries.single().urlKind)
        assertFalse(target.displayUrl.contains("ticket"))
        assertFalse(target.displayUrl.contains("section"))
    }

    @Test
    fun resolvesXWebUrlWithRawObjectLikeQueryFromHttpMetadata() {
        val queryPrefix = "https://testjwgl.sdbi.edu.cn/h5/index.html?zlParam={%22mode%22:%22"
        val querySuffix = "%22}"
        val rawUrl = queryPrefix + "x".repeat(479 - queryPrefix.length - querySuffix.length) + querySuffix
        val json = JSONObject()
            .put("id", "page-1")
            .put("type", "page")
            .put("title", SCHOOL_TITLE)
            .put("url", rawUrl)
            .put("webSocketDebuggerUrl", "ws://127.0.0.1/devtools/page/page-1")
            .toString()
        val parsed = parseDevToolsTargetList(
            "[$json]",
            "@webview_devtools_remote_123",
            weworkProcess(),
            endpoint = DevToolsEndpoint.JSON_LIST
        ) as DevToolsTargetParseResult.Parsed
        val target = parsed.targets.single().target
        val summary = target.endpointSummaries.single()

        assertEquals(479, rawUrl.length)
        assertEquals(479, summary.urlStringLength)
        assertEquals(TargetUrlValueKind.HTTP_URL_LENIENT, summary.urlKind)
        assertEquals("URISyntaxException", summary.strictUrlParseFailureType)
        assertEquals(rawUrl.indexOf('{'), summary.strictUrlParseFailureIndex)
        assertEquals(TargetUrlSource.HTTP_JSON_LIST_LENIENT, target.urlSource)
        assertEquals("https://testjwgl.sdbi.edu.cn/h5/index.html", target.displayUrl)
        assertEquals("testjwgl.sdbi.edu.cn", target.host)
        assertEquals("/h5/index.html", target.path)
        assertEquals(SchoolTargetMatch.SCHOOL_TARGET_FOUND, target.schoolMatch)
        assertFalse(target.displayUrl.contains("zlParam"))
        assertFalse(summary.toString().contains("APP_JW"))
    }

    @Test
    fun lenientUrlFromJsonEndpointUsesSpecificSource() {
        val rawUrl = "https://testjwgl.sdbi.edu.cn/h5/schedule.html?data={x}"
        val parsed = parseDevToolsTargetList(
            "[${JSONObject().put("id", "j").put("type", "page").put("title", SCHOOL_TITLE).put("url", rawUrl)}]",
            "@webview_devtools_remote_123",
            weworkProcess(),
            endpoint = DevToolsEndpoint.JSON
        ) as DevToolsTargetParseResult.Parsed

        assertEquals(TargetUrlSource.HTTP_JSON_LENIENT, parsed.targets.single().target.urlSource)
        assertEquals(SchoolTargetMatch.SCHOOL_TARGET_FOUND, parsed.targets.single().target.schoolMatch)
    }

    @Test
    fun endpointMergePrefersAConfirmedHostOverHostlessStrictScheme() {
        val socket = "@webview_devtools_remote_123"
        val processes = weworkProcess()
        val strictHostless = parseDevToolsTargetList(
            "[${JSONObject().put("id", "same").put("type", "page").put("title", SCHOOL_TITLE).put("url", "http:relative").toString()}]",
            socket,
            processes,
            endpoint = DevToolsEndpoint.JSON_LIST
        ) as DevToolsTargetParseResult.Parsed
        val lenientSchool = parseDevToolsTargetList(
            "[${JSONObject().put("id", "same").put("type", "page").put("title", SCHOOL_TITLE).put("url", "https://testjwgl.sdbi.edu.cn/h5/index.html?x={y}").toString()}]",
            socket,
            processes,
            endpoint = DevToolsEndpoint.JSON
        ) as DevToolsTargetParseResult.Parsed

        val merged = mergeTargetEndpointMetadata(strictHostless.targets.single(), lenientSchool.targets.single())

        assertEquals("testjwgl.sdbi.edu.cn", merged.target.host)
        assertEquals(TargetUrlSource.HTTP_JSON_LENIENT, merged.target.urlSource)
        assertEquals(SchoolTargetMatch.SCHOOL_TARGET_FOUND, merged.target.schoolMatch)
    }

    @Test
    fun distinguishesUrlKeyMissingNullAndEmpty() {
        val missing = parseOne("""{"id":"a","type":"page","title":"我的课表"}""")
        val nullValue = parseOne("""{"id":"b","type":"page","title":"我的课表","url":null}""")
        val empty = parseOne("""{"id":"c","type":"page","title":"我的课表","url":""}""")
        val other = parseOne("""{"id":"d","type":"page","title":"我的课表","url":17}""")

        assertEquals(TargetUrlValueKind.MISSING, missing.endpointSummaries.single().urlKind)
        assertEquals(TargetUrlValueKind.NULL, nullValue.endpointSummaries.single().urlKind)
        assertEquals(TargetUrlValueKind.EMPTY, empty.endpointSummaries.single().urlKind)
        assertEquals(SchoolTargetMatch.SCHOOL_TARGET_CANDIDATE_URL_UNKNOWN, missing.schoolMatch)
        assertEquals(SchoolTargetMatch.SCHOOL_TARGET_CANDIDATE_URL_UNKNOWN, nullValue.schoolMatch)
        assertEquals(SchoolTargetMatch.SCHOOL_TARGET_CANDIDATE_URL_UNKNOWN, empty.schoolMatch)
        assertEquals(SchoolTargetMatch.SCHOOL_TARGET_CANDIDATE_URL_UNKNOWN, other.schoolMatch)
    }

    @Test
    fun distinguishesNonHttpAndInvalidUrlValues() {
        val about = parseOne("""{"id":"a","type":"page","title":"我的课表","url":"about:blank"}""")
        val malformed = parseOne("""{"id":"b","type":"page","title":"我的课表","url":"http://[broken"}""")
        val otherType = parseOne("""{"id":"c","type":"page","title":"我的课表","url":17}""")

        assertEquals(TargetUrlValueKind.NON_HTTP_URL, about.endpointSummaries.single().urlKind)
        assertEquals("about:blank", about.displayUrl)
        assertEquals(SchoolTargetMatch.NONE, about.schoolMatch)
        assertEquals(TargetUrlValueKind.INVALID_URL, malformed.endpointSummaries.single().urlKind)
        assertEquals(SchoolTargetMatch.SCHOOL_TARGET_CANDIDATE_URL_UNKNOWN, malformed.schoolMatch)
        assertEquals(TargetUrlValueKind.OTHER_VALUE, otherType.endpointSummaries.single().urlKind)
    }

    @Test
    fun recordsRequestedFieldPresenceAndStringLengthsWithoutFieldValues() {
        val parsed = parseDevToolsTargetList(
            """[{"id":"i","type":"page","title":"我的课表","url":null,"description":"{\"attached\":true,\"visible\":true}","devtoolsFrontendUrl":"/devtools/inspector.html?secret=hidden","webSocketDebuggerUrl":"ws://localhost/devtools/page/i","faviconUrl":"https://example.com/favicon.ico"}]""",
            "@webview_devtools_remote_123",
            weworkProcess(),
            endpoint = DevToolsEndpoint.JSON_LIST
        ) as DevToolsTargetParseResult.Parsed
        val target = parsed.targets.single().target
        val summary = target.endpointSummaries.single()

        assertEquals(
            listOf(
                "id",
                "type",
                "title",
                "url",
                "description",
                "devtoolsFrontendUrl",
                "webSocketDebuggerUrl",
                "faviconUrl"
            ),
            summary.fields.map { it.name }
        )
        assertEquals(TargetDescriptionKind.JSON_OBJECT, summary.descriptionKind)
        assertEquals(listOf("attached", "visible"), summary.descriptionKeys)
        val frontendUrlLength = "/devtools/inspector.html?secret=hidden".length
        assertEquals(
            frontendUrlLength,
            summary.fields.first { it.name == "devtoolsFrontendUrl" }.stringLength
        )
        assertTrue(summary.hasWebSocketDebuggerUrl)
        assertFalse(target.toString().contains("secret=hidden"))
        assertFalse(target.toString().contains("ws://localhost"))
    }

    @Test
    fun marksNonJsonDescriptionWithoutKeepingItsText() {
        val target = parseOne(
            """{"id":"i","type":"page","title":"我的课表","description":"attached=true visible=true"}"""
        )
        val summary = target.endpointSummaries.single()

        assertEquals(TargetDescriptionKind.NON_JSON, summary.descriptionKind)
        assertTrue(summary.descriptionKeys.isEmpty())
        assertFalse(summary.toString().contains("attached=true"))
    }

    @Test
    fun comparesJsonAndJsonListMetadataForTheSameTargetId() {
        val jsonList = parseDevToolsTargetList(
            """[{"id":"same","type":"page","title":"我的课表","url":"about:blank?hidden=x","description":"{\"attached\":true}","webSocketDebuggerUrl":"ws://localhost/devtools/page/same"}]""",
            "@webview_devtools_remote_123",
            weworkProcess(),
            DevToolsEndpoint.JSON_LIST
        ) as DevToolsTargetParseResult.Parsed
        val json = parseDevToolsTargetList(
            """[{"id":"same","type":"page","title":"我的课表","url":"https://testjwgl.sdbi.edu.cn/h5/index.html?secret=hidden","description":"{\"visible\":true}","webSocketDebuggerUrl":"ws://localhost/devtools/page/same"}]""",
            "@webview_devtools_remote_123",
            weworkProcess(),
            DevToolsEndpoint.JSON
        ) as DevToolsTargetParseResult.Parsed
        val merged = mergeTargetEndpointMetadata(jsonList.targets.single(), json.targets.single())

        assertEquals("same", merged.target.id)
        assertEquals("我的课表", merged.target.title)
        assertEquals("https://testjwgl.sdbi.edu.cn/h5/index.html", merged.target.displayUrl)
        assertEquals(TargetUrlSource.HTTP_JSON, merged.target.urlSource)
        assertEquals(TargetUrlValueKind.NON_HTTP_URL, merged.target.endpointSummaries[0].urlKind)
        assertEquals(TargetUrlValueKind.HTTP_URL, merged.target.endpointSummaries[1].urlKind)
        assertEquals(
            listOf(DevToolsEndpoint.JSON_LIST, DevToolsEndpoint.JSON),
            merged.target.endpointSummaries.map { it.endpoint }
        )
        assertEquals(listOf("attached"), merged.target.endpointSummaries[0].descriptionKeys)
        assertEquals(listOf("visible"), merged.target.endpointSummaries[1].descriptionKeys)
        assertFalse(merged.target.toString().contains("secret=hidden"))
    }

    @Test
    fun titleAndContainerCandidateRequiresPageAndWeworkProcess() {
        val noProcess = (parseDevToolsTargetList(
            """[{"id":"i","type":"page","title":"我的课表"}]""",
            "@webview_devtools_remote_123",
            emptyList()
        ) as DevToolsTargetParseResult.Parsed).targets.single().target
        val nonPage = parseOne(
            """{"id":"i","type":"service_worker","title":"我的课表"}"""
        )

        assertEquals(SchoolTargetMatch.NONE, noProcess.schoolMatch)
        assertEquals(SchoolTargetMatch.NONE, nonPage.schoolMatch)
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
    fun nonHttpSchemeOnSchoolHostDoesNotMatchSchool() {
        val target = parseOne(
            """{"id":"x","type":"page","title":"我的课表","url":"ftp://testjwgl.sdbi.edu.cn/h5/index.html"}"""
        )

        assertEquals(TargetUrlValueKind.NON_HTTP_URL, target.endpointSummaries.single().urlKind)
        assertEquals(SchoolTargetMatch.NONE, target.schoolMatch)
    }

    @Test
    fun descriptionJsonArrayAndEmptyValuesAreReportedSafely() {
        val array = parseOne("""{"id":"a","type":"page","title":"我的课表","description":"[1,2]"}""")
        val empty = parseOne("""{"id":"b","type":"page","title":"我的课表","description":""}""")
        val missing = parseOne("""{"id":"c","type":"page","title":"我的课表"}""")

        assertEquals(TargetDescriptionKind.JSON_ARRAY, array.endpointSummaries.single().descriptionKind)
        assertEquals(TargetDescriptionKind.EMPTY, empty.endpointSummaries.single().descriptionKind)
        assertEquals(TargetDescriptionKind.MISSING, missing.endpointSummaries.single().descriptionKind)
    }

    @Test
    fun onlyPageTargetsCanMatchSchoolAndPath() {
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
        val data = sanitizeDevToolsTargetUrl("data:text/html,private-page")

        assertEquals("http://debugxweb.qq.com/", http?.displayUrl)
        assertEquals("about:blank", about?.displayUrl)
        assertEquals("data:", data?.displayUrl)
        assertFalse(http?.displayUrl.orEmpty().contains("inspector"))
        assertFalse(about?.displayUrl.orEmpty().contains("secret"))
        assertFalse(data?.displayUrl.orEmpty().contains("private-page"))
    }

    @Test
    fun invalidJsonAndOverlongUrlAreRejectedWithoutReturningRawInput() {
        assertTrue(
            parseDevToolsTargetList("not json", "@socket", emptyList()) is
                DevToolsTargetParseResult.InvalidJson
        )
        assertEquals(null, sanitizeDevToolsTargetUrl("x".repeat(8_193)))
    }

    private fun parseOne(json: String): DevToolsTarget =
        (parseDevToolsTargetList(
            "[$json]",
            "@webview_devtools_remote_123",
            weworkProcess()
        ) as DevToolsTargetParseResult.Parsed).targets.single().target

    private fun weworkProcess() = listOf(WeWorkProcess(123, "com.tencent.wework:wxa_container0"))
}
