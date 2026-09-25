package com.burgess.schedule.school.sync.localadb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevToolsSocketDiscoveryTest {
    @Test
    fun selectsAbstractDevToolsAndXWebSocketsAndStripsAtOnlyForAdbService() {
        val candidates = findDevToolsSocketCandidates(
            listOf(
                socket(1, "@xweb_devtools_remote_12345"),
                socket(2, "@webview_remote_67890"),
                socket(3, "@chromium_debug_endpoint"),
                socket(4, "@ordinary_socket"),
                socket(5, "/tmp/webview_devtools_remote_999", isAbstract = false)
            )
        )

        assertEquals(
            listOf("@xweb_devtools_remote_12345", "@webview_remote_67890", "@chromium_debug_endpoint"),
            candidates.map { it.socketName }
        )
        assertEquals("xweb_devtools_remote_12345", candidates[0].adbServiceName)
        assertEquals(0, candidates[0].priority)
        assertEquals(1, candidates[1].priority)
        assertEquals(3, candidates[2].priority)
    }

    @Test
    fun devtoolsSubstringIsEnoughAndDuplicateInodesAreDeduplicated() {
        val candidates = findDevToolsSocketCandidates(
            listOf(
                socket(10, "@vendor_devtools_endpoint"),
                socket(10, "@duplicate_devtools_remote")
            )
        )

        assertEquals(1, candidates.size)
        assertEquals("@vendor_devtools_endpoint", candidates.single().socketName)
    }

    @Test
    fun emptyOrOnlyOrdinarySocketListHasNoCandidate() {
        assertTrue(findDevToolsSocketCandidates(emptyList()).isEmpty())
        assertTrue(findDevToolsSocketCandidates(listOf(socket(1, "@ordinary"))).isEmpty())
    }

    private fun socket(inode: Long, name: String, isAbstract: Boolean = name.startsWith('@')) = NamedUnixSocket(
        inode = inode,
        type = 1,
        state = 1,
        socketName = name,
        isAbstract = isAbstract
    )
}
