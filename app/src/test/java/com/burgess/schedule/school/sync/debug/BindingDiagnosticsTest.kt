package com.burgess.schedule.school.sync.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BindingDiagnosticsTest {
    private val initial = BindingDiagnostics("com.burgess.schedule", "com.burgess.schedule", "Service", "Component")

    @Test fun onlyShellUidCanBeVerified() {
        val shell = initial.afterUid(2000)
        assertTrue(shell.verified)
        assertEquals(BindingStage.UID_VERIFIED, shell.stage)

        val root = initial.afterUid(0)
        assertFalse(root.verified)
        assertEquals(0, root.uid)
        assertEquals(BindingStage.BIND_EXCEPTION, root.stage)
    }

    @Test fun exceptionMessageOmitsUrlParametersAndCredentialValues() {
        val message = safeDiagnosticMessage("failed https://testjwgl.sdbi.edu.cn/h5/index.html?code=private&state=private Token=hidden Authorization: Bearer secret")!!
        assertFalse(message.contains("private"))
        assertFalse(message.contains("hidden"))
        assertFalse(message.contains("secret"))
        assertTrue(message.contains("[URL redacted]"))
    }
}
