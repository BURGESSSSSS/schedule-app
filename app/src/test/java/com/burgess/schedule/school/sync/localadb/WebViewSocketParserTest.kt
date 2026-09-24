package com.burgess.schedule.school.sync.localadb

import org.junit.Assert.assertEquals
import org.junit.Test

class WebViewSocketParserTest {
    @Test
    fun recognizesAbstractWebViewSocketName() {
        assertEquals(
            listOf("webview_devtools_remote_123"),
            parseWebViewSocketNames("@webview_devtools_remote_123")
        )
    }

    @Test
    fun recognizesNamedWebViewSocketFromProcRow() {
        val row = "0000000000000000: 00000002 00000000 00010000 0001 01 3154 webview_devtools_remote_456"

        assertEquals(
            listOf("webview_devtools_remote_456"),
            parseWebViewSocketNames(row)
        )
    }

    @Test
    fun ignoresOrdinaryUnixSockets() {
        val rows = """
            0000000000000000: 00000002 00000000 00010000 0001 01 1 @jdwp-control
            0000000000000000: 00000002 00000000 00010000 0001 01 2 system_server
        """.trimIndent()

        assertEquals(emptyList<String>(), parseWebViewSocketNames(rows))
    }

    @Test
    fun removesAbstractPrefixAndDeduplicatesNames() {
        val rows = """
            @webview_devtools_remote_123
            webview_devtools_remote_123
            @webview_devtools_remote_123
        """.trimIndent()

        assertEquals(listOf("webview_devtools_remote_123"), parseWebViewSocketNames(rows))
    }

    @Test
    fun emptyInputReturnsNoSockets() {
        assertEquals(emptyList<String>(), parseWebViewSocketNames(" \n\t"))
    }

    @Test
    fun recognizesCompletedSocketScanWithMatchesOrNoMatches() {
        assertEquals(0, socketScanExitCode("socket row\r\n__LOCAL_ADB_SCAN_DONE__0\r\n"))
        assertEquals(1, socketScanExitCode("__LOCAL_ADB_SCAN_DONE__1\n"))
        assertEquals(null, socketScanExitCode("socket row without completion marker"))
    }
}
