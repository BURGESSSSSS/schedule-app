package com.burgess.schedule.school.sync.localadb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewSocketParserTest {
    @Test
    fun recognizesXWebDevtoolsSocketAndPidSuffix() {
        val socket = parseDevtoolsSocketRecords(
            "__DEVTOOLS_SOCKET__|@xweb_devtools_remote_12345"
        ).single()

        assertEquals("@xweb_devtools_remote_12345", socket.name)
        assertEquals(DevtoolsSocketKind.XWEB, socket.kind)
        assertEquals(12345, socket.pidSuffix)
    }

    @Test
    fun recognizesWebViewDevtoolsSocketAndPidSuffix() {
        val socket = parseDevtoolsSocketRecords(
            "__DEVTOOLS_SOCKET__|@webview_devtools_remote_67890"
        ).single()

        assertEquals(DevtoolsSocketKind.WEBVIEW, socket.kind)
        assertEquals(67890, socket.pidSuffix)
    }

    @Test
    fun classifiesOtherDevtoolsAndTbsCandidates() {
        val sockets = parseDevtoolsSocketRecords(
            """
                __DEVTOOLS_SOCKET__|@devtools_remote_111
                __DEVTOOLS_SOCKET__|@tbs_debug_endpoint
            """.trimIndent()
        )

        assertEquals(
            listOf(DevtoolsSocketKind.OTHER, DevtoolsSocketKind.OTHER),
            sockets.map(DevtoolsSocket::kind)
        )
    }

    @Test
    fun ignoresOrdinaryUnixSocketNames() {
        assertTrue(
            parseDevtoolsSocketRecords(
                """
                    __DEVTOOLS_SOCKET__|@jdwp-control
                    __DEVTOOLS_SOCKET__|system_server
                """.trimIndent()
            ).isEmpty()
        )
    }

    @Test
    fun matchesSocketPidOnlyToTheTargetPackageProcess() {
        val sockets = parseDevtoolsSocketRecords(
            "__DEVTOOLS_SOCKET__|@xweb_devtools_remote_12345"
        )
        val processes = parseWeWorkProcessRecords(
            """
                __WXWORK_PROCESS__|12345|com.tencent.wework
                __WXWORK_PROCESS__|45678|com.tencent.wework:tools
                __WXWORK_PROCESS__|12345|com.example.other
            """.trimIndent()
        )

        assertEquals(
            listOf(WeWorkProcess(12345, "com.tencent.wework"), WeWorkProcess(45678, "com.tencent.wework:tools")),
            processes
        )
        assertEquals(
            listOf(SocketPidMatch("@xweb_devtools_remote_12345", WeWorkProcess(12345, "com.tencent.wework"))),
            matchSocketPids(sockets, processes)
        )
        assertTrue(
            matchSocketPids(sockets, listOf(WeWorkProcess(45678, "com.tencent.wework:tools"))).isEmpty()
        )
        assertFalse(isWeWorkProcessName("com.tencent.wework.evil"))
    }

    @Test
    fun deduplicatesSocketNamesCaseInsensitively() {
        val sockets = parseDevtoolsSocketRecords(
            """
                __DEVTOOLS_SOCKET__|@XWeb_Devtools_Remote_12345
                __DEVTOOLS_SOCKET__|@xweb_devtools_remote_12345
            """.trimIndent()
        )

        assertEquals(1, sockets.size)
        assertEquals("@XWeb_Devtools_Remote_12345", sockets.single().name)
    }

    @Test
    fun parsesProcStatsAndSuccessfulScanMarkers() {
        val scan = parseLocalAdbScanSnapshot(
            """
                __DEVTOOLS_SOCKET__|@xweb_devtools_remote_12345
                __PROC_UNIX_SUMMARY__|80|62|23
                __PROC_UNIX_EXIT__|0
                __WXWORK_PROCESS__|12345|com.tencent.wework
                __WXWORK_QUERY_DONE__
                __LOCAL_ADB_SCAN_DONE__
            """.trimIndent()
        )

        assertEquals(UnixSocketStats(80, 62, 23), scan.stats)
        assertEquals(1, scan.sockets.size)
        assertEquals(1, scan.weWorkProcesses.size)
        assertEquals(1, scan.pidMatches.size)
        assertFalse(scan.procUnixReadFailed)
        assertFalse(scan.processQueryFailed)
    }

    @Test
    fun emptyInputIsMarkedAsProcReadFailure() {
        val scan = parseLocalAdbScanSnapshot("")

        assertEquals(null, scan.stats)
        assertTrue(scan.sockets.isEmpty())
        assertTrue(scan.weWorkProcesses.isEmpty())
        assertTrue(scan.procUnixReadFailed)
        assertTrue(scan.processQueryFailed)
    }

    @Test
    fun nonzeroProcReadStatusIsMarkedAsFailure() {
        val scan = parseLocalAdbScanSnapshot(
            """
                __PROC_UNIX_SUMMARY__|0|0|0
                __PROC_UNIX_EXIT__|2
                __WXWORK_QUERY_DONE__
                __LOCAL_ADB_SCAN_DONE__
            """.trimIndent()
        )

        assertTrue(scan.procUnixReadFailed)
    }

    @Test
    fun findsNewSocketNamesBetweenAbAndCurrentScans() {
        val baseline = parseDevtoolsSocketRecords(
            "__DEVTOOLS_SOCKET__|@xweb_devtools_remote_100"
        )
        val current = parseDevtoolsSocketRecords(
            """
                __DEVTOOLS_SOCKET__|@xweb_devtools_remote_100
                __DEVTOOLS_SOCKET__|@xweb_devtools_remote_12345
            """.trimIndent()
        )

        assertEquals(
            listOf("@xweb_devtools_remote_12345"),
            newlyAppearedSockets(baseline, current).map(DevtoolsSocket::name)
        )
    }
}
