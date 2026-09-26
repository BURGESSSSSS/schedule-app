package com.burgess.schedule.school.sync.localadb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAdbProbeOutputTest {
    @Test
    fun acceptsOnlyExactTestStdoutAndZeroExitCode() {
        val result = analyzePrintfTestOutput("test", exitCode = 0)

        assertTrue(result.succeeded)
        assertEquals("test", result.stdout)
        assertFalse(result.commandEchoDetected)
        assertFalse(result.shellPromptDetected)
    }

    @Test
    fun leavesCommandEchoAndPromptInStdoutForFrameParserToExclude() {
        val raw = "dali:/ \$ printf test\r\ntest\r\ndali:/ \$"
        val result = analyzePrintfTestOutput(raw, exitCode = 0)

        assertFalse(result.succeeded)
        assertEquals(raw, result.stdout)
        assertTrue(result.commandEchoDetected)
        assertTrue(result.shellPromptDetected)
        assertTrue(result.hasCrLf)
        assertFalse(result.hasLfOnly)
        assertFalse(result.hasBareCr)
    }

    @Test
    fun reportsPromptAppendedDirectlyAfterBodyWithoutStrippingIt() {
        val raw = "testdali:/ \$"
        val result = analyzePrintfTestOutput(raw, exitCode = 0)

        assertFalse(result.succeeded)
        assertEquals(raw, result.stdout)
        assertTrue(result.shellPromptDetected)
    }

    @Test
    fun rejectsCorrectBodyWhenExitCodeIsNonZero() {
        assertFalse(analyzePrintfTestOutput("test", exitCode = 1).succeeded)
    }

    @Test
    fun reportsExitCodeRawBodyLengthEchoPromptAndLineEndings() {
        val result = analyzePrintfTestOutput("device:/ # printf test\rtest\r", exitCode = 0)
        val diagnostic = result.diagnosticText()

        assertTrue(diagnostic.contains("exitCode=0"))
        assertTrue(diagnostic.contains("stdout长度="))
        assertTrue(diagnostic.contains("stdout=\"device:/ # printf test\\rtest\\r\""))
        assertTrue(diagnostic.contains("命令回显=true"))
        assertTrue(diagnostic.contains("shell 提示符=true"))
        assertTrue(result.hasBareCr)
    }

    @Test
    fun acceptsMissingProcessProbeOnlyWhenTheHeaderIsTheOnlyRow() {
        assertTrue(isEmptyMissingProcessQueryOutput("PID NAME\n", exitCode = 0))
        assertFalse(isEmptyMissingProcessQueryOutput("PID NAME\n123 com.example.app\n", exitCode = 0))
        assertFalse(isEmptyMissingProcessQueryOutput("invalid output\n", exitCode = 0))
        assertFalse(isEmptyMissingProcessQueryOutput("PID NAME\n", exitCode = 1))
    }
}
