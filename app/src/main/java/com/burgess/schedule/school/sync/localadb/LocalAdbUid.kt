package com.burgess.schedule.school.sync.localadb

internal enum class LocalAdbCheckState {
    NOT_RUN,
    PAIRING,
    PAIRING_SUCCESS,
    PAIRING_FAILED,
    ADB_CONNECTING,
    ADB_CONNECTED,
    ADB_CONNECTION_FAILED,
    SHELL_OPENING,
    SHELL_OPENED,
    SHELL_FAILED,
    ID_RUNNING,
    ID_EXECUTED,
    ID_FAILED
}

internal data class LocalAdbUidCheckResult(
    val idState: LocalAdbCheckState,
    val output: String,
    val uidVerified: Boolean,
    val uidStatus: String,
    val errorMessage: String?
)

private const val MAX_ID_OUTPUT_CHARS = 1_024
private val UID_NUMBER_PATTERN = Regex("(?:^|\\s)uid=(\\d+)\\b")
private val COMPLETE_UID_FIELD_PATTERN = Regex("(?:^|\\s)uid=\\d+(?:\\([^)]+\\))?\\s")

internal fun hasCompleteUidField(output: String): Boolean =
    COMPLETE_UID_FIELD_PATTERN.containsMatchIn(output)

internal fun isShellUid2000(output: String): Boolean =
    Regex("(?:^|\\s)uid=2000\\(shell\\)(?:\\s|$)").containsMatchIn(output.trim())

private fun actualUidLabel(output: String): String {
    val match = Regex("(?:^|\\s)uid=(\\d+)\\b(?:\\(([^)]*)\\))?").find(output.trim())
    return match?.let {
        val name = it.groupValues[2]
        if (name.isBlank()) it.groupValues[1] else "${it.groupValues[1]} ($name)"
    } ?: "未识别"
}

internal fun evaluateLocalAdbUidCheck(
    output: String,
    readIssue: LocalAdbReadIssue = LocalAdbReadIssue.NONE,
    readFailureSummary: String? = null
): LocalAdbUidCheckResult {
    val displayOutput = output.take(MAX_ID_OUTPUT_CHARS).trim()
    val actualUid = actualUidLabel(displayOutput)
    val receivedUid = UID_NUMBER_PATTERN.containsMatchIn(displayOutput)
    val verified = receivedUid && isShellUid2000(displayOutput)
    val failure = if (receivedUid) null else readFailureSummary ?: when (readIssue) {
        LocalAdbReadIssue.STREAM_CLOSED -> "IOException: Stream closed."
        LocalAdbReadIssue.END_OF_STREAM -> "EOF: No UID was received before the stream ended."
        LocalAdbReadIssue.TIMEOUT -> "Timeout: No UID output arrived within 5 seconds."
        LocalAdbReadIssue.OUTPUT_LIMIT -> "Command output exceeded its safety limit."
        LocalAdbReadIssue.COMMAND_FAILED -> "The id command returned a non-zero status."
        LocalAdbReadIssue.PROTOCOL_ERROR -> "The command completion marker was incomplete."
        LocalAdbReadIssue.IO_ERROR -> "IOException: Failed to read id output."
        LocalAdbReadIssue.NONE -> "The id command returned no recognizable UID."
    }
    return LocalAdbUidCheckResult(
        idState = if (receivedUid) LocalAdbCheckState.ID_EXECUTED else LocalAdbCheckState.ID_FAILED,
        output = displayOutput,
        uidVerified = verified,
        uidStatus = when {
            verified -> "UID_2000_VERIFIED"
            receivedUid -> "UID_2000_NOT_VERIFIED（实际 UID：$actualUid）"
            else -> "UID_2000_NOT_VERIFIED（未收到 UID）"
        },
        errorMessage = failure
    )
}
