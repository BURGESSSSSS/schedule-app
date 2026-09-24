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
    fun readsOrdinaryOutputAndSentinelAsCompletedCommand() {
        val wrapped = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.PRINTF_TEST, SENTINEL_ID)
        val result = collect(frame("test", wrapped), wrapped)

        assertEquals("test", result.output)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun emptyOutputWithSentinelIsSuccessfulAndEmpty() {
        val wrapped = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.PROCESS_QUERY, SENTINEL_ID)
        val result = collect(frame("", wrapped), wrapped)

        assertEquals("", result.output)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun readsMultilineOutputAndStripsProtocolLines() {
        val wrapped = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.PROC_UNIX_READ, SENTINEL_ID)
        val result = collect(frame("line one\nline two\n", wrapped), wrapped)

        assertEquals("line one\nline two\n", result.output)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun sentinelMayArriveAcrossSeveralChunks() {
        val wrapped = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.ID, SENTINEL_ID)
        val input = ChunkedInputStream(frame("uid=2000(shell)\n", wrapped).toByteArray(), chunkSize = 3)
        val buffer = buffer(wrapped)
        val outcome = runBlocking {
            readUntilCommandSentinel(input, { false }, buffer, pollIntervalMs = 1)
        }

        assertTrue(outcome is SentinelReadOutcome.Completed)
        assertEquals("uid=2000(shell)\n", (outcome as SentinelReadOutcome.Completed).payload.output)
    }

    @Test
    fun partialOutputWithoutSentinelTimesOut() {
        val wrapped = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.ID, SENTINEL_ID)
        val input = OpenAfterBytesInputStream("partial".toByteArray())
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
    fun ordinaryDoneTextDoesNotMatchUniqueSentinel() {
        val wrapped = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.PRINTF_TEST, SENTINEL_ID)
        val ordinaryText = "normal __DONE__ marker\n"
        val input = ChunkedInputStream((ordinaryText + wrapped.sentinel + "\n").toByteArray(), chunkSize = 4)
        val outcome = runBlocking {
            readUntilCommandSentinel(input, { false }, buffer(wrapped), pollIntervalMs = 1)
        }

        assertTrue(outcome is SentinelReadOutcome.Completed)
        assertEquals(ordinaryText.trimEnd('\n'), (outcome as SentinelReadOutcome.Completed).payload.output)
    }

    @Test
    fun malformedSentinelWithoutCommandOutputIsNotReportedAsPartialOutput() {
        val wrapped = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.PRINTF_TEST, SENTINEL_ID)
        val bytes = "${wrapped.sentinel}\n".toByteArray()
        val buffer = buffer(wrapped)
        buffer.append(bytes, 0, bytes.size)

        assertFalse(buffer.hasOutput)
    }

    @Test
    fun commandCompletesFromSentinelWhileInputStreamRemainsOpen() {
        val wrapped = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.PRINTF_TEST, SENTINEL_ID)
        val input = OpenAfterBytesInputStream(frame("test", wrapped).toByteArray())
        val closedChecks = AtomicInteger()
        val outcome = runBlocking {
            readUntilCommandSentinel(
                input,
                { closedChecks.incrementAndGet(); false },
                buffer(wrapped),
                pollIntervalMs = 1
            )
        }

        assertTrue(outcome is SentinelReadOutcome.Completed)
        assertEquals(0, closedChecks.get())
    }

    @Test
    fun everyCommandGetsUniqueSentinelAndWrapperEndsWithNewline() {
        val firstId = newSentinelId()
        val secondId = newSentinelId()
        val first = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.ID, firstId)
        val second = wrapWithCommandSentinel(LocalAdbReadOnlyCommand.ID, secondId)

        assertNotEquals(first.sentinel, second.sentinel)
        assertTrue(first.input.endsWith("\n"))
        assertTrue(first.input.contains("printf '\\n${first.sentinel}\\n'"))
    }

    private fun buffer(wrapped: WrappedShellCommand) = SentinelCommandBuffer(
        sentinel = wrapped.sentinel,
        exitMarker = wrapped.exitMarker,
        outputLimit = 16 * 1024
    )

    private fun collect(text: String, wrapped: WrappedShellCommand): SentinelCommandPayload {
        val bytes = text.toByteArray()
        val buffer = buffer(wrapped)
        return buffer.append(bytes, 0, bytes.size) ?: error("Expected the completion sentinel")
    }

    private fun frame(output: String, wrapped: WrappedShellCommand): String =
        output + "\n${wrapped.exitMarker}|0\n\n${wrapped.sentinel}\n"

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
