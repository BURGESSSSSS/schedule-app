package com.burgess.schedule.school.sync.localadb

import org.junit.Assert.assertEquals
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

    @Test
    fun classifiesOrdinaryShellUidOutputAsVerified() {
        val result = evaluateLocalAdbUidCheck("uid=2000(shell) gid=2000(shell) groups=1003,3003")

        assertEquals(LocalAdbCheckState.ID_EXECUTED, result.idState)
        assertTrue(result.uidVerified)
        assertEquals("UID_2000_VERIFIED", result.uidStatus)
    }

    @Test
    fun recognizesUidAfterShellPromptEchoAndCrLf() {
        val result = evaluateLocalAdbUidCheck(
            "toysh:/ \$ id\r\nuid=2000(shell) gid=2000(shell)\r\ntoysh:/ \$ "
        )

        assertEquals(LocalAdbCheckState.ID_EXECUTED, result.idState)
        assertTrue(result.uidVerified)
    }

    @Test
    fun waitsForUidFieldDelimiterAcrossReadChunks() {
        val prefix = "toysh:/ \$ id\r\nuid=2000"

        assertFalse(hasCompleteUidField(prefix))
        assertFalse(hasCompleteUidField("$prefix(shell)"))
        assertTrue(hasCompleteUidField("$prefix(shell)\r\n"))
        assertTrue(hasCompleteUidField("toysh:/ \$ id\r\nuid=2000\r\n"))
    }

    @Test
    fun alreadyReadShellUidWinsOverStreamClosedError() {
        val result = evaluateLocalAdbUidCheck(
            "uid=2000(shell) gid=2000(shell)",
            LocalAdbReadIssue.STREAM_CLOSED,
            "IOException: Stream closed."
        )

        assertEquals(LocalAdbCheckState.ID_EXECUTED, result.idState)
        assertTrue(result.uidVerified)
        assertEquals(null, result.errorMessage)
    }

    @Test
    fun streamClosedWithoutUidFails() {
        val result = evaluateLocalAdbUidCheck(
            "toysh:/ \$ id\r\n",
            LocalAdbReadIssue.STREAM_CLOSED,
            "IOException: Stream closed."
        )

        assertEquals(LocalAdbCheckState.ID_FAILED, result.idState)
        assertFalse(result.uidVerified)
        assertEquals("IOException: Stream closed.", result.errorMessage)
    }

    @Test
    fun nonShellUidMeansCommandExecutedButUidUnverified() {
        val result = evaluateLocalAdbUidCheck("uid=10000(u0_a123) gid=10000(u0_a123)")

        assertEquals(LocalAdbCheckState.ID_EXECUTED, result.idState)
        assertFalse(result.uidVerified)
        assertTrue(result.uidStatus.contains("10000 (u0_a123)"))
    }

    @Test
    fun uidNumberWithoutNameMeansExecutedButCannotVerifyShellIdentity() {
        val result = evaluateLocalAdbUidCheck(
            "toysh:/ \$ id\r\nuid=2000",
            LocalAdbReadIssue.TIMEOUT,
            "TimeoutCancellationException: Timed out waiting for id output."
        )

        assertEquals(LocalAdbCheckState.ID_EXECUTED, result.idState)
        assertFalse(result.uidVerified)
        assertEquals("UID_2000_NOT_VERIFIED（实际 UID：2000）", result.uidStatus)
        assertEquals(null, result.errorMessage)
    }
}
