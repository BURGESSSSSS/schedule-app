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
    SCANNING_BASELINE,
    BASELINE_RECORDED,
    SCANNING_CURRENT,
    COMPARISON_COMPLETE,
    PROC_UNIX_READ_FAILED,
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

internal data class LocalAdbScanDiagnostics(
    val procUnixState: ProcUnixScanState? = null,
    val processQueryState: ProcessQueryScanState? = null,
    val fdScanStates: Map<Int, ProcFdScanState> = emptyMap(),
    val mapsScanStates: Map<Int, ProcMapsScanState> = emptyMap(),
    val activeStep: String? = null,
    val failure: String? = null
)

internal data class LocalAdbUidCheckResult(
    val idState: LocalAdbCheckState,
    val output: String,
    val uidVerified: Boolean,
    val uidStatus: String,
    val errorMessage: String?
)

private const val MAX_ID_OUTPUT_CHARS = 1024
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
    var baselineScan by remember { mutableStateOf<LocalAdbScanSnapshot?>(null) }
    var currentScan by remember { mutableStateOf<LocalAdbScanSnapshot?>(null) }
    var scanDiagnostics by remember { mutableStateOf(LocalAdbScanDiagnostics()) }
    var commandProbe by remember { mutableStateOf(CommandExecutorProbeResult()) }
    var commandProbeResults by remember { mutableStateOf<Map<LocalAdbCommandKind, CommandExecutorProbeResult>>(emptyMap()) }
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
        baselineScan = null
        currentScan = null
        commandProbeResults = emptyMap()
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
        socketScanState = WebViewSocketScanState.NOT_RUN
        baselineScan = null
        currentScan = null
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
                val idProbe = if (readResult.commandCompleted && readResult.issue == LocalAdbReadIssue.NONE) {
                    CommandExecutorProbeResult(CommandExecutorProbeState.SUCCESS, "已收到结束标记")
                } else {
                    CommandExecutorProbeResult(
                        CommandExecutorProbeState.FAILED,
                        readResult.failureSummary ?: "id 未收到结束标记。"
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
                if (idState == ID_RUNNING) idState = ID_FAILED
                lastError = safeFailure(exception)
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
                if (result.issue != LocalAdbReadIssue.NONE || !result.commandCompleted) {
                    recordCommandProbe(command.kind, CommandExecutorProbeResult(
                        CommandExecutorProbeState.FAILED,
                        result.failureSummary ?: "${command.kind.safeLabel} 未收到结束标记；部分输出：${result.partialOutputReceived}"
                    ))
                } else {
                    val detail = summarize(result.output)
                    if (result.exitCode != 0 || detail == null) {
                        recordCommandProbe(command.kind, CommandExecutorProbeResult(
                            CommandExecutorProbeState.FAILED,
                            "${command.kind.safeLabel} 已收到结束标记，但命令或结果校验失败。"
                        ))
                    } else {
                        recordCommandProbe(command.kind, CommandExecutorProbeResult(
                            CommandExecutorProbeState.SUCCESS,
                            "$detail · 已收到结束标记"
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

    fun scanSockets(recordBaseline: Boolean) {
        if (busy) return
        val requiredProbes = listOf(
            LocalAdbCommandKind.ID,
            LocalAdbCommandKind.PRINTF_TEST,
            LocalAdbCommandKind.PROC_UNIX_READ,
            LocalAdbCommandKind.MISSING_PROCESS_QUERY
        )
        val incompleteProbes = requiredProbes.filter {
            commandProbeResults[it]?.state != CommandExecutorProbeState.SUCCESS
        }
        if (incompleteProbes.isNotEmpty()) {
            lastError = "先完成命令结束协议检查：${incompleteProbes.joinToString { it.safeLabel }}。"
            socketScanState = WebViewSocketScanState.SCAN_FAILED
            return
        }
        if (!uidVerified) {
            lastError = "请先运行 id 并验证 UID 2000，再扫描 sockets。"
            socketScanState = WebViewSocketScanState.SCAN_FAILED
            return
        }
        if (!recordBaseline && baselineScan == null) {
            lastError = "请先在企业微信课表页面关闭时记录 A 基线。"
            socketScanState = WebViewSocketScanState.SCAN_FAILED
            return
        }
        val port = connectPort.toIntOrNull()
        if (port == null || port !in 1..65535) {
            lastError = "请输入 Wireless Debugging 主页面显示的有效连接端口。"
            connectionState = ADB_CONNECTION_FAILED
            socketScanState = WebViewSocketScanState.SCAN_FAILED
            return
        }

        lastError = null
        if (recordBaseline) {
            baselineScan = null
            currentScan = null
            socketScanState = WebViewSocketScanState.SCANNING_BASELINE
        } else {
            currentScan = null
            socketScanState = WebViewSocketScanState.SCANNING_CURRENT
        }
        scanDiagnostics = LocalAdbScanDiagnostics(
            procUnixState = ProcUnixScanState.PROC_UNIX_START,
            activeStep = "ADB_CONNECT"
        )
        connectionState = ADB_CONNECTING
        busy = true
        operationScope.launch {
            try {
                val connected = client.connect(port)
                if (!connected) throw IOException("TLS connection was not established")
                connectionState = ADB_CONNECTED
                shellState = SHELL_OPENING
                scanDiagnostics = scanDiagnostics.copy(activeStep = "PROC_UNIX_READ")

                suspend fun executeStep(
                    command: LocalAdbReadOnlyCommand,
                    stepName: String,
                    onFailure: (String) -> Unit
                ): LocalAdbCommandOutput? {
                    val result = try {
                        client.executeReadOnly(command)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (exception: Exception) {
                        val message = safeFailure(exception)
                        onFailure(message)
                        scanDiagnostics = scanDiagnostics.copy(activeStep = null, failure = "$stepName：$message")
                        return null
                    }
                    val problem = when {
                        result.issue != LocalAdbReadIssue.NONE -> result.failureSummary
                            ?: "${command.kind.safeLabel} 未完成；部分输出：${result.partialOutputReceived}"
                        !result.commandCompleted -> "${command.kind.safeLabel} 未收到结束标记；部分输出：${result.partialOutputReceived}"
                        result.exitCode != 0 -> "${command.kind.safeLabel} 退出状态为 ${result.exitCode}"
                        else -> null
                    }
                    if (problem != null) {
                        onFailure(problem)
                        scanDiagnostics = scanDiagnostics.copy(activeStep = null, failure = "$stepName：$problem")
                        return null
                    }
                    return result
                }

                val unixOutput = executeStep(LocalAdbReadOnlyCommand.PROC_UNIX_READ, "PROC_UNIX_READ") { message ->
                    scanDiagnostics = scanDiagnostics.copy(procUnixState = ProcUnixScanState.PROC_UNIX_FAILED)
                    lastError = "PROC_UNIX_READ_FAILED：$message"
                }
                val unixResult = unixOutput?.let { parseProcUnixTable(it.output, it.exitCode) }
                    ?: ProcUnixTableResult(ProcUnixScanState.PROC_UNIX_FAILED, null, emptyMap())
                scanDiagnostics = scanDiagnostics.copy(
                    procUnixState = unixResult.state,
                    processQueryState = ProcessQueryScanState.PROCESS_QUERY_START,
                    activeStep = "PROCESS_QUERY"
                )

                val processOutput = executeStep(LocalAdbReadOnlyCommand.PROCESS_QUERY, "PROCESS_QUERY") { message ->
                    scanDiagnostics = scanDiagnostics.copy(processQueryState = ProcessQueryScanState.PROCESS_QUERY_FAILED)
                    if (lastError == null) lastError = "PROCESS_QUERY_FAILED：$message"
                }
                val processResult = processOutput?.let { parseProcessQuery(it.output, it.exitCode) }
                    ?: ProcessQueryResult(ProcessQueryScanState.PROCESS_QUERY_FAILED, emptyList())
                scanDiagnostics = scanDiagnostics.copy(
                    processQueryState = processResult.state,
                    activeStep = null
                )
                shellState = SHELL_OPENED

                val audits = mutableListOf<WeWorkProcessAudit>()
                for (process in processResult.processes) {
                    scanDiagnostics = scanDiagnostics.copy(
                        fdScanStates = scanDiagnostics.fdScanStates + (process.pid to ProcFdScanState.FD_SCAN_START),
                        activeStep = "FD_SCAN pid=${process.pid}"
                    )
                    var fdFailure: String? = null
                    val fdOutput = executeStep(LocalAdbReadOnlyCommand.fdScan(process.pid), "FD_SCAN") { message ->
                        fdFailure = message
                        scanDiagnostics = scanDiagnostics.copy(
                            fdScanStates = scanDiagnostics.fdScanStates + (process.pid to ProcFdScanState.FD_SCAN_FAILED)
                        )
                    }
                    val fdResult = fdOutput?.let { parseProcFdScan(it.output, process.pid, it.exitCode) }
                        ?: ProcFdScanResult(process.pid, ProcFdScanState.FD_SCAN_FAILED, emptyList())
                    scanDiagnostics = scanDiagnostics.copy(
                        fdScanStates = scanDiagnostics.fdScanStates + (process.pid to fdResult.state),
                        activeStep = null,
                        failure = when {
                            fdFailure != null -> "FD_SCAN pid=${process.pid}：$fdFailure"
                            fdResult.state == ProcFdScanState.FD_SCAN_FAILED -> "FD_SCAN pid=${process.pid}：未收到有效扫描结果标记"
                            else -> scanDiagnostics.failure
                        }
                    )

                    val shouldReadMaps = process.processName == "com.tencent.wework" ||
                        process.processName == "com.tencent.wework:wxa_container0"
                    var mapsResult: ProcMapsScanResult? = null
                    if (shouldReadMaps) {
                        scanDiagnostics = scanDiagnostics.copy(
                            mapsScanStates = scanDiagnostics.mapsScanStates + (process.pid to ProcMapsScanState.MAPS_SCAN_START),
                            activeStep = "MAPS_SCAN pid=${process.pid}"
                        )
                        var mapsFailure: String? = null
                        val mapsOutput = executeStep(LocalAdbReadOnlyCommand.mapsScan(process.pid), "MAPS_SCAN") { message ->
                            mapsFailure = message
                            scanDiagnostics = scanDiagnostics.copy(
                                mapsScanStates = scanDiagnostics.mapsScanStates + (process.pid to ProcMapsScanState.MAPS_SCAN_FAILED)
                            )
                        }
                        val parsedMapsResult = mapsOutput?.let { parseProcMapsScan(it.output, process.pid, it.exitCode) }
                            ?: ProcMapsScanResult(process.pid, ProcMapsScanState.MAPS_SCAN_FAILED, emptyList())
                        mapsResult = parsedMapsResult
                        scanDiagnostics = scanDiagnostics.copy(
                            mapsScanStates = scanDiagnostics.mapsScanStates + (process.pid to parsedMapsResult.state),
                            activeStep = null,
                            failure = when {
                                mapsFailure != null -> "MAPS_SCAN pid=${process.pid}：$mapsFailure"
                                parsedMapsResult.state == ProcMapsScanState.MAPS_SCAN_FAILED -> "MAPS_SCAN pid=${process.pid}：未收到有效扫描结果标记"
                                else -> scanDiagnostics.failure
                            }
                        )
                    }
                    audits += buildProcessAudit(process, fdResult, unixResult.socketNamesByInode, mapsResult)
                }

                val snapshot = LocalAdbScanSnapshot(
                    stats = unixResult.stats,
                    processes = audits,
                    procUnixState = unixResult.state,
                    processQueryState = processResult.state
                )
                if (recordBaseline) {
                    baselineScan = snapshot
                    currentScan = null
                } else {
                    currentScan = snapshot
                }
                if (snapshot.procUnixReadFailed) {
                    socketScanState = WebViewSocketScanState.PROC_UNIX_READ_FAILED
                    if (lastError == null) lastError = "PROC_UNIX_READ_FAILED：无法读取 /proc/net/unix，暂不判断进程 socket 归属。"
                    return@launch
                }
                if (snapshot.processQueryFailed) {
                    socketScanState = WebViewSocketScanState.SCAN_FAILED
                    if (lastError == null) lastError = "PROCESS_QUERY_FAILED：保留了本次 /proc/net/unix 统计供诊断。"
                    return@launch
                }

                if (recordBaseline) {
                    socketScanState = WebViewSocketScanState.BASELINE_RECORDED
                } else {
                    socketScanState = WebViewSocketScanState.COMPARISON_COMPLETE
                }
                if (scanDiagnostics.failure != null && lastError == null) {
                    lastError = scanDiagnostics.failure
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                if (connectionState == ADB_CONNECTING) connectionState = ADB_CONNECTION_FAILED
                if (shellState == SHELL_OPENING) shellState = SHELL_FAILED
                socketScanState = WebViewSocketScanState.SCAN_FAILED
                val stage = scanDiagnostics.activeStep ?: "ADB_CONNECT"
                val error = safeFailure(exception)
                scanDiagnostics = scanDiagnostics.copy(activeStep = null, failure = "$stage：$error")
                lastError = "$stage：$error"
            } finally {
                busy = false
            }
        }
    }

    SubPageScaffold("Local ADB / 企业微信 Socket 归属", onBack) {
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
                            if (output.trim() == "test") "printf test 输出正确" else null
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

        SectionLabel("步骤 3 · 企业微信进程 Unix socket 只读归属")
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
                Text(
                    "按 PID 只读枚举 com.tencent.wework 进程的 fd，将 socket inode 对照 /proc/net/unix。主进程和 wxa_container0 仅筛选 maps 中指定内核库的 basename。不会连接 socket。",
                    style = AppType.body,
                    color = colors.ink
                )
                PillButton(
                    if (busy && socketScanState == WebViewSocketScanState.SCANNING_BASELINE) "记录 A 中…" else "记录 A 基线（课表关闭）",
                    onClick = { scanSockets(recordBaseline = true) },
                    style = PillStyle.Primary
                )
                PillButton(
                    if (busy && socketScanState == WebViewSocketScanState.SCANNING_CURRENT) "扫描 B 中…" else "扫描 B 当前状态（课表保持打开）",
                    onClick = { scanSockets(recordBaseline = false) },
                    style = PillStyle.Secondary
                )
                Text("扫描状态：${socketScanState.name}", style = AppType.caption, color = colors.mut)
                Text("PROC_UNIX：${scanDiagnostics.procUnixState?.name ?: "NOT_RUN"}", style = AppType.caption, color = colors.ink)
                Text("PROCESS_QUERY：${scanDiagnostics.processQueryState?.name ?: "NOT_RUN"}", style = AppType.caption, color = colors.ink)
                scanDiagnostics.fdScanStates.toSortedMap().forEach { (pid, state) ->
                    Text("FD_SCAN pid=$pid：${state.name}", style = AppType.caption, color = if (state == ProcFdScanState.FD_SCAN_PERMISSION_DENIED || state == ProcFdScanState.FD_SCAN_FAILED) colors.danger else colors.ink)
                }
                scanDiagnostics.mapsScanStates.toSortedMap().forEach { (pid, state) ->
                    Text("MAPS_SCAN pid=$pid：${state.name}", style = AppType.caption, color = if (state == ProcMapsScanState.MAPS_SCAN_PERMISSION_DENIED || state == ProcMapsScanState.MAPS_SCAN_FAILED) colors.danger else colors.ink)
                }
                scanDiagnostics.activeStep?.let { Text("当前步骤：$it", style = AppType.caption, color = colors.mut) }
                scanDiagnostics.failure?.let { Text("步骤诊断：$it", style = AppType.caption, color = colors.danger) }
                baselineScan?.let { snapshot ->
                    LocalAdbScanSnapshotCard("A 基线", snapshot)
                }
                currentScan?.let { snapshot ->
                    LocalAdbScanSnapshotCard("B 当前状态", snapshot, baselineScan)
                    Text(
                        "归属判断：${classifySocketOwnership(snapshot, baselineScan).name}",
                        style = AppType.caption,
                        color = colors.ink
                    )
                }
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
private fun LocalAdbScanSnapshotCard(
    title: String,
    snapshot: LocalAdbScanSnapshot,
    baseline: LocalAdbScanSnapshot? = null
) {
    val colors = LocalAppColors.current
    SectionLabel(title)
    if (snapshot.procUnixReadFailed) {
        Text("PROC_UNIX_READ_FAILED：/proc/net/unix 统计或 inode 映射读取失败。", style = AppType.caption, color = colors.danger)
    }
    snapshot.stats?.let { stats ->
        Text(
            "/proc/net/unix：总行数 ${stats.totalLines} · 有名称 ${stats.namedSockets} · abstract ${stats.abstractSockets}",
            style = AppType.caption,
            color = colors.mut
        )
    }
    if (snapshot.processQueryFailed) {
        Text("企业微信进程查询失败。", style = AppType.caption, color = colors.danger)
    } else if (snapshot.processes.isEmpty()) {
        Text("未发现 com.tencent.wework 进程。", style = AppType.caption, color = colors.mut)
    } else {
        Text("企业微信进程：${snapshot.processes.size}", style = AppType.caption, color = colors.ink)
        val orderedProcesses = snapshot.processes.sortedBy {
            if (it.process.processName == "com.tencent.wework:wxa_container0") 0 else 1
        }
        orderedProcesses.forEach { audit ->
            val process = audit.process
            SectionLabel(
                if (process.processName == "com.tencent.wework:wxa_container0") {
                    "重点候选进程 · ${process.processName}"
                } else {
                    process.processName
                }
            )
            Text("PID ${process.pid} · fd 状态：${audit.fdScanState}", style = AppType.caption, color = if (audit.fdScanState == ProcFdScanState.FD_SCAN_PERMISSION_DENIED || audit.fdScanState == ProcFdScanState.FD_SCAN_FAILED) colors.danger else colors.ink)
            Text("持有 Unix sockets：${audit.sockets.size}", style = AppType.caption, color = colors.ink)
            if (audit.sockets.isEmpty()) {
                Text("没有发现 socket fd。", style = AppType.caption, color = colors.mut)
            } else {
                audit.sockets.forEach { socket ->
                    val name = when {
                        !socket.presentInProcUnix -> "未在 /proc/net/unix 找到 inode"
                        socket.socketName == null -> "无路径名"
                        else -> socket.socketName
                    }
                    Text("inode ${socket.inode} · $name", style = AppType.caption, color = colors.ink)
                }
            }

            Text("maps：${audit.mapsScanState}", style = AppType.caption, color = if (audit.mapsScanState == ProcMapsScanState.MAPS_SCAN_PERMISSION_DENIED || audit.mapsScanState == ProcMapsScanState.MAPS_SCAN_FAILED) colors.danger else colors.ink)
            if (audit.libraries.isNotEmpty()) {
                audit.libraries.forEach { library ->
                    Text("${library.basename} · ${library.keywords.joinToString()}", style = AppType.caption, color = colors.ink)
                }
                Text(
                    "内核线索：${identifyWebViewKernel(audit.libraries).displayName}",
                    style = AppType.caption,
                    color = colors.mut
                )
            } else if (audit.mapsScanState == ProcMapsScanState.MAPS_SCAN_SUCCESS) {
                Text("未筛选到指定内核库名。", style = AppType.caption, color = colors.mut)
            }
        }
    }

    if (baseline != null) {
        val newProcesses = newlyAppearedProcesses(baseline.processes, snapshot.processes)
        val newSockets = newlyOwnedSockets(baseline, snapshot)
        val newInodes = newlyObservedSocketInodes(baseline, snapshot)
        val newNamedSocketCount = newSockets.count { it.socket.presentInProcUnix && it.socket.socketName != null }
        val newWxaSockets = newSockets.filter { it.process.processName == "com.tencent.wework:wxa_container0" }
        SectionLabel("A/B 差异")
        Text("新增进程：${newProcesses.size}", style = AppType.caption, color = colors.ink)
        newProcesses.forEach { process ->
            Text("PID ${process.pid} · ${process.processName}", style = AppType.caption, color = colors.ink)
        }
        Text("新增 socket inode：${newInodes.size} · 新增具名 socket：$newNamedSocketCount", style = AppType.caption, color = colors.ink)
        Text("wxa_container0 新增 socket：${newWxaSockets.size}", style = AppType.caption, color = colors.ink)
        newSockets.forEach { reference ->
            val name = if (reference.socket.presentInProcUnix) {
                reference.socket.socketName ?: "无路径名"
            } else {
                "未在 /proc/net/unix 找到 inode"
            }
            Text(
                "${reference.process.processName} · PID ${reference.process.pid} · inode ${reference.socket.inode} · $name",
                style = AppType.caption,
                color = colors.ink
            )
        }
    }
}
