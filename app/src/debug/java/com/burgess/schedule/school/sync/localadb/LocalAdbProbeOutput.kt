package com.burgess.schedule.school.sync.localadb

internal data class PrintfTestOutputAnalysis(
    val exitCode: Int?,
    val stdout: String,
    val commandEchoDetected: Boolean,
    val shellPromptDetected: Boolean,
    val hasCrLf: Boolean,
    val hasLfOnly: Boolean,
    val hasBareCr: Boolean
) {
    val succeeded: Boolean
        get() = exitCode == 0 && stdout == "test"

    fun diagnosticText(): String {
        val stdoutPreview = stdout
            .replace("\\", "\\\\")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .take(MAX_DIAGNOSTIC_OUTPUT_CHARS)
            .ifBlank { "<空>" }
        return "exitCode=${exitCode ?: "未收到"}；stdout长度=${stdout.length}；" +
            "stdout=\"$stdoutPreview\"；命令回显=$commandEchoDetected；" +
            "shell 提示符=$shellPromptDetected；CRLF=$hasCrLf；LF=$hasLfOnly；CR=$hasBareCr"
    }
}

internal fun analyzePrintfTestOutput(output: String, exitCode: Int?): PrintfTestOutputAnalysis {
    val promptAndCommandEchoDetected = Regex("(?m)^.{0,160}(?:\\$|#)\\s*printf test\\r?$")
        .containsMatchIn(output)
    val commandEchoDetected = promptAndCommandEchoDetected ||
        Regex("(?m)^printf test\\r?$").containsMatchIn(output) ||
        output.contains("eval \"\$(printf '%b'")
    val shellPromptDetected = promptAndCommandEchoDetected ||
        Regex("(?m)^.{0,160}(?:\\$|#)\\s*\\r?$").containsMatchIn(output)

    return PrintfTestOutputAnalysis(
        exitCode = exitCode,
        stdout = output,
        commandEchoDetected = commandEchoDetected,
        shellPromptDetected = shellPromptDetected,
        hasCrLf = output.contains("\r\n"),
        hasLfOnly = Regex("(?<!\r)\n").containsMatchIn(output),
        hasBareCr = Regex("\r(?!\n)").containsMatchIn(output)
    )
}

internal fun isEmptyMissingProcessQueryOutput(output: String, exitCode: Int?): Boolean {
    if (exitCode != 0) return false
    val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    return lines.size == 1 && lines.single().equals("PID NAME", ignoreCase = true)
}

private const val MAX_DIAGNOSTIC_OUTPUT_CHARS = 120
