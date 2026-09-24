package com.burgess.schedule.school.sync.localadb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewSocketParserTest {
    @Test
    fun parsesProcUnixStatsAndMapsSocketInodesToNames() {
        val result = parseProcUnixTable(
            "Num RefCount Protocol Flags Type St Inode Path\n" +
                "0000000000000000: 00000002 00000000 00010000 0001 01 98765 @local_socket\n" +
                "0000000000000001: 00000002 00000000 00010000 0001 01 98766\n",
            exitCode = 0
        )

        assertEquals(ProcUnixScanState.PROC_UNIX_SUCCESS, result.state)
        assertEquals(UnixSocketStats(3, 1, 1), result.stats)
        assertEquals("@local_socket", result.socketNamesByInode[98765L])
        assertTrue(result.socketNamesByInode.containsKey(98766L))
        assertNull(result.socketNamesByInode[98766L])
        assertEquals(setOf(UnixSocketNameCategory.XWEB), classifyUnixSocketName("@local_xweb_channel"))
        assertEquals(setOf(UnixSocketNameCategory.OTHER), classifyUnixSocketName(result.socketNamesByInode[98766L]))
    }

    @Test
    fun processQueryOnlyReturnsExactWeWorkPackageAndItsProcesses() {
        val result = parseProcessQuery(
            "PID NAME\n" +
                "1 com.tencent.wework\n" +
                "2 com.tencent.wework:wxa_container0\n" +
                "3 com.tencent.wework.evil\n" +
                "4 other.package\n",
            exitCode = 0
        )

        assertEquals(ProcessQueryScanState.PROCESS_QUERY_SUCCESS, result.state)
        assertEquals(listOf(1, 2), result.processes.map { it.pid })
    }

    @Test
    fun missingPackageQueryReturnsEmptyAfterSuccessfulProcessListing() {
        val result = parseProcessQueryForPackage(
            "PID NAME\n1 com.tencent.wework\n",
            exitCode = 0,
            packageName = "com.burgess.schedule.__localadb_absent_probe__"
        )

        assertEquals(ProcessQueryScanState.PROCESS_QUERY_SUCCESS, result.state)
        assertTrue(result.processes.isEmpty())
    }

    @Test
    fun parsesAndDeduplicatesOnlySocketFds() {
        val result = parseProcFdScan(
            "__FD_SOCKET__|12345|8|socket:[98765]\n" +
                "__FD_SOCKET__|12345|9|socket:[98765]\n" +
                "__FD_SCAN_SUCCESS__|12345\n",
            pid = 12345,
            exitCode = 0
        )

        assertEquals(ProcFdScanState.FD_SCAN_SUCCESS, result.state)
        assertEquals(listOf(SocketFdReference(fd = 8, inode = 98765L)), result.socketFds)
    }

    @Test
    fun parsesOnlySocketFdSymlinkTargets() {
        assertEquals(12345L, parseSocketFdTarget("socket:[12345]"))
        assertNull(parseSocketFdTarget("socket:[]"))
        assertNull(parseSocketFdTarget("socket:[12345]extra"))
        assertNull(parseSocketFdTarget("pipe:[12345]"))
        assertNull(parseSocketFdTarget("socket:[12x45]"))
    }

    @Test
    fun associatesFdInodeAndSocketNameForEachWeWorkPid() {
        val processA = WeWorkProcess(12345, "com.tencent.wework")
        val processB = WeWorkProcess(12346, "com.tencent.wework:wxa_container0")
        val fdA = parseProcFdScan(
            "__FD_SOCKET__|12345|12|socket:[98765]\n__FD_SCAN_SUCCESS__|12345\n",
            12345,
            0
        )
        val fdB = parseProcFdScan(
            "__FD_SOCKET__|12346|19|socket:[98766]\n__FD_SCAN_SUCCESS__|12346\n",
            12346,
            0
        )
        val unixNames = mapOf(98765L to "@main_socket", 98766L to "@xweb_devtools_remote_12346")

        val auditA = buildProcessAudit(processA, fdA, unixNames, mapsScan = null)
        val auditB = buildProcessAudit(processB, fdB, unixNames, mapsScan = null)

        assertEquals(12, auditA.sockets.single().fd)
        assertEquals("@main_socket", auditA.sockets.single().socketName)
        assertEquals(19, auditB.sockets.single().fd)
        assertEquals("@xweb_devtools_remote_12346", auditB.sockets.single().socketName)
        assertEquals(listOf(processA, processB), listOf(auditA.process, auditB.process))
    }

    @Test
    fun recordsMultipleProcessOwnersForSocketInodes() {
        val snapshot = snapshot(
            "PID NAME\n40 com.tencent.wework\n41 com.tencent.wework:wxa_container0\n",
            mapOf(
                40 to "__FD_SOCKET__|40|7|socket:[555]\n__FD_SCAN_SUCCESS__|40\n",
                41 to "__FD_SOCKET__|41|11|socket:[555]\n__FD_SCAN_SUCCESS__|41\n"
            ),
            mapOf(555L to "@shared_socket")
        )

        assertEquals(2, ownedSocketReferences(snapshot).size)
        assertEquals(setOf("com.tencent.wework", "com.tencent.wework:wxa_container0"), ownedSocketReferences(snapshot).map { it.process.processName }.toSet())
        val baseline = snapshot("PID NAME\n", emptyMap(), emptyMap())
        assertEquals(setOf(555L), newlyObservedSocketInodes(baseline, snapshot))
    }

    @Test
    fun preservesPermissionDeniedAsItsOwnFdState() {
        val result = parseProcFdScan("__FD_SCAN_PERMISSION_DENIED__|321\n", pid = 321, exitCode = 0)

        assertEquals(ProcFdScanState.FD_SCAN_PERMISSION_DENIED, result.state)
        assertTrue(result.socketFds.isEmpty())
    }

    @Test
    fun associatesOwnedInodesWithProcUnixNames() {
        val process = WeWorkProcess(12345, "com.tencent.wework:wxa_container0")
        val fd = parseProcFdScan(
            "__FD_SOCKET__|12345|8|socket:[98765]\n__FD_SCAN_SUCCESS__|12345\n",
            12345,
            0
        )
        val audit = buildProcessAudit(
            process,
            fd,
            mapOf(98765L to "@xweb_devtools_remote_12345"),
            mapsScan = null
        )

        assertEquals("@xweb_devtools_remote_12345", audit.sockets.single().socketName)
        assertTrue(audit.sockets.single().presentInProcUnix)
    }

    @Test
    fun parsesOnlyFilteredKernelLibraryBasenames() {
        val result = parseProcMapsScan(
            "__WX_MAPS_LIBRARY__|88|libxwebcore.so|xweb,chromium\n" +
                "__WX_MAPS_LIBRARY__|88|libwebviewchromium.so|webview,chromium\n" +
                "__MAPS_SCAN_SUCCESS__|88\n",
            pid = 88,
            exitCode = 0
        )

        assertEquals(ProcMapsScanState.MAPS_SCAN_SUCCESS, result.state)
        assertEquals(listOf("libxwebcore.so", "libwebviewchromium.so"), result.libraries.map { it.basename })
        assertEquals(WebViewKernelKind.XWEB, identifyWebViewKernel(result.libraries))
        assertFalse(result.libraries.any { '/' in it.basename })
    }

    @Test
    fun mapsPermissionDeniedIsNotReportedAsSuccessfulRead() {
        val result = parseProcMapsScan("__MAPS_SCAN_PERMISSION_DENIED__|88\n", pid = 88, exitCode = 0)
        assertEquals(ProcMapsScanState.MAPS_SCAN_PERMISSION_DENIED, result.state)
        assertTrue(result.libraries.isEmpty())
    }

    @Test
    fun detectsNewProcessesAndSocketInodesBetweenSnapshots() {
        val baseline = snapshot(
            "PID NAME\n10 com.tencent.wework\n",
            mapOf(10 to "__FD_SOCKET__|10|5|socket:[100]\n__FD_SCAN_SUCCESS__|10\n"),
            mapOf(100L to "@stable_socket")
        )
        val current = snapshot(
            "PID NAME\n10 com.tencent.wework\n11 com.tencent.wework:wxa_container0\n",
            mapOf(
                10 to "__FD_SOCKET__|10|5|socket:[100]\n__FD_SOCKET__|10|6|socket:[101]\n__FD_SCAN_SUCCESS__|10\n",
                11 to "__FD_SOCKET__|11|7|socket:[102]\n__FD_SCAN_SUCCESS__|11\n"
            ),
            mapOf(100L to "@stable_socket", 101L to "@new_main_socket", 102L to "@new_wxa_socket")
        )

        assertEquals(listOf("com.tencent.wework:wxa_container0"), newlyAppearedProcesses(baseline.processes, current.processes).map { it.processName })
        assertEquals(setOf(101L, 102L), newlyObservedSocketInodes(baseline, current))
        assertEquals(2, newlyOwnedSockets(baseline, current).size)
        assertEquals(SocketOwnershipClassification.OWNED_SOCKET_DELTA_FOUND, classifySocketOwnership(current, baseline))
    }

    @Test
    fun detectsProcessesThatDisappearedBetweenSnapshots() {
        val baseline = snapshot(
            "PID NAME\n10 com.tencent.wework\n11 com.tencent.wework:wxa_container0\n",
            mapOf(
                10 to "__FD_SCAN_SUCCESS__|10\n",
                11 to "__FD_SCAN_SUCCESS__|11\n"
            ),
            emptyMap()
        )
        val current = snapshot(
            "PID NAME\n10 com.tencent.wework\n",
            mapOf(10 to "__FD_SCAN_SUCCESS__|10\n"),
            emptyMap()
        )

        assertEquals(listOf(WeWorkProcess(11, "com.tencent.wework:wxa_container0")), disappearedProcesses(baseline.processes, current.processes))
    }

    @Test
    fun doesNotCallAnExistingInodeANewSocketWhenAnotherProcessStartsHoldingIt() {
        val baseline = snapshot(
            "PID NAME\n40 com.tencent.wework\n",
            mapOf(40 to "__FD_SOCKET__|40|7|socket:[555]\n__FD_SCAN_SUCCESS__|40\n"),
            mapOf(555L to "@shared_socket")
        )
        val current = snapshot(
            "PID NAME\n40 com.tencent.wework\n41 com.tencent.wework:wxa_container0\n",
            mapOf(
                40 to "__FD_SOCKET__|40|7|socket:[555]\n__FD_SCAN_SUCCESS__|40\n",
                41 to "__FD_SOCKET__|41|11|socket:[555]\n__FD_SCAN_SUCCESS__|41\n"
            ),
            mapOf(555L to "@shared_socket")
        )

        assertEquals(1, newlyOwnedSockets(baseline, current).size)
        assertTrue(newlyObservedSocketInodes(baseline, current).isEmpty())
        assertEquals(SocketOwnershipClassification.NO_OWNED_DEVTOOLS_SOCKET, classifySocketOwnership(current, baseline))
    }

    @Test
    fun categorizesOwnedUnixSocketsByNameAndKeepsUnknownAsOther() {
        assertEquals(
            setOf(UnixSocketNameCategory.DEVTOOLS, UnixSocketNameCategory.XWEB),
            classifyUnixSocketName("@xweb_devtools_remote_12345")
        )
        assertEquals(setOf(UnixSocketNameCategory.WEBVIEW), classifyUnixSocketName("webview_renderer"))
        assertEquals(setOf(UnixSocketNameCategory.TBS_X5), classifyUnixSocketName("@tbs_x5_channel"))
        assertEquals(setOf(UnixSocketNameCategory.OTHER), classifyUnixSocketName("@ordinary_socket"))
        assertEquals(setOf(UnixSocketNameCategory.OTHER), classifyUnixSocketName(null))
    }

    @Test
    fun onlyClassifiesDevtoolsWhenOwnedSocketIsExplicitlyNamed() {
        val target = snapshot(
            "PID NAME\n200 com.tencent.wework:wxa_container0\n",
            mapOf(200 to "__FD_SOCKET__|200|8|socket:[812]\n__FD_SCAN_SUCCESS__|200\n"),
            mapOf(812L to "@xweb_devtools_remote_200")
        )
        val unrelated = snapshot(
            "PID NAME\n200 com.tencent.wework\n",
            mapOf(200 to "__FD_SOCKET__|200|9|socket:[813]\n__FD_SCAN_SUCCESS__|200\n"),
            mapOf(813L to "@com.android.internal.os.WebViewZygoteInit/worker")
        )

        assertEquals(SocketOwnershipClassification.OWNED_DEVTOOLS_SOCKET_FOUND, classifySocketOwnership(target, null))
        assertEquals(SocketOwnershipClassification.NO_OWNED_DEVTOOLS_SOCKET, classifySocketOwnership(unrelated, null))
    }

    @Test
    fun reportsFdPermissionAsPermissionDeniedAndOtherIncompleteScansAsInconclusive() {
        val denied = snapshot(
            "PID NAME\n321 com.tencent.wework:wxa_container0\n",
            mapOf(321 to "__FD_SCAN_PERMISSION_DENIED__|321\n"),
            emptyMap()
        )
        val failed = snapshot(
            "PID NAME\n322 com.tencent.wework\n",
            mapOf(322 to "__FD_SCAN_FAILED__|322|PROCESS_GONE\n"),
            emptyMap()
        )
        val noProcesses = snapshot("PID NAME\n", emptyMap(), emptyMap())

        assertEquals(SocketOwnershipClassification.PROC_FD_PERMISSION_DENIED, classifySocketOwnership(denied, null))
        assertEquals(SocketOwnershipClassification.PROC_FD_PERMISSION_DENIED, classifySocketOwnership(noProcesses, denied))
        assertEquals(SocketOwnershipClassification.INCONCLUSIVE, classifySocketOwnership(failed, null))
        assertEquals(SocketOwnershipClassification.INCONCLUSIVE, classifySocketOwnership(noProcesses, null))
    }

    @Test
    fun failedSnapshotsCannotProduceProcessOrSocketDeltas() {
        val baseline = snapshot(
            "PID NAME\n10 com.tencent.wework\n",
            mapOf(10 to "__FD_SCAN_SUCCESS__|10\n"),
            emptyMap()
        )
        val failedCurrentQuery = snapshot(
            "PID NAME\n",
            emptyMap(),
            emptyMap()
        ).copy(processQueryState = ProcessQueryScanState.PROCESS_QUERY_FAILED)
        val deniedCurrentFd = snapshot(
            "PID NAME\n11 com.tencent.wework:wxa_container0\n",
            mapOf(11 to "__FD_SCAN_PERMISSION_DENIED__|11\n"),
            emptyMap()
        )
        val noCurrentProcess = snapshot("PID NAME\n", emptyMap(), emptyMap())
        val validCurrent = snapshot(
            "PID NAME\n11 com.tencent.wework:wxa_container0\n",
            mapOf(11 to "__FD_SCAN_SUCCESS__|11\n"),
            emptyMap()
        )

        assertFalse(canCompareProcessSnapshots(baseline, failedCurrentQuery))
        assertFalse(canCompareSocketSnapshots(baseline, failedCurrentQuery))
        assertFalse(canCompareSocketSnapshots(baseline, deniedCurrentFd))
        assertFalse(canCompareSocketSnapshots(baseline, noCurrentProcess))
        assertTrue(canCompareProcessSnapshots(baseline, validCurrent))
        assertTrue(canCompareSocketSnapshots(baseline, validCurrent))
    }

    @Test
    fun reportsProcAndProcessQueryFailuresSeparately() {
        val badUnix = parseProcUnixTable("not a proc unix table\n", exitCode = 0)
        val badQuery = parseProcessQuery("", exitCode = 1)

        assertEquals(ProcUnixScanState.PROC_UNIX_FAILED, badUnix.state)
        assertEquals(ProcessQueryScanState.PROCESS_QUERY_FAILED, badQuery.state)
    }

    private fun snapshot(
        processOutput: String,
        fdOutputByPid: Map<Int, String>,
        namesByInode: Map<Long, String?>
    ): LocalAdbScanSnapshot {
        val processQuery = parseProcessQuery(processOutput, exitCode = 0)
        val audits = processQuery.processes.map { process ->
            val fd = parseProcFdScan(fdOutputByPid.getValue(process.pid), process.pid, exitCode = 0)
            buildProcessAudit(process, fd, namesByInode, mapsScan = null)
        }
        return LocalAdbScanSnapshot(
            stats = UnixSocketStats(1 + namesByInode.size, namesByInode.values.count { it != null }, namesByInode.values.count { it?.startsWith("@") == true }),
            processes = audits,
            procUnixState = ProcUnixScanState.PROC_UNIX_SUCCESS,
            processQueryState = processQuery.state
        )
    }
}
