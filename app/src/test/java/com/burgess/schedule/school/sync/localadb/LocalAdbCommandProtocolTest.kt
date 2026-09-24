package com.burgess.schedule.school.sync.localadb

import java.io.InputStream
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
    fun parsesBeginAndDoneInOneChunk() {
        val wrapped = wrapWithCommandFrame(LocalAdbReadOnlyCommand.PRINTF_TEST, FRAME_ID)
        val result = collect(frame("test", wrapped), wrapped)

        assertEquals("test", result.output)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun parsesBeginMarkerSplitAcrossChunks() {
        val wrapped = wrapWithCommandFrame(LocalAdbReadOnlyCommand.ID, FRAME_ID)
        val raw = frame("uid=2000(shell)", wrapped)
        val bytes = raw.toByteArray()
        val buffer = buffer(wrapped)
        val markerStart = raw.indexOf(wrapped.beginMarker)
        val splitAt = markerStart + wrapped.beginMarker.length / 2
        assertEquals(null, buffer.append(bytes, 0, splitAt))
        val payload = buffer.append(bytes, splitAt, bytes.size - splitAt)

        assertEquals("uid=2000(shell)", payload?.output)
        assertEquals(0, payload?.exitCode)
    }

    @Test
    fun parsesDoneMarkerSplitAcrossChunks() {
        val wrapped = wrapWithCommandFrame(LocalAdbReadOnlyCommand.PRINTF_TEST, FRAME_ID)
        val raw = frame("test", wrapped)
        val bytes = raw.toByteArray()
        val buffer = buffer(wrapped)
        val doneStart = raw.indexOf(wrapped.doneMarker)
        val splitAt = doneStart + wrapped.doneMarker.length / 2
        assertEquals(null, buffer.append(bytes, 0, splitAt))
        val payload = buffer.append(bytes, splitAt, bytes.size - splitAt)

        assertEquals("test", payload?.output)
    }

    @Test
    fun ignoresWrapperEchoContainingMarkersUnlessTheyAreStandaloneLines() {
        val wrapped = wrapWithCommandFrame(LocalAdbReadOnlyCommand.PRINTF_TEST, FRAME_ID)
        val echoedWrapper = "dali:/ \$ ${wrapped.input.trimEnd()}\r\n"
        val outcome = collectOutcome(echoedWrapper + frame("test", wrapped), wrapped)

        assertTrue(outcome is CommandFrameReadOutcome.Completed)
        assertEquals("test", (outcome as CommandFrameReadOutcome.Completed).payload.output)
    }

    @Test
    fun ignoresPromptAndEchoBeforeBeginMarker() {
        val wrapped = wrapWithCommandFrame(LocalAdbReadOnlyCommand.PRINTF_TEST, FRAME_ID)
        val preamble = "dali:/ \$ printf test\r\n"
        val result = collect(preamble + frame("test", wrapped), wrapped)

        assertEquals("test", result.output)
    }

    @Test
    fun ignoresPromptAfterDoneMarker() {
        val wrapped = wrapWithCommandFrame(LocalAdbReadOnlyCommand.PRINTF_TEST, FRAME_ID)
        val result = collect(frame("test", wrapped, postamble = "dali:/ \$ \r\nextra output"), wrapped)

        assertEquals("test", result.output)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun removesFrameSeparatorWhenPayloadHasNoTrailingNewline() {
        val wrapped = wrapWithCommandFrame(LocalAdbReadOnlyCommand.PRINTF_TEST, FRAME_ID)
        val result = collect(frame("test", wrapped), wrapped)

        assertEquals("test", result.output)
    }

    @Test
    fun preservesMultilinePayload() {
        val wrapped = wrapWithCommandFrame(LocalAdbReadOnlyCommand.PROC_UNIX_READ, FRAME_ID)
        val result = collect(frame("first line\nsecond line", wrapped), wrapped)

        assertEquals("first line\nsecond line", result.output)
    }

    @Test
    fun emptyPayloadParsesSuccessfully() {
        val wrapped = wrapWithCommandFrame(LocalAdbReadOnlyCommand.MISSING_PROCESS_QUERY, FRAME_ID)
        val result = collect(frame("", wrapped), wrapped)

        assertEquals("", result.output)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun acceptsLfMarkerLines() {
        val wrapped = wrapWithCommandFrame(LocalAdbReadOnlyCommand.PRINTF_TEST, FRAME_ID)
        val result = collect(frame("body", wrapped, lineEnding = "\n"), wrapped)

        assertEquals("body", result.output)
    }

    @Test
    fun acceptsCrLfMarkerLinesAndPreservesPayloadCrLf() {
        val wrapped = wrapWithCommandFrame(LocalAdbReadOnlyCommand.PRINTF_TEST, FRAME_ID)
        val result = collect(frame("first\r\nsecond", wrapped, lineEnding = "\r\n"), wrapped)

        assertEquals("first\r\nsecond", result.output)
    }

    @Test
    fun returnsNonZeroExitCodeFromDoneMarker() {
        val wrapped = wrapWithCommandFrame(LocalAdbReadOnlyCommand.PROC_UNIX_READ, FRAME_ID)
        val result = collect(frame("permission diagnostic", wrapped, exitCode = 1), wrapped)

        assertEquals("permission diagnostic", result.output)
        assertEquals(1, result.exitCode)
    }

    @Test
    fun ignoresMarkersWithWrongUuid() {
        val wrapped = wrapWithCommandFrame(LocalAdbReadOnlyCommand.PRINTF_TEST, FRAME_ID)
        val wrong = wrapWithCommandFrame(LocalAdbReadOnlyCommand.PRINTF_TEST, OTHER_FRAME_ID)
        val raw = "${wrong.beginMarker}\n${wrong.doneMarker}:0\n" +
            "${wrapped.beginMarker}\nbody\n${wrong.doneMarker}:1\n" +
            "${wrapped.doneMarker}:0\n"
        val result = collect(raw, wrapped)

        assertEquals("body\n${wrong.doneMarker}:1", result.output)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun waitsForDoneMarkerBeforeTimingOut() {
        val wrapped = wrapWithCommandFrame(LocalAdbReadOnlyCommand.ID, FRAME_ID)
        val input = OpenAfterBytesInputStream("${wrapped.beginMarker}\npartial".toByteArray())
        val buffer = buffer(wrapped)
        var timedOut = false
        runBlocking {
            try {
                withTimeout(60) {
                    readUntilCommandFrame(input, { false }, buffer, pollIntervalMs = 5)
                }
            } catch (_: TimeoutCancellationException) {
                timedOut = true
            }
        }

        assertTrue(timedOut)
        assertTrue(buffer.hasOutput)
    }

    @Test
    fun wrapperIsOnePhysicalShellLineWithMatchingRandomMarkers() {
        val wrapped = wrapWithCommandFrame(LocalAdbReadOnlyCommand.mapsScan(12345), FRAME_ID)

        assertEquals(1, wrapped.input.count { it == '\n' })
        assertTrue(wrapped.input.startsWith("{ printf '\\n${wrapped.beginMarker}\\n'; eval \"\$(printf '%b' "))
        assertTrue(wrapped.input.contains("__burgess_rc=\$?; printf '\\n${wrapped.doneMarker}:%d\\n' \"\$__burgess_rc\"; }\n"))
        assertEquals(FRAME_ID, wrapped.beginMarker.removePrefix("__BURGESS_BEGIN_").removeSuffix("__"))
        assertEquals(FRAME_ID, wrapped.doneMarker.removePrefix("__BURGESS_DONE_").removeSuffix("__"))
        assertFalse(wrapped.input.contains("\neval "))
    }

    @Test
    fun everyCommandGetsItsOwnFrameId() {
        val first = wrapWithCommandFrame(LocalAdbReadOnlyCommand.ID, newCommandFrameId())
        val second = wrapWithCommandFrame(LocalAdbReadOnlyCommand.ID, newCommandFrameId())

        assertNotEquals(first.beginMarker, second.beginMarker)
        assertNotEquals(first.doneMarker, second.doneMarker)
    }

    private fun buffer(wrapped: WrappedCommandFrame) = CommandFrameBuffer(
        beginMarker = wrapped.beginMarker,
        doneMarker = wrapped.doneMarker,
        outputLimit = 32 * 1024
    )

    private fun collectOutcome(text: String, wrapped: WrappedCommandFrame): CommandFrameReadOutcome {
        val bytes = text.toByteArray()
        val buffer = buffer(wrapped)
        val result = buffer.append(bytes, 0, bytes.size)
        return result?.let { CommandFrameReadOutcome.Completed(it) }
            ?: error("Expected a complete BEGIN/DONE frame")
    }

    private fun collect(text: String, wrapped: WrappedCommandFrame): CommandFramePayload =
        (collectOutcome(text, wrapped) as CommandFrameReadOutcome.Completed).payload

    private fun frame(
        payload: String,
        wrapped: WrappedCommandFrame,
        exitCode: Int = 0,
        lineEnding: String = "\n",
        postamble: String = ""
    ): String = "${wrapped.beginMarker}$lineEnding$payload$lineEnding" +
        "${wrapped.doneMarker}:$exitCode$lineEnding$postamble"

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
        const val FRAME_ID = "1234567890abcdef1234567890abcdef"
        const val OTHER_FRAME_ID = "fedcba0987654321fedcba0987654321"
    }
}
