package com.burgess.schedule.school.sync.localadb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewSocketParserTest {
    @Test
    fun parsesUnixStatsProcessFdInodeAndSocketName() {
        val result = parse(scan(
            "__WXWORK_PROCESS__|12345|com.tencent.wework",
            "__FD_SOCKET__|12345|98765",
            "__OWNED_SOCKET__|12345|98765|1|@local_socket_name",
            "__PROC_FD_OK__|12345"
        ))

        assertEquals(UnixSocketStats(92, 54, 21), result.stats)
        assertFalse(result.procUnixReadFailed)
        assertEquals(1, result.processes.size)
        val process = result.processes.single()
        assertEquals(12345, process.process.pid)
        assertEquals("com.tencent.wework", process.process.processName)
        assertEquals(ProcFdScanState.ENUMERATED, process.fdScanState)
        assertEquals(98765L, process.sockets.single().inode)
        assertTrue(process.sockets.single().presentInProcUnix)
        assertEquals("@local_socket_name", process.sockets.single().socketName)
    }

    @Test
    fun acceptsOnlyTheWeWorkPackageAndItsNamedProcesses() {
        val result = parse(scan(
            "__WXWORK_PROCESS__|1|com.tencent.wework",
            "__WXWORK_PROCESS__|2|com.tencent.wework:wxa_container0",
            "__WXWORK_PROCESS__|3|com.tencent.wework.evil",
            "__WXWORK_PROCESS__|4|other.package"
        ))

        assertEquals(listOf(1, 2), result.processes.map { it.process.pid })
    }

    @Test
    fun deduplicatesRepeatedSocketInodesPerProcess() {
        val result = parse(scan(
            "__WXWORK_PROCESS__|77|com.tencent.wework",
            "__OWNED_SOCKET__|77|456|1|@first_name",
            "__OWNED_SOCKET__|77|456|1|@duplicate_name",
            "__PROC_FD_OK__|77"
        ))

        assertEquals(1, result.processes.single().sockets.size)
        assertEquals("@first_name", result.processes.single().sockets.single().socketName)
    }

    @Test
    fun preservesUnnamedSocketAsPresentWithoutInventingAName() {
        val result = parse(scan(
            "__WXWORK_PROCESS__|78|com.tencent.wework",
            "__OWNED_SOCKET__|78|457|1|",
            "__PROC_FD_OK__|78"
        ))

        val socket = result.processes.single().sockets.single()
        assertTrue(socket.presentInProcUnix)
        assertNull(socket.socketName)
    }

    @Test
    fun preservesPermissionDeniedAndClassifiesItExplicitly() {
        val result = parse(scan(
            "__WXWORK_PROCESS__|321|com.tencent.wework:wxa_container0",
            "__PROC_FD_PERMISSION_DENIED__|321"
        ))

        assertEquals(ProcFdScanState.PERMISSION_DENIED, result.processes.single().fdScanState)
        assertEquals(
            SocketOwnershipClassification.PROC_FD_PERMISSION_DENIED,
            classifySocketOwnership(result, baseline = null)
        )
    }

    @Test
    fun invalidBaselineIsNotTreatedAsACompleteNegativeComparison() {
        val deniedBaseline = parse(scan(
            "__WXWORK_PROCESS__|330|com.tencent.wework",
            "__PROC_FD_PERMISSION_DENIED__|330"
        ))
        val readableCurrent = parse(scan(
            "__WXWORK_PROCESS__|330|com.tencent.wework",
            "__PROC_FD_OK__|330"
        ))

        assertEquals(
            SocketOwnershipClassification.PROC_FD_PERMISSION_DENIED,
            classifySocketOwnership(readableCurrent, deniedBaseline)
        )
    }

    @Test
    fun distinguishesOwnedDevtoolsSocketFromUnrelatedWebViewNamedSocket() {
        val debugSocket = parse(scan(
            "__WXWORK_PROCESS__|200|com.tencent.wework:wxa_container0",
            "__OWNED_SOCKET__|200|812|1|@xweb_devtools_remote_200",
            "__PROC_FD_OK__|200"
        ))
        val zygoteSocket = parse(scan(
            "__WXWORK_PROCESS__|200|com.tencent.wework",
            "__OWNED_SOCKET__|200|813|1|@com.android.internal.os.WebViewZygoteInit/worker",
            "__PROC_FD_OK__|200"
        ))

        assertEquals(
            SocketOwnershipClassification.OWNED_DEVTOOLS_SOCKET_FOUND,
            classifySocketOwnership(debugSocket, baseline = null)
        )
        assertEquals(
            SocketOwnershipClassification.NO_OWNED_DEVTOOLS_SOCKET,
            classifySocketOwnership(zygoteSocket, baseline = null)
        )
    }

    @Test
    fun detectsNewOwnedUnixSocketAndAbaChangesByPidAndInode() {
        val baseline = parse(scan(
            "__WXWORK_PROCESS__|10|com.tencent.wework",
            "__OWNED_SOCKET__|10|100|1|@stable_socket",
            "__PROC_FD_OK__|10"
        ))
        val current = parse(scan(
            "__WXWORK_PROCESS__|10|com.tencent.wework",
            "__OWNED_SOCKET__|10|100|1|@stable_socket",
            "__OWNED_SOCKET__|10|101|1|@new_main_socket",
            "__PROC_FD_OK__|10",
            "__WXWORK_PROCESS__|11|com.tencent.wework:wxa_container0",
            "__OWNED_SOCKET__|11|102|1|@new_wxa_socket",
            "__PROC_FD_OK__|11"
        ))

        assertEquals(listOf("com.tencent.wework:wxa_container0"), newlyAppearedProcesses(baseline.processes, current.processes).map { it.processName })
        assertEquals(setOf(101L, 102L), newlyObservedSocketInodes(baseline, current))
        assertEquals(2, newlyOwnedSockets(baseline, current).size)
        assertEquals(
            SocketOwnershipClassification.OWNED_UNIX_SOCKET_FOUND,
            classifySocketOwnership(current, baseline)
        )
    }

    @Test
    fun noOwnedDevtoolsSocketRequiresSuccessfulFdEnumeration() {
        val baseline = parse(scan(
            "__WXWORK_PROCESS__|20|com.tencent.wework",
            "__PROC_FD_OK__|20"
        ))
        val current = parse(scan(
            "__WXWORK_PROCESS__|20|com.tencent.wework",
            "__OWNED_SOCKET__|20|200|1|@ordinary_socket",
            "__PROC_FD_OK__|20"
        ))

        assertEquals(
            SocketOwnershipClassification.NO_OWNED_DEVTOOLS_SOCKET,
            classifySocketOwnership(baseline, baseline)
        )
        assertEquals(
            SocketOwnershipClassification.OWNED_UNIX_SOCKET_FOUND,
            classifySocketOwnership(current, baseline)
        )
    }

    @Test
    fun mapsOnlyExposeMatchedBasenameAndKeywordsAndInferKernelHint() {
        val result = parse(scan(
            "__WXWORK_PROCESS__|88|com.tencent.wework:wxa_container0",
            "__PROC_FD_OK__|88",
            "__WX_MAPS_LIBRARY__|88|libxwebcore.so|xweb,chromium",
            "__WX_MAPS_LIBRARY__|88|libwebviewchromium.so|webview,chromium",
            "__WX_MAPS_LIBRARY__|88|/private/full/path/libsecret.so|secret",
            "__PROC_MAPS_READ_OK__|88"
        ))
        val process = result.processes.single()

        assertEquals(ProcMapsScanState.READABLE, process.mapsScanState)
        assertEquals(listOf("libxwebcore.so", "libwebviewchromium.so"), process.libraries.map { it.basename })
        assertEquals(WebViewKernelKind.XWEB, identifyWebViewKernel(process.libraries))
        assertFalse(process.libraries.any { '/' in it.basename })
        assertFalse(process.libraries.any { "secret" in it.keywords })
    }

    @Test
    fun classifiesSystemWebViewTbsChromiumAndUnknownLibraries() {
        assertEquals(
            WebViewKernelKind.ANDROID_SYSTEM_WEBVIEW,
            identifyWebViewKernel(listOf(WebViewKernelLibrary("libwebviewchromium.so", setOf("webview"))))
        )
        assertEquals(
            WebViewKernelKind.TBS_X5,
            identifyWebViewKernel(listOf(WebViewKernelLibrary("libtbs.so", setOf("tbs"))))
        )
        assertEquals(
            WebViewKernelKind.TBS_X5,
            identifyWebViewKernel(listOf(WebViewKernelLibrary("libx5.so", setOf("x5"))))
        )
        assertEquals(
            WebViewKernelKind.OTHER_CHROMIUM,
            identifyWebViewKernel(listOf(WebViewKernelLibrary("libchromium.so", setOf("chromium"))))
        )
        assertEquals(WebViewKernelKind.UNKNOWN, identifyWebViewKernel(emptyList()))
    }

    @Test
    fun reportsProcUnixAndProcessQueryFailuresSeparately() {
        val badUnix = parse(
            "__PROC_UNIX_SUMMARY__|0|0|0\n" +
                "__PROC_UNIX_EXIT__|1\n" +
                "__PROC_UNIX_MAP_FAILED__\n" +
                "__WXWORK_QUERY_DONE__\n" +
                "__LOCAL_ADB_SCAN_DONE__"
        )
        val badProcessQuery = parse(scan("__WXWORK_QUERY_FAILED__"))

        assertTrue(badUnix.procUnixReadFailed)
        assertEquals(
            SocketOwnershipClassification.PROC_UNIX_READ_FAILED,
            classifySocketOwnership(badUnix, baseline = null)
        )
        assertTrue(badProcessQuery.processQueryFailed)
        assertEquals(
            SocketOwnershipClassification.PROCESS_QUERY_FAILED,
            classifySocketOwnership(badProcessQuery, baseline = null)
        )
    }

    @Test
    fun retainsProcUnixLineCountsWhenInodeMappingFails() {
        val result = parse(
            "__PROC_UNIX_SUMMARY__|92|54|21\n" +
                "__PROC_UNIX_EXIT__|0\n" +
                "__PROC_UNIX_MAP_FAILED__\n" +
                "__WXWORK_QUERY_DONE__\n" +
                "__LOCAL_ADB_SCAN_DONE__"
        )

        assertEquals(UnixSocketStats(92, 54, 21), result.stats)
        assertTrue(result.procUnixReadFailed)
    }

    @Test
    fun missingProcessIsNotReportedAsNoDevtoolsSocket() {
        val result = parse(scan())

        assertTrue(result.processes.isEmpty())
        assertEquals(
            SocketOwnershipClassification.PROCESS_NOT_FOUND,
            classifySocketOwnership(result, baseline = null)
        )
        assertNull(result.processes.firstOrNull())
    }

    private fun parse(output: String) = parseLocalAdbScanSnapshot(output)

    private fun scan(vararg records: String): String = listOf(
        "__PROC_UNIX_MAP_OK__",
        "__PROC_UNIX_SUMMARY__|92|54|21",
        "__PROC_UNIX_EXIT__|0",
        *records,
        "__WXWORK_QUERY_DONE__",
        "__LOCAL_ADB_SCAN_DONE__"
    ).joinToString("\n")
}
