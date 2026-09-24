package com.burgess.schedule.school.sync.localadb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalUnixSocketDiffTest {
    @Test
    fun parsesAllNamedSocketFieldsAndKeepsAbstractFlag() {
        val result = parseGlobalUnixSocketTable(
            """Num       RefCount Protocol Flags    Type St Inode Path
               00000000: 00000002 00000000 00010000 0001 01 98765 @xweb_devtools_remote_12345
               00000001: 00000002 00000000 00010000 0001 03 98766 /dev/socket/example
               00000002: 00000002 00000000 00010000 0001 01 98767
            """.trimIndent(),
            exitCode = 0
        )

        assertEquals(ProcUnixScanState.PROC_UNIX_SUCCESS, result.state)
        assertEquals(2, result.sockets.size)
        assertEquals(4, result.stats?.totalLines)
        assertEquals(2, result.stats?.namedSockets)
        assertEquals(1, result.stats?.abstractSockets)
        val abstractSocket = result.sockets.first()
        assertEquals(98765L, abstractSocket.inode)
        assertEquals(1, abstractSocket.type)
        assertEquals(1, abstractSocket.state)
        assertTrue(abstractSocket.isAbstract)
        assertEquals("@xweb_devtools_remote_12345", abstractSocket.socketName)
        val fileSocket = result.sockets.last()
        assertEquals(3, fileSocket.state)
        assertFalse(fileSocket.isAbstract)
        assertEquals("/dev/socket/example", fileSocket.socketName)
    }

    @Test
    fun ignoresUnnamedRowsAndDeduplicatesByInode() {
        val result = parseGlobalUnixSocketTable(
            """Num RefCount Protocol Flags Type St Inode Path
               00000000: 00000002 00000000 00010000 0001 01 100 @named
               00000001: 00000002 00000000 00010000 0001 01 100 @named_duplicate
               00000002: 00000002 00000000 00010000 0001 01 101
            """.trimIndent(),
            exitCode = 0
        )

        assertEquals(1, result.sockets.size)
        assertEquals(100L, result.sockets.single().inode)
        assertEquals("@named", result.sockets.single().socketName)
    }

    @Test
    fun rejectsMissingHeaderOrNonzeroExitCode() {
        assertEquals(ProcUnixScanState.PROC_UNIX_FAILED, parseGlobalUnixSocketTable("not a unix table", 0).state)
        assertEquals(
            ProcUnixScanState.PROC_UNIX_FAILED,
            parseGlobalUnixSocketTable("Num RefCount Protocol Flags Type St Inode Path", 1).state
        )
    }

    @Test
    fun calculatesPerCycleAddedAndRemovedSocketInstances() {
        val stable = socket(10, "@stable")
        val removed = socket(11, "/dev/socket/gone")
        val newSocket = socket(12, "@new")
        val delta = diffNamedUnixSockets(
            before = listOf(stable, removed),
            after = listOf(stable, newSocket)
        )

        assertEquals(listOf(newSocket), delta.added)
        assertEquals(listOf(removed), delta.removed)
    }

    @Test
    fun findsStableAddedNameAcrossCyclesEvenWhenKernelInodeChanges() {
        val unchanged = socket(1, "@persistent")
        val firstRun = socket(201, "@xweb_devtools_remote_12345")
        val secondRun = socket(887, "@xweb_devtools_remote_12345")
        val diff = repeatedSocketDiff(
            a1 = listOf(unchanged),
            b1 = listOf(unchanged, firstRun),
            a2 = listOf(unchanged),
            b2 = listOf(unchanged, secondRun)
        )

        assertEquals(1, diff.firstCycle.added.size)
        assertEquals(1, diff.secondCycle.added.size)
        assertEquals(1, diff.stableAdded.size)
        assertEquals(firstRun, diff.stableAdded.single().firstCycle)
        assertEquals(secondRun, diff.stableAdded.single().secondCycle)
        assertEquals(0, diff.randomAddedCount)
    }

    @Test
    fun reportsOneOffAddedNamesAsRandomCandidates() {
        val diff = repeatedSocketDiff(
            a1 = emptyList(),
            b1 = listOf(socket(1, "@only_cycle_one")),
            a2 = emptyList(),
            b2 = listOf(socket(2, "@only_cycle_two"))
        )

        assertTrue(diff.stableAdded.isEmpty())
        assertEquals(2, diff.randomAddedCount)
        assertEquals("@only_cycle_one", diff.randomAddedFirstCycle.single().socketName)
        assertEquals("@only_cycle_two", diff.randomAddedSecondCycle.single().socketName)
    }

    @Test
    fun categorizesAllRequestedNameFamiliesWithoutDroppingOther() {
        assertEquals(setOf(GlobalSocketNameCategory.DEVTOOLS), classifyGlobalSocketName("@devtools_remote_12345"))
        assertEquals(setOf(GlobalSocketNameCategory.XWEB), classifyGlobalSocketName("@XWEB_channel"))
        assertEquals(setOf(GlobalSocketNameCategory.WEBVIEW), classifyGlobalSocketName("webview_local"))
        assertEquals(setOf(GlobalSocketNameCategory.TBS_X5), classifyGlobalSocketName("/tmp/tbs_x5.sock"))
        assertEquals(setOf(GlobalSocketNameCategory.WEWORK), classifyGlobalSocketName("@wework_channel"))
        assertEquals(setOf(GlobalSocketNameCategory.OTHER), classifyGlobalSocketName("@unclassified_socket"))
        assertEquals(
            setOf(GlobalSocketNameCategory.XWEB, GlobalSocketNameCategory.DEVTOOLS),
            classifyGlobalSocketName("@xweb_devtools_remote")
        )
    }

    @Test
    fun detectsPidStyleSuffixAndExactCurrentWeWorkPidMatch() {
        val processes = listOf(WeWorkProcess(12345, "com.tencent.wework:wxa_container0"))

        assertEquals(12345, pidStyleSuffix("@xweb_devtools_remote_12345"))
        assertEquals(12345, pidStyleSuffix("/tmp/socket-12345"))
        assertNull(pidStyleSuffix("@socket_12345_extra"))
        assertTrue(socketNameMatchesWeWorkPid("@xweb_devtools_remote_12345", processes))
        assertFalse(socketNameMatchesWeWorkPid("@xweb_devtools_remote_123456", processes))
    }

    @Test
    fun onlyClassifiesDevtoolsLikeWhenStableNamesContainRecognizedHints() {
        val snapshots = List(4) { globalSnapshot() }
        val debugDiff = repeatedSocketDiff(
            emptyList(), listOf(socket(2, "@xweb_remote")),
            emptyList(), listOf(socket(20, "@xweb_remote"))
        )
        val ordinaryDiff = repeatedSocketDiff(
            emptyList(), listOf(socket(3, "@local_channel")),
            emptyList(), listOf(socket(30, "@local_channel"))
        )

        assertEquals(
            GlobalSocketExperimentClassification.DEVTOOLS_LIKE_DELTA_FOUND,
            classifyGlobalSocketExperiment(snapshots, debugDiff)
        )
        assertEquals(
            GlobalSocketExperimentClassification.STABLE_SOCKET_DELTA_FOUND,
            classifyGlobalSocketExperiment(snapshots, ordinaryDiff)
        )
        assertEquals(
            GlobalSocketExperimentClassification.NO_STABLE_SOCKET_DELTA,
            classifyGlobalSocketExperiment(snapshots, repeatedSocketDiff(emptyList(), emptyList(), emptyList(), emptyList()))
        )
    }

    @Test
    fun failedProcUnixSnapshotCannotProduceAConfidentClassification() {
        val snapshots = List(4) { globalSnapshot() }.toMutableList()
        snapshots[2] = snapshots[2].copy(procUnixState = ProcUnixScanState.PROC_UNIX_FAILED)

        assertFalse(canCompareGlobalSocketSnapshots(snapshots))
        assertEquals(
            GlobalSocketExperimentClassification.PROC_UNIX_READ_FAILED,
            classifyGlobalSocketExperiment(
                snapshots,
                repeatedSocketDiff(emptyList(), emptyList(), emptyList(), emptyList())
            )
        )
    }

    @Test
    fun processDiffSeparatesAddedRemovedAndQueryFailure() {
        val before = listOf(WeWorkProcess(10, "com.tencent.wework"))
        val after = listOf(WeWorkProcess(10, "com.tencent.wework"), WeWorkProcess(11, "com.tencent.wework:push"))

        val delta = diffWeWorkProcesses(
            ProcessQueryScanState.PROCESS_QUERY_SUCCESS,
            before,
            ProcessQueryScanState.PROCESS_QUERY_SUCCESS,
            after
        )
        assertEquals(listOf(WeWorkProcess(11, "com.tencent.wework:push")), delta?.added)
        assertTrue(delta?.removed.orEmpty().isEmpty())
        assertNull(
            diffWeWorkProcesses(
                ProcessQueryScanState.PROCESS_QUERY_FAILED,
                before,
                ProcessQueryScanState.PROCESS_QUERY_SUCCESS,
                after
            )
        )
    }

    private fun socket(inode: Long, name: String) = NamedUnixSocket(
        inode = inode,
        type = 1,
        state = 1,
        socketName = name,
        isAbstract = name.startsWith('@')
    )

    private fun globalSnapshot() = GlobalSocketSnapshot(
        stats = GlobalUnixSocketStats(totalLines = 1, namedSockets = 0, abstractSockets = 0),
        sockets = emptyList(),
        processes = emptyList(),
        procUnixState = ProcUnixScanState.PROC_UNIX_SUCCESS,
        processQueryState = ProcessQueryScanState.PROCESS_QUERY_SUCCESS
    )
}
