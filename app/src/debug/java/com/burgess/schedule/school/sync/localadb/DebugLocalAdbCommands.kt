package com.burgess.schedule.school.sync.localadb

/** Commands used only by the unified development diagnostics screen. */
internal enum class DebugLocalAdbCommandKind(override val safeLabel: String) : LocalAdbCommandDescriptor {
    PRINTF_TEST("printf test"),
    MISSING_PROCESS_QUERY("ps absent-package query")
}

internal val LocalAdbReadOnlyCommand.Companion.PRINTF_TEST: LocalAdbReadOnlyCommand
    get() = LocalAdbReadOnlyCommand(
        kind = DebugLocalAdbCommandKind.PRINTF_TEST,
        shellCommand = "printf test",
        outputLimit = 1024
    )

internal val LocalAdbReadOnlyCommand.Companion.MISSING_PROCESS_QUERY: LocalAdbReadOnlyCommand
    get() = LocalAdbReadOnlyCommand(
        kind = DebugLocalAdbCommandKind.MISSING_PROCESS_QUERY,
        shellCommand = "ps -A -o PID,NAME 2>/dev/null | awk 'NR == 1 || \$2 == \"com.burgess.schedule.__localadb_absent_probe__\" || index(\$2, \"com.burgess.schedule.__localadb_absent_probe__:\") == 1'",
        outputLimit = 96 * 1024
    )
