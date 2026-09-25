package com.burgess.schedule.school.sync.localadb

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.ADB_CONNECTED
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.ADB_CONNECTING
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.ADB_CONNECTION_FAILED
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.ID_EXECUTED
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.ID_FAILED
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.ID_RUNNING
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.NOT_RUN
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.PAIRING
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.PAIRING_FAILED
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.PAIRING_SUCCESS
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.SHELL_FAILED
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.SHELL_OPENED
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.SHELL_OPENING
import com.burgess.schedule.ui.components.AppCard
import com.burgess.schedule.ui.components.PillButton
import com.burgess.schedule.ui.components.PillStyle
import com.burgess.schedule.ui.components.SectionLabel
import com.burgess.schedule.ui.components.SubPageScaffold
import com.burgess.schedule.ui.theme.AppSpace
import com.burgess.schedule.ui.theme.AppType
import com.burgess.schedule.ui.theme.LocalAppColors
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

internal enum class WebViewSocketScanState {
    NOT_RUN,
    SCANNING,
    SNAPSHOT_RECORDED,
    DIFF_COMPLETE,
    SCAN_FAILED
}

internal enum class CommandExecutorProbeState {
    NOT_RUN,
    RUNNING,
    SUCCESS,
    FAILED
}

internal data class CommandExecutorProbeResult(
    val state: CommandExecutorProbeState = CommandExecutorProbeState.NOT_RUN,
    val detail: String = ""
)

internal data class LocalAdbUidCheckResult(
    val idState: LocalAdbCheckState,
    val output: String,
    val uidVerified: Boolean,
    val uidStatus: String,
    val errorMessage: String?
)

private const val MAX_ID_OUTPUT_CHARS = 1024
private const val MAX_TARGETS_TO_DISPLAY = 32
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
    val failure = if (receivedUid) {
        null
    } else {
        readFailureSummary ?: when (readIssue) {
            LocalAdbReadIssue.STREAM_CLOSED -> "IOException: Stream closed."
            LocalAdbReadIssue.END_OF_STREAM -> "EOF: Stream ended before a UID was received."
            LocalAdbReadIssue.TIMEOUT -> "Timeout: No UID output arrived within 5 seconds."
            LocalAdbReadIssue.OUTPUT_LIMIT -> "Command output exceeded its safety limit."
            LocalAdbReadIssue.COMMAND_FAILED -> "The id command returned a non-zero status."
            LocalAdbReadIssue.PROTOCOL_ERROR -> "The command completion marker was incomplete."
            LocalAdbReadIssue.IO_ERROR -> "IOException: Failed to read id output."
            LocalAdbReadIssue.NONE -> "The id command returned no recognizable UID."
        }
    }
    return LocalAdbUidCheckResult(
        idState = if (receivedUid) ID_EXECUTED else ID_FAILED,
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

private fun safeFailure(error: Exception, secret: String? = null): String {
    var message = error.message.orEmpty()
    if (!secret.isNullOrEmpty()) message = message.replace(secret, "[已隐藏]")
    message = message.replace(Regex("\\b\\d{6}\\b"), "[已隐藏]").take(180)
    val type = error.javaClass.simpleName.ifBlank { "Exception" }
    return if (message.isBlank()) type else "$type：$message"
}

@Composable
internal fun LocalAdbSmokeScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current.applicationContext
    val client = remember(context) { LocalAdbClient(context) }
    val operationScope = rememberCoroutineScope()
    val colors = LocalAppColors.current

    var pairingPort by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pairingState by remember { mutableStateOf(NOT_RUN) }
    var connectionState by remember { mutableStateOf(NOT_RUN) }
    var shellState by remember { mutableStateOf(NOT_RUN) }
    var idState by remember { mutableStateOf(NOT_RUN) }
    var idOutput by remember { mutableStateOf("") }
    var uidState by remember { mutableStateOf("未验证") }
    var uidVerified by remember { mutableStateOf(false) }
    var socketScanState by remember { mutableStateOf(WebViewSocketScanState.NOT_RUN) }
    var globalSocketSnapshots by remember { mutableStateOf<Map<GlobalSocketSnapshotSlot, GlobalSocketSnapshot>>(emptyMap()) }
    var activeSnapshotSlot by remember { mutableStateOf<GlobalSocketSnapshotSlot?>(null) }
    var repeatedSocketDiff by remember { mutableStateOf<RepeatedUnixSocketDiff?>(null) }
    var experimentClassification by remember { mutableStateOf<GlobalSocketExperimentClassification?>(null) }
    var socketScanMessage by remember { mutableStateOf<String?>(null) }
    var mapsScanResult by remember { mutableStateOf<ProcMapsScanResult?>(null) }
    var mapsScanMessage by remember { mutableStateOf<String?>(null) }
    var commandProbe by remember { mutableStateOf(CommandExecutorProbeResult()) }
    var commandProbeResults by remember { mutableStateOf<Map<LocalAdbCommandKind, CommandExecutorProbeResult>>(emptyMap()) }
    var devToolsStateHistory by remember { mutableStateOf<List<DevToolsDiscoveryState>>(emptyList()) }
    var devToolsResult by remember { mutableStateOf<DevToolsTargetDiscoveryResult?>(null) }
    var devToolsMessage by remember { mutableStateOf<String?>(null) }
    var devToolsProcessQueryStatus by remember { mutableStateOf("未查询") }
    var probePort by remember { mutableStateOf<Int?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }

    fun recordCommandProbe(kind: LocalAdbCommandKind, result: CommandExecutorProbeResult) {
        commandProbe = result
        commandProbeResults = commandProbeResults + (kind to result)
    }

    DisposableEffect(client) {
        onDispose {
            val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            cleanupScope.launch {
                try {
                    client.close()
                } catch (_: Exception) {
                    // The one-shot PoC has no background connection after the page is closed.
                } finally {
                    cleanupScope.cancel()
                }
            }
        }
    }

    fun pairWithWirelessDebuggingCode() {
        if (busy) return
        val port = pairingPort.toIntOrNull()
        val submittedCode = pairingCode
        if (port == null || port !in 1..65535 || submittedCode.length != 6 || submittedCode.any { !it.isDigit() }) {
            lastError = "请输入有效配对端口和 6 位配对码。"
            pairingState = PAIRING_FAILED
            return
        }

        pairingCode = ""
        lastError = null
        pairingState = PAIRING
        connectionState = NOT_RUN
        shellState = NOT_RUN
        idState = NOT_RUN
        idOutput = ""
        uidState = "未验证"
        uidVerified = false
        socketScanState = WebViewSocketScanState.NOT_RUN
        globalSocketSnapshots = emptyMap()
        activeSnapshotSlot = null
        repeatedSocketDiff = null
        experimentClassification = null
        socketScanMessage = null
        mapsScanResult = null
        mapsScanMessage = null
        commandProbeResults = emptyMap()
        devToolsStateHistory = emptyList()
        devToolsResult = null
        devToolsMessage = null
        devToolsProcessQueryStatus = "未查询"
        probePort = null
        busy = true
        operationScope.launch {
            try {
                val paired = client.pair(port, submittedCode)
                if (paired) {
                    pairingState = PAIRING_SUCCESS
                } else {
                    pairingState = PAIRING_FAILED
                    lastError = "配对没有完成。请确认端口和配对码来自本机当前的 Wireless Debugging 配对窗口。"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                pairingState = PAIRING_FAILED
                lastError = safeFailure(exception, submittedCode)
            } finally {
                busy = false
            }
        }
    }

    fun connectAndRunId() {
        if (busy) return
        val port = connectPort.toIntOrNull()
        if (port == null || port !in 1..65535) {
            lastError = "请输入 Wireless Debugging 主页面显示的有效连接端口。"
            connectionState = ADB_CONNECTION_FAILED
            idState = ID_FAILED
            idOutput = ""
            uidState = "UID_2000_NOT_VERIFIED（连接未开始）"
            uidVerified = false
            recordCommandProbe(
                LocalAdbCommandKind.ID,
                CommandExecutorProbeResult(CommandExecutorProbeState.FAILED, lastError.orEmpty())
            )
            return
        }

        if (probePort != port) commandProbeResults = emptyMap()
        probePort = port

        lastError = null
        connectionState = ADB_CONNECTING
        shellState = NOT_RUN
        idState = NOT_RUN
        idOutput = ""
        uidState = "未验证"
        uidVerified = false
        recordCommandProbe(
            LocalAdbCommandKind.ID,
            CommandExecutorProbeResult(CommandExecutorProbeState.RUNNING, "正在连接并运行 id")
        )
        socketScanState = WebViewSocketScanState.NOT_RUN
        globalSocketSnapshots = emptyMap()
        activeSnapshotSlot = null
        repeatedSocketDiff = null
        experimentClassification = null
        socketScanMessage = null
        mapsScanResult = null
        mapsScanMessage = null
        devToolsStateHistory = emptyList()
        devToolsResult = null
        devToolsMessage = null
        devToolsProcessQueryStatus = "未查询"
        busy = true
        operationScope.launch {
            try {
                val connected = client.connect(port)
                if (!connected) throw IOException("TLS connection was not established")
                connectionState = ADB_CONNECTED

                shellState = SHELL_OPENING
                idState = ID_RUNNING
                val readResult = client.executeReadOnly(LocalAdbReadOnlyCommand.ID)
                shellState = SHELL_OPENED
                val result = evaluateLocalAdbUidCheck(
                    readResult.output.takeIf {
                        readResult.commandCompleted && readResult.issue == LocalAdbReadIssue.NONE
                    }.orEmpty(),
                    readResult.issue,
                    readResult.failureSummary
                )
                idOutput = result.output
                idState = result.idState
                uidState = result.uidStatus
                uidVerified = result.uidVerified
                lastError = result.errorMessage
                val idProbe = when {
                    readResult.issue != LocalAdbReadIssue.NONE || !readResult.commandCompleted ->
                        CommandExecutorProbeResult(
                            CommandExecutorProbeState.FAILED,
                            readResult.failureSummary ?: "id 未收到结束标记。"
                        )
                    result.uidVerified ->
                        CommandExecutorProbeResult(CommandExecutorProbeState.SUCCESS, "UID 2000 已验证")
                    else ->
                        CommandExecutorProbeResult(
                            CommandExecutorProbeState.FAILED,
                            "id 已完成，但 UID 校验失败：${result.uidStatus}"
                        )
                }
                recordCommandProbe(LocalAdbCommandKind.ID, idProbe)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                when (connectionState) {
                    ADB_CONNECTING -> connectionState = ADB_CONNECTION_FAILED
                    ADB_CONNECTED -> if (shellState == SHELL_OPENING) shellState = SHELL_FAILED
                    else -> Unit
                }
                idState = ID_FAILED
                idOutput = ""
                uidState = "UID_2000_NOT_VERIFIED（连接或执行失败）"
                uidVerified = false
                lastError = safeFailure(exception)
                recordCommandProbe(
                    LocalAdbCommandKind.ID,
                    CommandExecutorProbeResult(CommandExecutorProbeState.FAILED, lastError.orEmpty())
                )
            } finally {
                busy = false
            }
        }
    }

    fun discoverDevToolsTargets() {
        if (busy) return
        val port = connectPort.toIntOrNull()
        if (port == null || port !in 1..65535) {
            devToolsMessage = "请输入 Wireless Debugging 主页面显示的有效连接端口。"
            return
        }
        if (!uidVerified || idState != ID_EXECUTED || probePort != port) {
            devToolsMessage = "请先使用当前连接端口运行 id，并确认 UID 2000。"
            return
        }

        devToolsStateHistory = emptyList()
        devToolsResult = null
        devToolsMessage = null
        devToolsProcessQueryStatus = "未查询"
        busy = true
        operationScope.launch {
            fun recordState(state: DevToolsDiscoveryState) {
                devToolsStateHistory = devToolsStateHistory + state
            }

            try {
                connectionState = ADB_CONNECTING
                if (!client.connect(port)) throw IOException("TLS connection was not established")
                connectionState = ADB_CONNECTED
                recordState(DevToolsDiscoveryState.LOCAL_ADB_CONNECTED)

                recordState(DevToolsDiscoveryState.DEVTOOLS_SOCKET_SCAN_START)
                shellState = SHELL_OPENING
                val unixOutput = client.executeReadOnly(LocalAdbReadOnlyCommand.PROC_UNIX_READ)
                val unixResult = if (unixOutput.issue == LocalAdbReadIssue.NONE &&
                    unixOutput.commandCompleted && unixOutput.exitCode == 0
                ) {
                    parseGlobalUnixSocketTable(unixOutput.output, unixOutput.exitCode)
                } else {
                    GlobalUnixSocketTableResult(ProcUnixScanState.PROC_UNIX_FAILED, null, emptyList())
                }
                if (unixResult.state != ProcUnixScanState.PROC_UNIX_SUCCESS) {
                    recordState(DevToolsDiscoveryState.DEVTOOLS_SOCKET_SCAN_FAILED)
                    shellState = SHELL_OPENED
                    devToolsMessage = unixOutput.failureSummary
                        ?: "读取 /proc/net/unix 失败；没有扫描任何 socket。"
                    return@launch
                }

                val candidates = findDevToolsSocketCandidates(unixResult.sockets)
                recordState(DevToolsDiscoveryState.DEVTOOLS_SOCKET_SCAN_SUCCESS)

                val processOutput = client.executeReadOnly(LocalAdbReadOnlyCommand.PROCESS_QUERY)
                val processResult = if (processOutput.issue == LocalAdbReadIssue.NONE &&
                    processOutput.commandCompleted && processOutput.exitCode == 0
                ) {
                    parseProcessQuery(processOutput.output, processOutput.exitCode)
                } else {
                    ProcessQueryResult(ProcessQueryScanState.PROCESS_QUERY_FAILED, emptyList())
                }
                devToolsProcessQueryStatus = when (processResult.state) {
                    ProcessQueryScanState.PROCESS_QUERY_SUCCESS -> "PROCESS_QUERY_SUCCESS · ${processResult.processes.size} 个企业微信进程"
                    else -> "PROCESS_QUERY_FAILED · socket PID 无法关联进程"
                }
                shellState = SHELL_OPENED

                devToolsResult = discoverDevToolsTargets(
                    client = client,
                    candidates = candidates,
                    processes = processResult.processes,
                    onState = ::recordState
                )
                devToolsMessage = when (devToolsResult?.finalState) {
                    DevToolsDiscoveryState.SCHOOL_TARGET_FOUND -> "已发现山东商务职业学院教务系统 WebView。"
                    DevToolsDiscoveryState.SCHOOL_HOST_TARGET_FOUND -> "发现学校域名页面，但标题不是“我的课表”。"
                    DevToolsDiscoveryState.DEVTOOLS_CONNECTED_NO_SCHOOL_TARGET -> "DevTools HTTP 可用，尚未发现学校页面。"
                    DevToolsDiscoveryState.DEVTOOLS_SOCKET_NOT_FOUND -> "/proc/net/unix 读取成功，但没有符合规则的 abstract DevTools socket。"
                    DevToolsDiscoveryState.DEVTOOLS_SOCKET_OPEN_FAILED -> "发现候选 socket，但 localabstract 打开失败。查看各候选的安全错误类型。"
                    DevToolsDiscoveryState.DEVTOOLS_HTTP_FAILED -> "socket 已打开，但 DevTools HTTP discovery 没有成功。查看 endpoint 和错误状态。"
                    DevToolsDiscoveryState.TARGET_LIST_PARSE_FAILED -> "HTTP 返回成功，但 Target JSON 无法解析。"
                    else -> null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                if (connectionState == ADB_CONNECTING) connectionState = ADB_CONNECTION_FAILED
                if (shellState == SHELL_OPENING) shellState = SHELL_FAILED
                devToolsMessage = "${exception.javaClass.simpleName.ifBlank { "Exception" }}：Target discovery 未完成。"
            } finally {
                busy = false
            }
        }
    }

    fun runExecutorProbe(command: LocalAdbReadOnlyCommand, summarize: (String) -> String?) {
        if (busy) return
        val port = connectPort.toIntOrNull()
        if (port == null || port !in 1..65535) {
            recordCommandProbe(command.kind, CommandExecutorProbeResult(CommandExecutorProbeState.FAILED, "请输入有效的 Wireless Debugging 连接端口。"))
            return
        }
        if (probePort != port) commandProbeResults = emptyMap()
        probePort = port
        recordCommandProbe(command.kind, CommandExecutorProbeResult(CommandExecutorProbeState.RUNNING, "等待 ${command.kind.safeLabel} 的结束标记"))
        lastError = null
        busy = true
        operationScope.launch {
            try {
                if (!client.connect(port)) throw IOException("TLS connection was not established")
                val result = client.executeReadOnly(command)
                val printfAnalysis = if (command.kind == LocalAdbCommandKind.PRINTF_TEST) {
                    analyzePrintfTestOutput(result.output, result.exitCode)
                } else {
                    null
                }
                val diagnostic = printfAnalysis?.diagnosticText()?.let { "\n$it" }.orEmpty()
                if (result.issue != LocalAdbReadIssue.NONE || !result.commandCompleted) {
                    recordCommandProbe(command.kind, CommandExecutorProbeResult(
                        CommandExecutorProbeState.FAILED,
                        (result.failureSummary ?: "${command.kind.safeLabel} 未收到结束标记；部分输出：${result.partialOutputReceived}") +
                            diagnostic
                    ))
                } else {
                    val detail = if (printfAnalysis != null) {
                        if (printfAnalysis.succeeded) "printf test 输出正确" else null
                    } else {
                        summarize(result.output)
                    }
                    if (result.exitCode != 0 || detail == null) {
                        recordCommandProbe(command.kind, CommandExecutorProbeResult(
                            CommandExecutorProbeState.FAILED,
                            "${command.kind.safeLabel} 已收到结束标记，但命令或结果校验失败。$diagnostic"
                        ))
                    } else {
                        recordCommandProbe(command.kind, CommandExecutorProbeResult(
                            CommandExecutorProbeState.SUCCESS,
                            "$detail · 已收到结束标记$diagnostic"
                        ))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                recordCommandProbe(command.kind, CommandExecutorProbeResult(CommandExecutorProbeState.FAILED, safeFailure(exception)))
            } finally {
                busy = false
            }
        }
    }

    fun recordGlobalSnapshot(slot: GlobalSocketSnapshotSlot) {
        if (busy) return
        if (!uidVerified) {
            socketScanMessage = "请先运行 id 并验证 UID 2000，再记录全局 socket。"
            socketScanState = WebViewSocketScanState.SCAN_FAILED
            return
        }
        val port = connectPort.toIntOrNull()
        if (port == null || port !in 1..65535) {
            socketScanMessage = "请输入 Wireless Debugging 主页面显示的有效连接端口。"
            connectionState = ADB_CONNECTION_FAILED
            socketScanState = WebViewSocketScanState.SCAN_FAILED
            return
        }

        val orderedSlots = GlobalSocketSnapshotSlot.values().toList()
        val slotIndex = orderedSlots.indexOf(slot)
        val previousSlot = orderedSlots.getOrNull(slotIndex - 1)
        if (previousSlot != null &&
            globalSocketSnapshots[previousSlot]?.procUnixState != ProcUnixScanState.PROC_UNIX_SUCCESS
        ) {
            socketScanMessage = "请先成功记录 ${previousSlot.label}。"
            socketScanState = WebViewSocketScanState.SCAN_FAILED
            return
        }

        val invalidatedSlots = orderedSlots.drop(slotIndex)
        globalSocketSnapshots = globalSocketSnapshots - invalidatedSlots.toSet()
        repeatedSocketDiff = null
        experimentClassification = null
        mapsScanResult = null
        mapsScanMessage = null
        socketScanMessage = null
        lastError = null
        activeSnapshotSlot = slot
        socketScanState = WebViewSocketScanState.SCANNING
        connectionState = ADB_CONNECTING
        busy = true

        operationScope.launch {
            try {
                if (!client.connect(port)) throw IOException("TLS connection was not established")
                connectionState = ADB_CONNECTED
                shellState = SHELL_OPENING

                suspend fun runCommand(command: LocalAdbReadOnlyCommand): Pair<LocalAdbCommandOutput?, String?> {
                    val result = try {
                        client.executeReadOnly(command)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (exception: Exception) {
                        return null to safeFailure(exception)
                    }
                    val failure = when {
                        result.issue != LocalAdbReadIssue.NONE -> result.failureSummary
                            ?: "${command.kind.safeLabel} 未完成；是否收到部分输出：${result.partialOutputReceived}"
                        !result.commandCompleted -> "${command.kind.safeLabel} 未收到结束标记；是否收到部分输出：${result.partialOutputReceived}"
                        result.exitCode != 0 -> "${command.kind.safeLabel} 退出状态为 ${result.exitCode}"
                        else -> null
                    }
                    return if (failure == null) result to null else null to failure
                }

                val (unixOutput, unixFailure) = runCommand(LocalAdbReadOnlyCommand.PROC_UNIX_READ)
                val unixResult = unixOutput?.let { parseGlobalUnixSocketTable(it.output, it.exitCode) }
                    ?: GlobalUnixSocketTableResult(ProcUnixScanState.PROC_UNIX_FAILED, null, emptyList())
                val normalizedUnixFailure = when {
                    unixFailure != null -> unixFailure
                    unixResult.state == ProcUnixScanState.PROC_UNIX_FAILED -> "未找到有效的 /proc/net/unix 表头或具名 socket 数据。"
                    else -> null
                }

                val (processOutput, processFailure) = runCommand(LocalAdbReadOnlyCommand.PROCESS_QUERY)
                val processResult = processOutput?.let { parseProcessQuery(it.output, it.exitCode) }
                    ?: ProcessQueryResult(ProcessQueryScanState.PROCESS_QUERY_FAILED, emptyList())
                val normalizedProcessFailure = when {
                    processFailure != null -> processFailure
                    processResult.state == ProcessQueryScanState.PROCESS_QUERY_FAILED -> "企业微信进程查询没有返回有效结果。"
                    else -> null
                }
                shellState = SHELL_OPENED

                globalSocketSnapshots = globalSocketSnapshots + (slot to GlobalSocketSnapshot(
                    stats = unixResult.stats,
                    sockets = unixResult.sockets,
                    processes = processResult.processes,
                    procUnixState = unixResult.state,
                    processQueryState = processResult.state,
                    procUnixFailure = normalizedUnixFailure,
                    processQueryFailure = normalizedProcessFailure
                ))
                socketScanState = if (unixResult.state == ProcUnixScanState.PROC_UNIX_SUCCESS) {
                    WebViewSocketScanState.SNAPSHOT_RECORDED
                } else {
                    WebViewSocketScanState.SCAN_FAILED
                }
                socketScanMessage = listOfNotNull(
                    normalizedUnixFailure?.let { "${slot.name} PROC_UNIX_READ_FAILED：$it" },
                    normalizedProcessFailure?.let { "${slot.name} PROCESS_QUERY_FAILED：$it" }
                ).joinToString(" · ").ifBlank { null }
                lastError = socketScanMessage
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                if (connectionState == ADB_CONNECTING) connectionState = ADB_CONNECTION_FAILED
                if (shellState == SHELL_OPENING) shellState = SHELL_FAILED
                socketScanState = WebViewSocketScanState.SCAN_FAILED
                socketScanMessage = "${slot.name}：${safeFailure(exception)}"
                lastError = socketScanMessage
            } finally {
                activeSnapshotSlot = null
                busy = false
            }
        }
    }

    fun calculateRepeatedSocketDiff() {
        val ordered = GlobalSocketSnapshotSlot.values().mapNotNull(globalSocketSnapshots::get)
        if (ordered.size != GlobalSocketSnapshotSlot.values().size) {
            socketScanMessage = "请先依次完成 A1、B1、A2、B2 四次快照。"
            socketScanState = WebViewSocketScanState.SCAN_FAILED
            return
        }
        if (!canCompareGlobalSocketSnapshots(ordered)) {
            repeatedSocketDiff = null
            experimentClassification = GlobalSocketExperimentClassification.PROC_UNIX_READ_FAILED
            socketScanState = WebViewSocketScanState.DIFF_COMPLETE
            socketScanMessage = "至少一次 /proc/net/unix 读取失败；差分结果不可判定，请重做失败快照。"
            return
        }

        val diff = repeatedSocketDiff(
            a1 = ordered[0].sockets,
            b1 = ordered[1].sockets,
            a2 = ordered[2].sockets,
            b2 = ordered[3].sockets
        )
        repeatedSocketDiff = diff
        experimentClassification = classifyGlobalSocketExperiment(ordered, diff)
        socketScanState = WebViewSocketScanState.DIFF_COMPLETE
        socketScanMessage = null
    }

    fun scanWxaContainerMaps() {
        if (busy) return
        val port = connectPort.toIntOrNull()
        if (port == null || port !in 1..65535) {
            mapsScanMessage = "请输入 Wireless Debugging 主页面显示的有效连接端口。"
            return
        }
        val candidate = GlobalSocketSnapshotSlot.values().reversed()
            .asSequence()
            .mapNotNull(globalSocketSnapshots::get)
            .filter { it.processQueryState == ProcessQueryScanState.PROCESS_QUERY_SUCCESS }
            .flatMap { it.processes.asSequence() }
            .firstOrNull { it.processName == "com.tencent.wework:wxa_container0" }
        if (candidate == null) {
            mapsScanResult = null
            mapsScanMessage = "最近一次成功的企业微信进程查询未发现 wxa_container0；请先记录一组进程快照。"
            return
        }

        mapsScanResult = ProcMapsScanResult(candidate.pid, ProcMapsScanState.MAPS_SCAN_START, emptyList())
        mapsScanMessage = "正在只读检查 wxa_container0 的筛选库名。"
        lastError = null
        busy = true
        operationScope.launch {
            try {
                if (!client.connect(port)) throw IOException("TLS connection was not established")
                connectionState = ADB_CONNECTED
                shellState = SHELL_OPENING
                val commandResult = client.executeReadOnly(LocalAdbReadOnlyCommand.mapsScan(candidate.pid))
                shellState = SHELL_OPENED
                val failure = when {
                    commandResult.issue != LocalAdbReadIssue.NONE -> commandResult.failureSummary
                        ?: "maps 命令未完成。"
                    !commandResult.commandCompleted -> "maps 命令未收到结束标记。"
                    commandResult.exitCode != 0 -> "maps 命令退出状态为 ${commandResult.exitCode}。"
                    else -> null
                }
                if (failure != null) {
                    mapsScanResult = ProcMapsScanResult(candidate.pid, ProcMapsScanState.MAPS_SCAN_FAILED, emptyList())
                    mapsScanMessage = "MAPS_SCAN_FAILED：$failure"
                    return@launch
                }

                val result = parseProcMapsScan(commandResult.output, candidate.pid, commandResult.exitCode)
                mapsScanResult = result
                mapsScanMessage = when (result.state) {
                    ProcMapsScanState.MAPS_SCAN_SUCCESS -> "读取成功；仅保留匹配关键词的库 basename。"
                    ProcMapsScanState.MAPS_SCAN_PERMISSION_DENIED -> "MAPS_PERMISSION_DENIED；已停止该项检查。"
                    else -> "MAPS_SCAN_FAILED：未收到有效扫描结果标记。"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                if (shellState == SHELL_OPENING) shellState = SHELL_FAILED
                mapsScanResult = ProcMapsScanResult(candidate.pid, ProcMapsScanState.MAPS_SCAN_FAILED, emptyList())
                mapsScanMessage = "MAPS_SCAN_FAILED：${safeFailure(exception)}"
            } finally {
                busy = false
            }
        }
    }

    SubPageScaffold("Local ADB / DevTools Target", onBack) {
        Text(
            "只连接本机 127.0.0.1。你需要在系统 Wireless Debugging 页面手动配对；本页不会打开或更改系统设置。",
            style = AppType.body,
            color = colors.mut
        )

        SectionLabel("步骤 1 · 配对")
        AppCard(Modifier.fillMaxWidth()) {
            Text(
                "在系统设置的 Wireless Debugging 页面选择“使用配对码配对”，将系统显示的配对端口和 6 位配对码填入这里。",
                style = AppType.body,
                color = colors.ink
            )
            OutlinedTextField(
                value = pairingPort,
                onValueChange = { pairingPort = it.filter(Char::isDigit).take(5) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("配对端口") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = pairingCode,
                onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("6 位配对码（仅本次使用）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            )
            PillButton(
                if (busy && pairingState == PAIRING) "配对中…" else "配对本机 Wireless Debugging",
                onClick = ::pairWithWirelessDebuggingCode,
                style = PillStyle.Primary
            )
            Text("配对状态：${pairingState.name}", style = AppType.caption, color = colors.mut)
        }

        SectionLabel("步骤 2 · TLS 连接与 UID 验证")
        AppCard(Modifier.fillMaxWidth()) {
            Text("回到 Wireless Debugging 主页面，填写当前显示的连接端口。连接后可运行 id 确认 UID。", style = AppType.body, color = colors.ink)
            OutlinedTextField(
                value = connectPort,
                onValueChange = { connectPort = it.filter(Char::isDigit).take(5) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Wireless Debugging 连接端口") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            PillButton(
                if (busy && connectionState == ADB_CONNECTING) "连接并执行中…" else "连接并运行 id",
                onClick = ::connectAndRunId,
                style = PillStyle.Primary
            )
            Text("ADB 状态：${connectionState.name}", style = AppType.caption, color = colors.mut)
            Text("Shell 状态：${shellState.name}", style = AppType.caption, color = colors.mut)
            Text("命令状态：${idState.name}", style = AppType.caption, color = colors.mut)
            Text("UID 校验：$uidState", style = AppType.caption, color = colors.ink)
            if (idOutput.isNotBlank()) {
                SectionLabel("id 输出")
                Text(idOutput, style = AppType.caption, color = colors.ink)
            }
        }

        SectionLabel("Phase 3 · XWeb DevTools Target Discovery")
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
                Text(
                    "扫描 /proc/net/unix 中的具名 abstract DevTools socket，再通过 localabstract 请求 /json/list；仅在该 endpoint 不受支持时尝试 /json。只显示 Target 的 id、type、title、脱敏 URL 和 socket 标识，不连接 WebSocket。",
                    style = AppType.body,
                    color = colors.ink
                )
                Text(
                    "仅在你点击后运行。URL 不显示 query 或 fragment；不会读取页面正文、DOM、JavaScript、Cookie、Storage 或学校接口。",
                    style = AppType.caption,
                    color = colors.mut
                )
                PillButton(
                    if (busy && devToolsStateHistory.lastOrNull() != null) "Target discovery 扫描中…" else "发现 DevTools Targets",
                    onClick = ::discoverDevToolsTargets,
                    style = PillStyle.Primary
                )
                Text(
                    "ADB：${if (connectionState == ADB_CONNECTED) "LOCAL_ADB_CONNECTED" else connectionState.name}",
                    style = AppType.caption,
                    color = colors.ink
                )
                Text("进程关联：$devToolsProcessQueryStatus", style = AppType.caption, color = colors.mut)
                devToolsStateHistory.takeLast(16).forEach { state ->
                    Text(state.name, style = AppType.caption, color = colors.mut)
                }
                devToolsMessage?.let {
                    val failedState = devToolsResult?.finalState?.let { state ->
                        state == DevToolsDiscoveryState.DEVTOOLS_SOCKET_OPEN_FAILED ||
                            state == DevToolsDiscoveryState.DEVTOOLS_HTTP_FAILED ||
                            state == DevToolsDiscoveryState.TARGET_LIST_PARSE_FAILED
                    } == true
                    Text(it, style = AppType.caption, color = if (failedState || it.contains("失败")) colors.danger else colors.ink)
                }
                devToolsResult?.let { result ->
                    Text(
                        "结果：${result.finalState.name} · 候选 socket ${result.candidates.size} 个 · HTTP 请求 ${result.attempts.size} 次 · endpoint ${result.endpointUsed?.path ?: "无"}",
                        style = AppType.caption,
                        color = colors.ink
                    )
                    result.attempts.forEach { attempt ->
                        Text(
                            "${attempt.endpointPath} · ${attempt.socketName.take(160)} · inode=${attempt.inode} · opened=${attempt.opened} · HTTP=${attempt.httpStatus ?: "—"} · targets=${attempt.targetCount ?: "—"}${attempt.failure?.let { " · $it" }.orEmpty()}",
                            style = AppType.caption,
                            color = if (attempt.failure != null) colors.danger else colors.mut
                        )
                    }
                    if (result.targets.isEmpty()) {
                        Text("没有可显示的 Target。", style = AppType.caption, color = colors.mut)
                    } else {
                        result.targets.take(MAX_TARGETS_TO_DISPLAY).forEach { target ->
                            SectionLabel("Target · ${target.type.ifBlank { "未知类型" }}")
                            Text("进程/容器：${target.containerName ?: target.processName ?: "未按 PID 关联"}", style = AppType.caption, color = colors.ink)
                            Text("标题：${target.title.ifBlank { "（空标题）" }}", style = AppType.caption, color = colors.ink)
                            if (target.displayUrl.isNotBlank()) {
                                Text("URL：${target.displayUrl}", style = AppType.caption, color = colors.ink)
                            }
                            Text("socket：${target.socketName.take(160)} · target id：${target.id.ifBlank { "（无）" }}", style = AppType.caption, color = colors.mut)
                            Text(
                                "学校匹配：${target.schoolMatch.name}${if (target.pathMatchesSchoolPage) " · path 匹配 /h5/index.html" else ""}",
                                style = AppType.caption,
                                color = if (target.schoolMatch == SchoolTargetMatch.SCHOOL_TARGET_FOUND) colors.ink else colors.mut
                            )
                        }
                        if (result.targets.size > MAX_TARGETS_TO_DISPLAY) {
                            Text("仅显示前 $MAX_TARGETS_TO_DISPLAY 个 Target。", style = AppType.caption, color = colors.mut)
                        }
                    }
                }
            }
        }

        SectionLabel("命令结束协议回归检查")
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
                Text(
                    "每条只读命令都通过独立随机结束标记确认完成。按顺序验证 id、printf、/proc/net/unix 行数和不存在的包查询；不会显示完整 proc 内容。",
                    style = AppType.body,
                    color = colors.ink
                )
                PillButton(
                    if (busy && commandProbe.state == CommandExecutorProbeState.RUNNING) "命令验证中…" else "验证 printf test",
                    onClick = {
                        runExecutorProbe(LocalAdbReadOnlyCommand.PRINTF_TEST) { output ->
                            if (output == "test") {
                                "printf test 输出正确"
                            } else {
                                null
                            }
                        }
                    },
                    style = PillStyle.Secondary
                )
                PillButton(
                    "验证 /proc/net/unix 读取",
                    onClick = {
                        runExecutorProbe(LocalAdbReadOnlyCommand.PROC_UNIX_READ) { output ->
                            val result = parseProcUnixTable(output, exitCode = 0)
                            result.stats?.let { "读取成功 · ${it.totalLines} 行" }
                        }
                    },
                    style = PillStyle.Secondary
                )
                PillButton(
                    "验证不存在的进程查询",
                    onClick = {
                        runExecutorProbe(LocalAdbReadOnlyCommand.MISSING_PROCESS_QUERY) { output ->
                            val result = parseProcessQueryForPackage(
                                output,
                                exitCode = 0,
                                packageName = "com.burgess.schedule.__localadb_absent_probe__"
                            )
                            if (result.state == ProcessQueryScanState.PROCESS_QUERY_SUCCESS && result.processes.isEmpty()) {
                                "查询完成 · 匹配 0 个进程"
                            } else {
                                null
                            }
                        }
                    },
                    style = PillStyle.Secondary
                )
                Text("执行器状态：${commandProbe.state}", style = AppType.caption, color = colors.ink)
                if (commandProbe.detail.isNotBlank()) {
                    Text(commandProbe.detail, style = AppType.caption, color = if (commandProbe.state == CommandExecutorProbeState.FAILED) colors.danger else colors.mut)
                }
                listOf(
                    LocalAdbCommandKind.ID,
                    LocalAdbCommandKind.PRINTF_TEST,
                    LocalAdbCommandKind.PROC_UNIX_READ,
                    LocalAdbCommandKind.MISSING_PROCESS_QUERY
                ).forEach { kind ->
                    val status = commandProbeResults[kind]?.state?.name ?: "NOT_RUN"
                    Text("${kind.safeLabel}：$status", style = AppType.caption, color = if (status == CommandExecutorProbeState.FAILED.name) colors.danger else colors.mut)
                }
            }
        }

        SectionLabel("步骤 3 · 全局 Unix socket 重复 A/B")
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
                Text(
                    "每次只读保存 /proc/net/unix 中所有具名 socket，以及 com.tencent.wework 进程名称和 PID。不会读取进程 fd、连接 socket 或保存到设备。",
                    style = AppType.body,
                    color = colors.ink
                )
                Text(
                    "轮内差分按 inode 和 socket 字段判断；跨轮稳定项按名称、类型、状态和 abstract 属性匹配，并保留两轮 inode。",
                    style = AppType.caption,
                    color = colors.mut
                )
                GlobalSocketSnapshotSlot.values().forEach { slot ->
                    PillButton(
                        if (busy && activeSnapshotSlot == slot) "${slot.label} 扫描中…" else "记录 ${slot.label}",
                        onClick = { recordGlobalSnapshot(slot) },
                        style = PillStyle.Secondary
                    )
                    globalSocketSnapshots[slot]?.let { snapshot -> GlobalSocketSnapshotSummary(slot, snapshot) }
                }
                Text("扫描状态：${socketScanState.name}", style = AppType.caption, color = colors.mut)
                socketScanMessage?.let { Text(it, style = AppType.caption, color = colors.danger) }
                PillButton("计算稳定差分", onClick = ::calculateRepeatedSocketDiff, style = PillStyle.Primary)
                Text("实验分类：${experimentClassification?.name ?: "待计算"}", style = AppType.caption, color = colors.ink)

                repeatedSocketDiff?.let { diff ->
                    val a1 = globalSocketSnapshots[GlobalSocketSnapshotSlot.A1]
                    val b1 = globalSocketSnapshots[GlobalSocketSnapshotSlot.B1]
                    val a2 = globalSocketSnapshots[GlobalSocketSnapshotSlot.A2]
                    val b2 = globalSocketSnapshots[GlobalSocketSnapshotSlot.B2]

                    Text("B1 − A1 新增：${diff.firstCycle.added.size} · A1 − B1 消失：${diff.firstCycle.removed.size}", style = AppType.caption, color = colors.ink)
                    diff.firstCycle.added.forEach { socket ->
                        GlobalSocketEntryLine(
                            "第 1 轮新增",
                            socket,
                            b1?.processes.orEmpty(),
                            b1?.processQueryState == ProcessQueryScanState.PROCESS_QUERY_SUCCESS
                        )
                    }
                    diff.firstCycle.removed.forEach { socket ->
                        GlobalSocketEntryLine(
                            "第 1 轮消失",
                            socket,
                            a1?.processes.orEmpty(),
                            a1?.processQueryState == ProcessQueryScanState.PROCESS_QUERY_SUCCESS
                        )
                    }
                    WeWorkProcessDeltaSummary(
                        "第 1 轮企业微信进程变化",
                        a1?.let { before -> b1?.let { after ->
                            diffWeWorkProcesses(before.processQueryState, before.processes, after.processQueryState, after.processes)
                        } }
                    )

                    Text("B2 − A2 新增：${diff.secondCycle.added.size} · A2 − B2 消失：${diff.secondCycle.removed.size}", style = AppType.caption, color = colors.ink)
                    diff.secondCycle.added.forEach { socket ->
                        GlobalSocketEntryLine(
                            "第 2 轮新增",
                            socket,
                            b2?.processes.orEmpty(),
                            b2?.processQueryState == ProcessQueryScanState.PROCESS_QUERY_SUCCESS
                        )
                    }
                    diff.secondCycle.removed.forEach { socket ->
                        GlobalSocketEntryLine(
                            "第 2 轮消失",
                            socket,
                            a2?.processes.orEmpty(),
                            a2?.processQueryState == ProcessQueryScanState.PROCESS_QUERY_SUCCESS
                        )
                    }
                    WeWorkProcessDeltaSummary(
                        "第 2 轮企业微信进程变化",
                        a2?.let { before -> b2?.let { after ->
                            diffWeWorkProcesses(before.processQueryState, before.processes, after.processQueryState, after.processes)
                        } }
                    )

                    Text("稳定新增 socket：${diff.stableAdded.size} · 单轮新增（随机噪声候选）：${diff.randomAddedCount}", style = AppType.caption, color = colors.ink)
                    diff.stableAdded.forEach { addition ->
                        val categories = classifyGlobalSocketName(addition.firstCycle.socketName).joinToString(" · ") { it.label }
                        val pidMatch = socketNameMatchesWeWorkPid(addition.firstCycle.socketName, b1?.processes.orEmpty()) ||
                            socketNameMatchesWeWorkPid(addition.secondCycle.socketName, b2?.processes.orEmpty())
                        val pidCorrelation = when {
                            pidMatch -> "NAME_PID_MATCH"
                            b1?.processQueryState != ProcessQueryScanState.PROCESS_QUERY_SUCCESS ||
                                b2?.processQueryState != ProcessQueryScanState.PROCESS_QUERY_SUCCESS -> "PID 对照不可用"
                            else -> "未匹配当前企业微信 PID"
                        }
                        SectionLabel("稳定新增 · $categories · $pidCorrelation")
                        GlobalSocketEntryLine(
                            "第 1 轮",
                            addition.firstCycle,
                            b1?.processes.orEmpty(),
                            b1?.processQueryState == ProcessQueryScanState.PROCESS_QUERY_SUCCESS
                        )
                        GlobalSocketEntryLine(
                            "第 2 轮",
                            addition.secondCycle,
                            b2?.processes.orEmpty(),
                            b2?.processQueryState == ProcessQueryScanState.PROCESS_QUERY_SUCCESS
                        )
                    }
                    diff.randomAddedFirstCycle.forEach { socket ->
                        GlobalSocketEntryLine(
                            "仅第 1 轮新增",
                            socket,
                            b1?.processes.orEmpty(),
                            b1?.processQueryState == ProcessQueryScanState.PROCESS_QUERY_SUCCESS
                        )
                    }
                    diff.randomAddedSecondCycle.forEach { socket ->
                        GlobalSocketEntryLine(
                            "仅第 2 轮新增",
                            socket,
                            b2?.processes.orEmpty(),
                            b2?.processQueryState == ProcessQueryScanState.PROCESS_QUERY_SUCCESS
                        )
                    }
                }
            }
        }

        SectionLabel("独立检查 · wxa_container0 内核库")
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
                Text(
                    "仅在点击后读取最近快照中的 com.tencent.wework:wxa_container0 maps；界面只显示匹配关键词的库 basename。",
                    style = AppType.body,
                    color = colors.ink
                )
                PillButton(
                    if (busy && mapsScanResult?.state == ProcMapsScanState.MAPS_SCAN_START) "检查中…" else "检查 wxa_container0 maps",
                    onClick = ::scanWxaContainerMaps,
                    style = PillStyle.Secondary
                )
                mapsScanResult?.let { result ->
                    Text("PID ${result.pid} · ${result.state.name}", style = AppType.caption, color = if (result.state == ProcMapsScanState.MAPS_SCAN_PERMISSION_DENIED || result.state == ProcMapsScanState.MAPS_SCAN_FAILED) colors.danger else colors.ink)
                    result.libraries.forEach { library ->
                        Text("${library.basename} · ${library.keywords.joinToString()}", style = AppType.caption, color = colors.ink)
                    }
                    if (result.state == ProcMapsScanState.MAPS_SCAN_SUCCESS) {
                        Text("内核线索：${identifyWebViewKernel(result.libraries).displayName}", style = AppType.caption, color = colors.mut)
                    }
                }
                mapsScanMessage?.let { Text(it, style = AppType.caption, color = if (mapsScanResult?.state == ProcMapsScanState.MAPS_SCAN_PERMISSION_DENIED || mapsScanResult?.state == ProcMapsScanState.MAPS_SCAN_FAILED) colors.danger else colors.mut) }
            }
        }

        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
                Text("密钥保存在 App 私有目录。配对码不会保存或写入日志。扫描只在点击按钮后执行。", style = AppType.caption, color = colors.mut)
                if (lastError != null) Text("错误：$lastError", style = AppType.caption, color = colors.danger)
            }
        }
    }
}

@Composable
private fun GlobalSocketSnapshotSummary(slot: GlobalSocketSnapshotSlot, snapshot: GlobalSocketSnapshot) {
    val colors = LocalAppColors.current
    SectionLabel(slot.label)
    Text("PROC_UNIX：${snapshot.procUnixState.name}", style = AppType.caption, color = if (snapshot.procUnixState == ProcUnixScanState.PROC_UNIX_FAILED) colors.danger else colors.ink)
    snapshot.stats?.let { stats ->
        Text("/proc/net/unix：${stats.totalLines} 行 · 具名 ${stats.namedSockets} · abstract ${stats.abstractSockets}", style = AppType.caption, color = colors.mut)
    }
    snapshot.procUnixFailure?.let { Text(it, style = AppType.caption, color = colors.danger) }
    Text("PROCESS_QUERY：${snapshot.processQueryState.name}", style = AppType.caption, color = if (snapshot.processQueryState == ProcessQueryScanState.PROCESS_QUERY_FAILED) colors.danger else colors.ink)
    snapshot.processQueryFailure?.let { Text(it, style = AppType.caption, color = colors.danger) }
    when {
        snapshot.processQueryState != ProcessQueryScanState.PROCESS_QUERY_SUCCESS -> Unit
        snapshot.processes.isEmpty() -> Text("未发现 com.tencent.wework 进程。", style = AppType.caption, color = colors.mut)
        else -> {
            Text("企业微信相关进程：${snapshot.processes.size}", style = AppType.caption, color = colors.ink)
            snapshot.processes.forEach { process ->
                Text("PID ${process.pid} · ${process.processName}", style = AppType.caption, color = colors.ink)
            }
        }
    }
}

@Composable
private fun GlobalSocketEntryLine(
    label: String,
    socket: NamedUnixSocket,
    processes: List<WeWorkProcess>,
    processQuerySucceeded: Boolean
) {
    val colors = LocalAppColors.current
    val suffix = pidStyleSuffix(socket.socketName)
    val pidMatch = socketNameMatchesWeWorkPid(socket.socketName, processes)
    val categories = classifyGlobalSocketName(socket.socketName).joinToString(" · ") { it.label }
    val endpoint = if (socket.isAbstract) "abstract" else "filesystem"
    val suffixLabel = suffix?.toString() ?: "无数字 PID 样式后缀"
    val pidMatchLabel = when {
        pidMatch -> "NAME_PID_MATCH"
        !processQuerySucceeded -> "PID 对照不可用"
        else -> "无 PID 匹配"
    }
    Text(
        "$label · $categories · $endpoint · type=0x${socket.type.toString(16).uppercase().padStart(4, '0')} · state=0x${socket.state.toString(16).uppercase().padStart(2, '0')} · inode=${socket.inode} · PID 后缀=$suffixLabel · $pidMatchLabel · ${socket.socketName}",
        style = AppType.caption,
        color = colors.ink
    )
}

@Composable
private fun WeWorkProcessDeltaSummary(title: String, delta: WeWorkProcessDelta?) {
    val colors = LocalAppColors.current
    SectionLabel(title)
    if (delta == null) {
        Text("PROCESS_QUERY_FAILED · 进程差异不可判定。", style = AppType.caption, color = colors.danger)
    } else {
        Text("新增 ${delta.added.size} · 消失 ${delta.removed.size}", style = AppType.caption, color = colors.ink)
        delta.added.forEach { process ->
            Text("新增 PID ${process.pid} · ${process.processName}", style = AppType.caption, color = colors.ink)
        }
        delta.removed.forEach { process ->
            Text("消失 PID ${process.pid} · ${process.processName}", style = AppType.caption, color = colors.mut)
        }
    }
}
