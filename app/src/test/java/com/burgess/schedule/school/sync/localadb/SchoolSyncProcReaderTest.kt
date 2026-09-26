package com.burgess.schedule.school.sync.localadb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchoolSyncProcReaderTest {
    @Test
    fun parsesOnlyNamedUnixSocketsAfterHeader() {
        val parsed = parseNamedUnixSockets(
            "Num RefCount Protocol Flags Type St Inode Path\n" +
                "000: 00000002 00000000 00010000 0001 01 17 @webview_devtools_remote_17\n" +
                "001: 00000002 00000000 00010000 0001 01 17 @duplicate",
            0
        )
        assertTrue(parsed.successful)
        assertEquals(1, parsed.sockets.size)
        assertEquals("@webview_devtools_remote_17", parsed.sockets.single().socketName)
        assertTrue(parsed.sockets.single().isAbstract)
    }

    @Test
    fun requiresAValidTableAndZeroExit() {
        assertFalse(parseNamedUnixSockets("", 0).successful)
        assertFalse(parseNamedUnixSockets("Num RefCount Protocol Flags Type St Inode Path", 1).successful)
    }

    @Test
    fun processQueryKeepsOnlyWeWorkProcesses() {
        val result = parseWeWorkProcessList(
            "PID NAME\n10 com.tencent.wework\n11 com.tencent.wework:wxa_container0\n12 com.example.other",
            0
        )
        assertEquals(listOf("com.tencent.wework", "com.tencent.wework:wxa_container0"), result?.map { it.processName })
        assertNull(parseWeWorkProcessList("", 0))
    }
}
