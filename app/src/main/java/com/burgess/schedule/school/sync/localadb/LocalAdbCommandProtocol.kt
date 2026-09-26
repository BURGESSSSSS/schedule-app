package com.burgess.schedule.school.sync.localadb

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.delay

internal enum class LocalAdbReadIssue {
    NONE,
    STREAM_CLOSED,
    END_OF_STREAM,
    TIMEOUT,
    OUTPUT_LIMIT,
    COMMAND_FAILED,
    PROTOCOL_ERROR,
    IO_ERROR
}

internal interface LocalAdbCommandDescriptor {
    val safeLabel: String
}

internal enum class LocalAdbCommandKind(override val safeLabel: String) : LocalAdbCommandDescriptor {
    ID("id"),
    PROC_UNIX_READ("cat /proc/net/unix"),
    PROCESS_QUERY("ps process query")
}

internal data class LocalAdbReadOnlyCommand(
    val kind: LocalAdbCommandDescriptor,
    val shellCommand: String,
    val outputLimit: Int = DEFAULT_OUTPUT_LIMIT_BYTES,
    val timeoutMs: Long = COMMAND_TIMEOUT_MS,
    val targetPid: Int? = null
) {
    val safeLabel: String
        get() = targetPid?.let { "${kind.safeLabel} (pid=$it)" } ?: kind.safeLabel

    companion object {
        val ID = LocalAdbReadOnlyCommand(LocalAdbCommandKind.ID, "id", outputLimit = 1024)
        val PROC_UNIX_READ = LocalAdbReadOnlyCommand(
            LocalAdbCommandKind.PROC_UNIX_READ,
            "cat /proc/net/unix 2>/dev/null",
            outputLimit = 96 * 1024
        )
        val PROCESS_QUERY = LocalAdbReadOnlyCommand(
            LocalAdbCommandKind.PROCESS_QUERY,
            "ps -A -o PID,NAME 2>/dev/null | awk 'NR == 1 || \$2 == \"com.tencent.wework\" || index(\$2, \"com.tencent.wework:\") == 1'",
            outputLimit = 96 * 1024
        )
    }
}

internal data class LocalAdbCommandOutput(
    val output: String,
    val issue: LocalAdbReadIssue,
    val failureSummary: String?,
    val commandKind: LocalAdbCommandDescriptor,
    val commandCompleted: Boolean,
    val exitCode: Int?,
    val partialOutputReceived: Boolean
)

internal data class CommandFramePayload(
    val output: String,
    val exitCode: Int
)

internal class CommandFrameBuffer(
    private val beginMarker: String,
    private val doneMarker: String,
    val outputLimit: Int,
    private val payloadLimit: Int = outputLimit
) {
    private val bytes = ByteArrayOutputStream()
    private val beginPattern = Regex("(?m)^${Regex.escape(beginMarker)}\\r?\\n")
    private val donePattern = Regex("(?m)^${Regex.escape(doneMarker)}:(\\d+)\\r?\\n")

    val size: Int
        get() = bytes.size()

    val hasOutput: Boolean
        get() {
            val content = snapshot()
            val begin = beginPattern.find(content) ?: return false
            val bodyStart = begin.range.last + 1
            val done = donePattern.find(content, bodyStart)
            val bodyEnd = done?.range?.first ?: content.length
            return content.substring(bodyStart, bodyEnd).removeOneFrameSeparator().isNotEmpty()
    }

    fun append(source: ByteArray, offset: Int, length: Int): CommandFramePayload? {
        require(length >= 0 && offset >= 0 && offset + length <= source.size)
        require(bytes.size() + length <= outputLimit) { "Command output limit exceeded" }
        bytes.write(source, offset, length)
        return completedPayload()
    }

    fun snapshot(): String = bytes.toString(Charsets.UTF_8.name())

    private fun completedPayload(): CommandFramePayload? {
        val content = snapshot()
        val begin = beginPattern.find(content) ?: return null
        val bodyStart = begin.range.last + 1
        val done = donePattern.find(content, bodyStart) ?: return null
        val exitCode = done.groupValues[1].toIntOrNull() ?: return null
        val output = content.substring(bodyStart, done.range.first).removeOneFrameSeparator()
        require(output.toByteArray(Charsets.UTF_8).size <= payloadLimit) { "Command payload limit exceeded" }
        return CommandFramePayload(output, exitCode)
    }

    private fun String.removeOneFrameSeparator(): String = when {
        endsWith("\r\n") -> dropLast(2)
        endsWith('\n') -> dropLast(1)
        else -> this
    }
}

internal sealed interface CommandFrameReadOutcome {
    data class Completed(val payload: CommandFramePayload) : CommandFrameReadOutcome
    data class StreamClosed(val partialOutputReceived: Boolean) : CommandFrameReadOutcome
    data class EndOfStream(val partialOutputReceived: Boolean) : CommandFrameReadOutcome
    data class OutputLimitExceeded(val partialOutputReceived: Boolean) : CommandFrameReadOutcome
}

internal suspend fun readUntilCommandFrame(
    input: InputStream,
    isStreamClosed: () -> Boolean,
    buffer: CommandFrameBuffer,
    pollIntervalMs: Long = 25L,
    chunkSize: Int = 512
): CommandFrameReadOutcome {
    val chunk = ByteArray(chunkSize)
    while (true) {
        if (buffer.size >= buffer.outputLimit) {
            return CommandFrameReadOutcome.OutputLimitExceeded(buffer.hasOutput)
        }
        val available = input.available()
        if (available <= 0) {
            if (isStreamClosed()) return CommandFrameReadOutcome.StreamClosed(buffer.hasOutput)
            delay(pollIntervalMs)
            continue
        }

        val count = input.read(chunk, 0, minOf(available, chunk.size, buffer.outputLimit - buffer.size))
        if (count < 0) return CommandFrameReadOutcome.EndOfStream(buffer.hasOutput)
        try {
            buffer.append(chunk, 0, count)?.let { return CommandFrameReadOutcome.Completed(it) }
        } catch (_: IllegalArgumentException) {
            return CommandFrameReadOutcome.OutputLimitExceeded(buffer.hasOutput)
        }
    }
}

internal fun newCommandFrameId(): String = UUID.randomUUID().toString().replace("-", "")

internal fun wrapWithCommandFrame(command: LocalAdbReadOnlyCommand, frameId: String): WrappedCommandFrame {
    require(frameId.matches(Regex("[A-Fa-f0-9]{32}"))) { "Command frame id must be a UUID hex string" }
    val beginMarker = "__BURGESS_BEGIN_${frameId}__"
    val doneMarker = "__BURGESS_DONE_${frameId}__"
    val encodedCommand = encodeForPrintfB(command.shellCommand.trimEnd('\n', '\r'))
    val quotedCommand = quoteForShellSingleArgument(encodedCommand)
    val input = buildString {
        append("{ printf '\\n$beginMarker\\n'; ")
        append("eval \"\$(printf '%b' ")
        append(quotedCommand)
        append(")\"; __burgess_rc=\$?; ")
        append("printf '\\n$doneMarker:%d\\n' \"\$__burgess_rc\"; }\n")
    }
    return WrappedCommandFrame(input, beginMarker, doneMarker)
}

private fun encodeForPrintfB(script: String): String = buildString(script.length) {
    script.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(character)
        }
    }
}

private fun quoteForShellSingleArgument(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"

internal data class WrappedCommandFrame(
    val input: String,
    val beginMarker: String,
    val doneMarker: String
)

private const val DEFAULT_OUTPUT_LIMIT_BYTES = 64 * 1024
private const val COMMAND_TIMEOUT_MS = 5_000L
