package com.burgess.schedule.school.sync.localadb

import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAdbCommandProtocolTest {
    @Test
    fun parsesBodyAndAtomicSentinelInOneChunk() {
        val wrapped = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.PRINTF_TEST, SENTINEL_ID)
        val result = collect(frame("test", wrapped), wrapped)

        assertEquals("test", result.output)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun parsesSentinelSplitAcrossMultipleChunks() {
        val wrapped = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.ID, SENTINEL_ID)
        val input = ChunkedInputStream(frame("uid=2000(shell)\n", wrapped).toByteArray(), chunkSize = 2)
        val outcome = read(input, wrapped)

        assertTrue(outcome is SentinelReadOutcome.Completed)
        assertEquals("uid=2000(shell)\n", (outcome as SentinelReadOutcome.Completed).payload.output)
        assertEquals(0, outcome.payload.exitCode)
    }

    @Test
    fun parsesBodyDeliveredAcrossMultipleChunks() {
        val wrapped = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.PRINTF_TEST, SENTINEL_ID)
        val input = ChunkedInputStream(frame("first line\nsecond line", wrapped).toByteArray(), chunkSize = 4)
        val outcome = read(input, wrapped)

        assertTrue(outcome is SentinelReadOutcome.Completed)
        assertEquals("first line\nsecond line", (outcome as SentinelReadOutcome.Completed).payload.output)
    }

    @Test
    fun emptyBodyWithSentinelAndZeroExitIsSuccessful() {
        val wrapped = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.MISSING_PROCESS_QUERY, SENTINEL_ID)
        val result = collect(frame("", wrapped), wrapped)

        assertEquals("", result.output)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun nonZeroExitCodeIsReturnedWithAtomicSentinel() {
        val wrapped = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.PROC_UNIX_READ, SENTINEL_ID)
        val result = collect(frame("permission diagnostic", wrapped, exitCode = 1), wrapped)

        assertEquals("permission diagnostic", result.output)
        assertEquals(1, result.exitCode)
    }

    @Test
    fun acceptsLfSentinelLineEnding() {
        val wrapped = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.PRINTF_TEST, SENTINEL_ID)
        val result = collect(frame("body", wrapped, lineEnding = "\n"), wrapped)

        assertEquals("body", result.output)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun acceptsCrLfSentinelLineEnding() {
        val wrapped = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.PRINTF_TEST, SENTINEL_ID)
        val result = collect(frame("body", wrapped, lineEnding = "\r\n"), wrapped)

        assertEquals("body", result.output)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun ordinaryDoneTextInOutputDoesNotCompleteCommand() {
        val wrapped = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.PRINTF_TEST, SENTINEL_ID)
        val ordinaryText = "normal __DONE__ marker\n"
        val outcome = read(
            ChunkedInputStream(frame(ordinaryText, wrapped).toByteArray(), chunkSize = 3),
            wrapped
        )

        assertTrue(outcome is SentinelReadOutcome.Completed)
        assertEquals(ordinaryText, (outcome as SentinelReadOutcome.Completed).payload.output)
    }

    @Test
    fun anotherCommandsUuidDoesNotCompleteThisCommand() {
        val wrapped = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.PRINTF_TEST, SENTINEL_ID)
        val buffer = buffer(wrapped)
        val wrongId = "fedcba0987654321fedcba0987654321"
        val wrongMarker = "${wrapped.sentinel.substringBefore(SENTINEL_ID)}${wrongId}__:0\n"
        val wrongBytes = wrongMarker.toByteArray()

        assertEquals(null, buffer.append(wrongBytes, 0, wrongBytes.size))

        val correctSuffix = "${wrapped.sentinel}:0\n".toByteArray()
        val result = buffer.append(correctSuffix, 0, correctSuffix.size)
        assertEquals(0, result?.exitCode)
        assertTrue(result?.output?.contains(wrongMarker.trim()) == true)
    }

    @Test
    fun waitsForSentinelLineTerminatorWhenDigitsEndAtChunkBoundary() {
        val wrapped = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.PRINTF_TEST, SENTINEL_ID)
        val buffer = buffer(wrapped)
        val partialLine = "body\n${wrapped.sentinel}:0".toByteArray()

        assertEquals(null, buffer.append(partialLine, 0, partialLine.size))

        val lineTerminator = "\n".toByteArray()
        val result = buffer.append(lineTerminator, 0, lineTerminator.size)
        assertEquals("body", result?.output)
        assertEquals(0, result?.exitCode)
    }

    @Test
    fun waitsForCorrectSentinelBeforeTimingOut() {
        val wrapped = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.ID, SENTINEL_ID)
        val input = OpenAfterBytesInputStream("partial output\n".toByteArray())
        val buffer = buffer(wrapped)
        var timedOut = false
        runBlocking {
            try {
                withTimeout(60) {
                    readUntilCommandSentinel(input, { false }, buffer, pollIntervalMs = 5)
                }
            } catch (_: TimeoutCancellationException) {
                timedOut = true
            }
        }

        assertTrue(timedOut)
        assertTrue(buffer.hasOutput)
    }

    @Test
    fun correctSentinelCompletesEvenWhenStreamIsAlreadyClosedAfterItsBytes() {
        val wrapped = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.PRINTF_TEST, SENTINEL_ID)
        val input = OpenAfterBytesInputStream(frame("test", wrapped).toByteArray())
        val closedChecks = AtomicInteger()
        val outcome = runBlocking {
            readUntilCommandSentinel(input, { closedChecks.incrementAndGet(); true }, buffer(wrapped), pollIntervalMs = 1)
        }

        assertTrue(outcome is SentinelReadOutcome.Completed)
        assertEquals("test", (outcome as SentinelReadOutcome.Completed).payload.output)
        assertEquals(0, closedChecks.get())
    }

    @Test
    fun wrapperUsesOneStatusBearingSentinelAndSavesStatusFirst() {
        val wrapped = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.ID, SENTINEL_ID)

        assertTrue(wrapped.input.endsWith("\n"))
        assertTrue(wrapped.input.contains("__burgess_rc=\$?\nprintf '\\n${wrapped.sentinel}:%d\\n' \"\$__burgess_rc\""))
        assertFalse(wrapped.input.contains("__BURGESS_CMD_EXIT_"))
    }

    @Test
    fun everyCommandGetsItsOwnSentinelId() {
        val first = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.ID, newSentinelId())
        val second = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.ID, newSentinelId())

        assertNotEquals(first.sentinel, second.sentinel)
    }

    private fun read(input: InputStream, wrapped: WrappedShellCommand): SentinelReadOutcome = runBlocking {
        readUntilCommandSentinel(input, { false }, buffer(wrapped), pollIntervalMs = 1)
    }

    private fun buffer(wrapped: WrappedShellCommand) = SentinelCommandBuffer(
        sentinel = wrapped.sentinel,
        outputLimit = 16 * 1024
    )

    private fun collect(text: String, wrapped: WrappedShellCommand): SentinelCommandPayload {
        val bytes = text.toByteArray()
        val buffer = buffer(wrapped)
        return buffer.append(bytes, 0, bytes.size) ?: error("Expected the status-bearing completion sentinel")
    }

    private fun frame(
        output: String,
        wrapped: WrappedShellCommand,
        exitCode: Int = 0,
        lineEnding: String = "\n"
    ): String = output + lineEnding + "${wrapped.sentinel}:$exitCode" + lineEnding

    private class ChunkedInputStream(
        private val bytes: ByteArray,
        private val chunkSize: Int
    ) : InputStream() {
        private var position = 0

        override fun available(): Int = minOf(chunkSize, bytes.size - position)

        override fun read(): Int = if (position >= bytes.size) -1 else bytes[position++].toInt() and 0xff

        override fun read(destination: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, chunkSize, bytes.size - position)
            bytes.copyInto(destination, offset, position, position + count)
            position += count
            return count
        }
    }

    private class OpenAfterBytesInputStream(private val bytes: ByteArray) : InputStream() {
        private var position = 0

        override fun available(): Int = bytes.size - position

        override fun read(): Int = if (position >= bytes.size) -1 else bytes[position++].toInt() and 0xff

        override fun read(destination: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(destination, offset, position, position + count)
            position += count
            return count
        }
    }

    private companion object {
        const val SENTINEL_ID = "1234567890abcdef1234567890abcdef"
    }
}
