package com.burgess.schedule.school.sync.localadb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAdbProbeOutputTest {
    @Test
    fun acceptsPlainPrintfOutputWhenExitCodeIsZero() {
        val result = analyzePrintfTestOutput("test", exitCode = 0)

        assertTrue(result.succeeded)
        assertEquals("test", result.normalizedOutput)
        assertFalse(result.commandEchoDetected)
        assertFalse(result.shellPromptDetected)
    }

    @Test
    fun removesEchoAndPromptFromCrLfInteractiveShellOutput() {
        val raw = "dali:/ \$ printf test\r\ntest\r\ndali:/ \$ "
        val result = analyzePrintfTestOutput(raw, exitCode = 0)

        assertTrue(result.succeeded)
        assertEquals("test", result.normalizedOutput)
        assertTrue(result.commandEchoDetected)
        assertTrue(result.shellPromptDetected)
        assertTrue(result.hasCrLf)
        assertFalse(result.hasLfOnly)
        assertFalse(result.hasBareCr)
        assertTrue(result.rawOutputLength > result.normalizedOutput.length)
    }

    @Test
    fun removesStandaloneCommandEchoAndPrompt() {
        val result = analyzePrintfTestOutput("printf test\ntest\ntoysh:/ \$", exitCode = 0)

        assertTrue(result.succeeded)
        assertTrue(result.commandEchoDetected)
        assertTrue(result.shellPromptDetected)
        assertTrue(result.hasLfOnly)
    }

    @Test
    fun removesPromptImmediatelyAppendedToPrintfOutput() {
        val result = analyzePrintfTestOutput("testdali:/ \$ ", exitCode = 0)

        assertTrue(result.succeeded)
        assertEquals("test", result.normalizedOutput)
        assertTrue(result.shellPromptDetected)
    }

    @Test
    fun rejectsCorrectBodyWhenExitCodeIsNonZero() {
        val result = analyzePrintfTestOutput("test", exitCode = 1)

        assertFalse(result.succeeded)
    }

    @Test
    fun rejectsOutputThatOnlyContainsTestAsPartOfAnotherWord() {
        val result = analyzePrintfTestOutput("notest", exitCode = 0)

        assertFalse(result.succeeded)
        assertEquals("notest", result.normalizedOutput)
    }

    @Test
    fun reportsExitCodeLengthNormalizedOutputEchoPromptAndLineEndings() {
        val result = analyzePrintfTestOutput("device:/ # printf test\rtest\r", exitCode = 0)
        val diagnostic = result.diagnosticText()

        assertTrue(diagnostic.contains("exitCode=0"))
        assertTrue(diagnostic.contains("标准化前长度="))
        assertTrue(diagnostic.contains("标准化后输出=\"test\""))
        assertTrue(diagnostic.contains("命令回显=true"))
        assertTrue(diagnostic.contains("shell 提示符=true"))
        assertTrue(result.hasBareCr)
    }
}
