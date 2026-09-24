package com.burgess.schedule.school.sync.localadb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAdbUidCheckTest {
    @Test
    fun recognizesShellUid2000FromIdOutput() {
        assertTrue(isShellUid2000("uid=2000(shell) gid=2000(shell) groups=1003,3003"))
    }

    @Test
    fun rejectsNonShellAndPartialUidMatches() {
        assertFalse(isShellUid2000("uid=10000(u0_a123) gid=10000(u0_a123)"))
        assertFalse(isShellUid2000("uid=2000(system) gid=2000(system)"))
        assertFalse(isShellUid2000("uid=20000(shell)"))
        assertFalse(isShellUid2000("uid=2000"))
    }
}
