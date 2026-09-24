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

internal enum class LocalAdbCommandKind(val safeLabel: String) {
    ID("id"),
    PRINTF_TEST("printf test"),
    PROC_UNIX_READ("cat /proc/net/unix"),
    PROCESS_QUERY("ps process query"),
    MISSING_PROCESS_QUERY("ps absent-package query"),
    FD_SCAN("/proc/PID/fd scan"),
    MAPS_SCAN("filtered /proc/PID/maps scan")
}

internal data class LocalAdbReadOnlyCommand(
    val kind: LocalAdbCommandKind,
    val shellCommand: String,
    val outputLimit: Int = DEFAULT_OUTPUT_LIMIT_BYTES,
    val timeoutMs: Long = COMMAND_TIMEOUT_MS,
    val targetPid: Int? = null
) {
    val safeLabel: String
        get() = targetPid?.let { "${kind.safeLabel} (pid=$it)" } ?: kind.safeLabel

    companion object {
        val ID = LocalAdbReadOnlyCommand(LocalAdbCommandKind.ID, "id", outputLimit = 1024)
        val PRINTF_TEST = LocalAdbReadOnlyCommand(
            LocalAdbCommandKind.PRINTF_TEST,
            "printf test",
            outputLimit = 1024
        )
        val PROC_UNIX_READ = LocalAdbReadOnlyCommand(
            LocalAdbCommandKind.PROC_UNIX_READ,
            "cat /proc/net/unix 2>/dev/null",
            outputLimit = 96 * 1024
        )
        val PROCESS_QUERY = LocalAdbReadOnlyCommand(
            LocalAdbCommandKind.PROCESS_QUERY,
            "ps -A -o PID,NAME 2>/dev/null",
            outputLimit = 96 * 1024
        )
        val MISSING_PROCESS_QUERY = LocalAdbReadOnlyCommand(
            LocalAdbCommandKind.MISSING_PROCESS_QUERY,
            "ps -A -o PID,NAME 2>/dev/null",
            outputLimit = 96 * 1024
        )

        fun fdScan(pid: Int): LocalAdbReadOnlyCommand {
            require(pid > 0) { "PID must be positive" }
            val command = FD_SCAN_SCRIPT
                .replace("@@PID@@", pid.toString())
                .replace('§', '$')
            return LocalAdbReadOnlyCommand(
                kind = LocalAdbCommandKind.FD_SCAN,
                shellCommand = command,
                outputLimit = 32 * 1024,
                targetPid = pid
            )
        }

        fun mapsScan(pid: Int): LocalAdbReadOnlyCommand {
            require(pid > 0) { "PID must be positive" }
            val command = MAPS_SCAN_SCRIPT
                .replace("@@PID@@", pid.toString())
                .replace('§', '$')
            return LocalAdbReadOnlyCommand(
                kind = LocalAdbCommandKind.MAPS_SCAN,
                shellCommand = command,
                outputLimit = 16 * 1024,
                targetPid = pid
            )
        }
    }
}

internal data class LocalAdbCommandOutput(
    val output: String,
    val issue: LocalAdbReadIssue,
    val failureSummary: String?,
    val commandKind: LocalAdbCommandKind,
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

private val FD_SCAN_SCRIPT = """
if [ ! -d "/proc/@@PID@@/fd" ]; then
  printf '__FD_SCAN_FAILED__|@@PID@@|PROCESS_GONE\n'
elif ! ls "/proc/@@PID@@/fd" >/dev/null 2>&1; then
  printf '__FD_SCAN_PERMISSION_DENIED__|@@PID@@\n'
else
  fd_problem=0
  for fd_path in /proc/@@PID@@/fd/*; do
    [ -e "§fd_path" ] || [ -L "§fd_path" ] || continue
    target="$(readlink "§fd_path" 2>/dev/null)"
    readlink_status=$?
    if [ "§readlink_status" -ne 0 ]; then
      fd_problem=1
      continue
    fi
    case "§target" in
      socket:\[*\])
        inode="§{target#socket:}"
        inode="§{inode#\[}"
        inode="§{inode%\]}"
        case "§inode" in *[!0-9]*|'') continue ;; esac
        printf '__FD_SOCKET__|@@PID@@|%s\n' "§inode"
        ;;
    esac
  done
  if [ ! -d "/proc/@@PID@@/fd" ]; then
    printf '__FD_SCAN_FAILED__|@@PID@@|PROCESS_GONE\n'
  elif [ "§fd_problem" -ne 0 ]; then
    printf '__FD_SCAN_PERMISSION_DENIED__|@@PID@@\n'
  else
    printf '__FD_SCAN_SUCCESS__|@@PID@@\n'
  fi
fi
""".trimIndent()

private val MAPS_SCAN_SCRIPT = """
maps_file="/proc/@@PID@@/maps"
if [ ! -r "§maps_file" ]; then
  printf '__MAPS_SCAN_PERMISSION_DENIED__|@@PID@@\n'
else
  maps_result="$(awk -v pid=@@PID@@ '
    {
      path = ""
      for (i = 6; i <= NF; i++) path = path (i == 6 ? "" : " ") §i
      sub(/ \(deleted\)$/, "", path)
      if (path !~ /^\//) next
      count = split(path, parts, "/")
      basename = parts[count]
      lowered = tolower(basename)
      keys = ""
      if (lowered ~ /xweb/) keys = keys "xweb,"
      if (lowered ~ /webview/) keys = keys "webview,"
      if (lowered ~ /chromium/) keys = keys "chromium,"
      if (lowered ~ /tbs/) keys = keys "tbs,"
      if (lowered ~ /x5/) keys = keys "x5,"
      if (keys != "") {
        sub(/,$/, "", keys)
        gsub(/[|[:cntrl:]]/, "?", basename)
        printf "__WX_MAPS_LIBRARY__|%s|%s|%s\n", pid, basename, keys
      }
    }
  ' "§maps_file" 2>&1)"
  maps_status=$?
  case "§maps_result" in
    *"Permission denied"*) printf '__MAPS_SCAN_PERMISSION_DENIED__|@@PID@@\n' ;;
    *)
      printf '%s\n' "§maps_result" | awk '/^__WX_MAPS_LIBRARY__\|/'
      if [ "§maps_status" -eq 0 ]; then
        printf '__MAPS_SCAN_SUCCESS__|@@PID@@\n'
      else
        printf '__MAPS_SCAN_FAILED__|@@PID@@\n'
      fi
      ;;
  esac
fi
""".trimIndent()
