package com.burgess.schedule.school.sync.localadb

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetUrlNormalizerTest {
    @Test
    fun stripsUnsafeQueryBeforeParsingBaseUrl() {
        val queries = listOf(
            "value=with spaces",
            "value={object}",
            "value=\"quoted\""
        )

        queries.forEach { query ->
            val normalized = TargetUrlNormalizer.normalize(
                "https://testjwgl.sdbi.edu.cn/h5/index.html?$query"
            )

            assertEquals("https://testjwgl.sdbi.edu.cn/h5/index.html", normalized.url?.displayUrl)
            assertEquals("https", normalized.url?.scheme)
            assertEquals("testjwgl.sdbi.edu.cn", normalized.url?.host)
            assertEquals("/h5/index.html", normalized.url?.path)
            assertEquals(TargetUrlNormalizationMode.LENIENT_BASE, normalized.mode)
            assertEquals("URISyntaxException", normalized.strictParseFailureType)
            assertTrue((normalized.strictParseFailureIndex ?: -1) >= "https://testjwgl.sdbi.edu.cn/h5/index.html?".length)
        }
    }

    @Test
    fun stripsFragmentAndQueryWithoutDecodingOrRebuilding() {
        val normalized = TargetUrlNormalizer.normalize(
            " https://testjwgl.sdbi.edu.cn/h5/index.html?x={\"name\":\"课程\"}#private "
        )

        assertEquals("https://testjwgl.sdbi.edu.cn/h5/index.html", normalized.url?.displayUrl)
        assertFalse(normalized.url?.displayUrl.orEmpty().contains("private"))
    }

    @Test
    fun chineseQueryIsNeverIncludedInNormalizedDisplayUrl() {
        val normalized = TargetUrlNormalizer.normalize(
            "https://testjwgl.sdbi.edu.cn/h5/index.html?课程=我的课表"
        )

        assertEquals("https://testjwgl.sdbi.edu.cn/h5/index.html", normalized.url?.displayUrl)
        assertFalse(normalized.url?.displayUrl.orEmpty().contains("课程"))
    }

    @Test
    fun noQueryUrlUsesStrictMode() {
        val normalized = TargetUrlNormalizer.normalize("https://testjwgl.sdbi.edu.cn/h5/index.html")

        assertEquals(TargetUrlNormalizationMode.STRICT, normalized.mode)
        assertNull(normalized.strictParseFailureType)
        assertEquals("testjwgl.sdbi.edu.cn", normalized.url?.host)
    }

    @Test
    fun matchingRequiresHttpsSchoolHostAndKnownH5Path() {
        val correct = parseTarget("https://testjwgl.sdbi.edu.cn/h5/schedule.html")
        val wrongHost = parseTarget("https://example.com/h5/index.html")
        val wrongPath = parseTarget("https://testjwgl.sdbi.edu.cn/login")
        val http = parseTarget("http://testjwgl.sdbi.edu.cn/h5/index.html")
        val nestedH5 = parseTarget("https://testjwgl.sdbi.edu.cn/h5/week/view")

        assertEquals(SchoolTargetMatch.SCHOOL_TARGET_FOUND, correct.schoolMatch)
        assertEquals(SchoolTargetMatch.NONE, wrongHost.schoolMatch)
        assertEquals(SchoolTargetMatch.NONE, wrongPath.schoolMatch)
        assertEquals(SchoolTargetMatch.NONE, http.schoolMatch)
        assertEquals(SchoolTargetMatch.SCHOOL_TARGET_FOUND, nestedH5.schoolMatch)
    }

    @Test
    fun opaqueAndEmptyValuesDoNotMatchSchool() {
        val javascript = parseTarget("javascript:alert(1)")
        val about = parseTarget("about:blank")
        val empty = parseTarget("")

        assertEquals(TargetUrlValueKind.NON_HTTP_URL, javascript.endpointSummaries.single().urlKind)
        assertEquals(TargetUrlValueKind.NON_HTTP_URL, about.endpointSummaries.single().urlKind)
        assertEquals(TargetUrlValueKind.EMPTY, empty.endpointSummaries.single().urlKind)
        assertEquals(SchoolTargetMatch.NONE, javascript.schoolMatch)
        assertEquals(SchoolTargetMatch.NONE, about.schoolMatch)
        assertEquals(SchoolTargetMatch.SCHOOL_TARGET_CANDIDATE_URL_UNKNOWN, empty.schoolMatch)
        assertNull(TargetUrlNormalizer.normalize("").url)
    }

    private fun parseTarget(url: String): DevToolsTarget {
        val targetJson = JSONObject()
            .put("id", "target")
            .put("type", "page")
            .put("title", SCHOOL_TITLE)
            .put("url", url)
        return (parseDevToolsTargetList(
            "[$targetJson]",
            "@webview_devtools_remote_123",
            listOf(WeWorkProcess(123, "com.tencent.wework:wxa_container0"))
        ) as DevToolsTargetParseResult.Parsed).targets.single().target
    }
}
