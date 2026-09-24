package com.burgess.schedule.school.sync.localadb

internal data class PrintfTestOutputAnalysis(
    val exitCode: Int?,
    val rawOutputLength: Int,
    val normalizedOutput: String,
    val commandEchoDetected: Boolean,
    val shellPromptDetected: Boolean,
    val hasCrLf: Boolean,
    val hasLfOnly: Boolean,
    val hasBareCr: Boolean
) {
    val succeeded: Boolean
        get() = exitCode == 0 && normalizedOutput == "test"

    fun diagnosticText(): String {
        val displayOutput = normalizedOutput
            .replace("\n", "↵")
            .take(MAX_DIAGNOSTIC_OUTPUT_CHARS)
            .ifBlank { "<空>" }
        return "exitCode=${exitCode ?: "未收到"}；标准化前长度=$rawOutputLength；" +
            "标准化后输出=\"$displayOutput\"；命令回显=$commandEchoDetected；" +
            "shell 提示符=$shellPromptDetected；CRLF=$hasCrLf；LF=$hasLfOnly；CR=$hasBareCr"
    }
}

internal fun analyzePrintfTestOutput(output: String, exitCode: Int?): PrintfTestOutputAnalysis {
    val hasCrLf = output.contains("\r\n")
    val hasLfOnly = Regex("(?<!\r)\n").containsMatchIn(output)
    val hasBareCr = Regex("\r(?!\n)").containsMatchIn(output)
    val promptAndCommand = Regex("^.{0,160}(?:\\$|#)\\s*printf\\s+test$")
    val promptOnly = Regex("^.{0,160}(?:\\$|#)$")
    val testFollowedByPrompt = Regex("^test.{1,160}(?:\\$|#)$")
    var commandEchoDetected = false
    var shellPromptDetected = false

    val normalizedOutput = output
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split('\n')
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null

            val echoedWithPrompt = promptAndCommand.matches(trimmed)
            val isCommandEcho = trimmed == "printf test" || echoedWithPrompt
            val isPromptOnly = promptOnly.matches(trimmed)
            val testWithPromptSuffix = testFollowedByPrompt.matches(trimmed)
            if (isCommandEcho) commandEchoDetected = true
            if (echoedWithPrompt || isPromptOnly || testWithPromptSuffix) shellPromptDetected = true

            when {
                isCommandEcho -> null
                testWithPromptSuffix -> "test"
                isPromptOnly -> null
                else -> trimmed
            }
        }
        .joinToString("\n")

    return PrintfTestOutputAnalysis(
        exitCode = exitCode,
        rawOutputLength = output.length,
        normalizedOutput = normalizedOutput,
        commandEchoDetected = commandEchoDetected,
        shellPromptDetected = shellPromptDetected,
        hasCrLf = hasCrLf,
        hasLfOnly = hasLfOnly,
        hasBareCr = hasBareCr
    )
}

private const val MAX_DIAGNOSTIC_OUTPUT_CHARS = 120
