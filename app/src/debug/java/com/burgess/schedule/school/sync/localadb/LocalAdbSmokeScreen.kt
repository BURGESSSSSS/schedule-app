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
import com.burgess.schedule.school.sync.localadb.LocalAdbReadOnlyCommand.ID
import com.burgess.schedule.school.sync.localadb.LocalAdbReadOnlyCommand.DEVTOOLS_SOCKET_SCAN
import com.burgess.schedule.ui.components.AppCard
import com.burgess.schedule.ui.components.PillButton
import com.burgess.schedule.ui.components.PillStyle
import com.burgess.schedule.ui.components.SectionLabel
import com.burgess.schedule.ui.components.SubPageScaffold
import com.burgess.schedule.ui.theme.AppSpace
import com.burgess.schedule.ui.theme.AppType
import com.burgess.schedule.ui.theme.LocalAppColors
import java.io.IOException
import java.util.Locale
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

internal enum class LocalAdbReadIssue {
    NONE,
    STREAM_CLOSED,
    END_OF_STREAM,
    TIMEOUT,
    IO_ERROR
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
    var lastError by remember { mutableStateOf<String?>(null) }

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
                val readResult = client.executeReadOnly(ID)
                shellState = SHELL_OPENED
                val result = evaluateLocalAdbUidCheck(
                    readResult.output,
                    readResult.issue,
                    readResult.failureSummary
                )
                idOutput = result.output
                idState = result.idState
                uidState = result.uidStatus
                uidVerified = result.uidVerified
                lastError = result.errorMessage
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

    fun scanSockets(recordBaseline: Boolean) {
        if (busy) return
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
        connectionState = ADB_CONNECTING
        busy = true
        operationScope.launch {
            try {
                val connected = client.connect(port)
                if (!connected) throw IOException("TLS connection was not established")
                connectionState = ADB_CONNECTED

                shellState = SHELL_OPENING
                val scanOutput = client.executeReadOnly(DEVTOOLS_SOCKET_SCAN)
                if (scanOutput.issue != LocalAdbReadIssue.NONE) {
                    throw IOException(scanOutput.failureSummary ?: "Read-only socket scan did not complete.")
                }
                shellState = SHELL_OPENED
                val result = parseLocalAdbScanSnapshot(scanOutput.output)
                if (result.procUnixReadFailed) {
                    socketScanState = WebViewSocketScanState.PROC_UNIX_READ_FAILED
                    lastError = "PROC_UNIX_READ_FAILED：无法读取 /proc/net/unix，暂不判断 DevTools sockets。"
                    return@launch
                }

                if (recordBaseline) {
                    baselineScan = result
                    socketScanState = WebViewSocketScanState.BASELINE_RECORDED
                } else {
                    currentScan = result
                    socketScanState = WebViewSocketScanState.COMPARISON_COMPLETE
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                if (connectionState == ADB_CONNECTING) connectionState = ADB_CONNECTION_FAILED
                if (shellState == SHELL_OPENING) shellState = SHELL_FAILED
                socketScanState = WebViewSocketScanState.SCAN_FAILED
                lastError = safeFailure(exception)
            } finally {
                busy = false
            }
        }
    }

    SubPageScaffold("Local ADB / WebView sockets", onBack) {
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

        SectionLabel("步骤 3 · WebView / XWeb socket 只读发现")
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
                Text(
                    "只读取 /proc/net/unix 的统计值和名称含 devtools、xweb、webview、tbs 的 socket；进程查询只保留 com.tencent.wework。不会连接 socket。",
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
                baselineScan?.let { snapshot ->
                    LocalAdbScanSnapshotCard("A 基线", snapshot)
                }
                currentScan?.let { snapshot ->
                    LocalAdbScanSnapshotCard("B 当前状态", snapshot, baselineScan)
                    Text(
                        "发现判断：${classifyDevtoolsDiscovery(snapshot, baselineScan)}",
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
    snapshot.stats?.let { stats ->
        Text(
            "/proc/net/unix：总行数 ${stats.totalLines} · 有名称 ${stats.namedSockets} · abstract ${stats.abstractSockets}",
            style = AppType.caption,
            color = colors.mut
        )
    }
    if (snapshot.processQueryFailed) {
        Text("企业微信进程查询失败。", style = AppType.caption, color = colors.danger)
    } else if (snapshot.weWorkProcesses.isEmpty()) {
        Text("未发现 com.tencent.wework 进程。", style = AppType.caption, color = colors.mut)
    } else {
        Text("企业微信进程：${snapshot.weWorkProcesses.size}", style = AppType.caption, color = colors.ink)
        snapshot.weWorkProcesses.forEach { process ->
            Text("PID ${process.pid} · ${process.processName}", style = AppType.caption, color = colors.ink)
        }
    }

    Text("候选 sockets：${snapshot.sockets.size}", style = AppType.caption, color = colors.ink)
    if (snapshot.sockets.isEmpty()) {
        Text("没有匹配到相关 socket 名称。", style = AppType.caption, color = colors.mut)
    } else {
        snapshot.sockets.forEach { socket ->
            Text("${socket.name} · ${socket.kind}", style = AppType.caption, color = colors.ink)
        }
    }

    if (snapshot.pidMatches.isNotEmpty()) {
        SectionLabel("WXWORK_SOCKET_PID_MATCH")
        snapshot.pidMatches.forEach { match ->
            Text(
                "${match.socketName} ↔ PID ${match.process.pid} · ${match.process.processName}",
                style = AppType.caption,
                color = colors.ink
            )
        }
    }

    if (baseline != null) {
        val newProcesses = newlyAppearedWeWorkProcesses(baseline.weWorkProcesses, snapshot.weWorkProcesses)
        val newSockets = newlyAppearedSockets(baseline.sockets, snapshot.sockets)
        Text("A/B 新进程：${newProcesses.size}", style = AppType.caption, color = colors.ink)
        newProcesses.forEach { process ->
            Text("PID ${process.pid} · ${process.processName}", style = AppType.caption, color = colors.ink)
        }
        Text("A/B 新 sockets：${newSockets.size}", style = AppType.caption, color = colors.ink)
        newSockets.forEach { socket ->
            Text("${socket.name} · ${socket.kind}", style = AppType.caption, color = colors.ink)
        }
    }
}

private fun classifyDevtoolsDiscovery(
    current: LocalAdbScanSnapshot,
    baseline: LocalAdbScanSnapshot?
): String {
    if (current.procUnixReadFailed) return "PROC_UNIX_READ_FAILED"
    if (current.processQueryFailed) return "企业微信进程查询失败"
    if (current.weWorkProcesses.isEmpty()) return "企业微信进程未发现，暂不能分类"

    val pidMatchedNames = current.pidMatches.mapTo(hashSetOf()) { it.socketName.lowercase(Locale.ROOT) }
    val newSocketNames = baseline?.let {
        newlyAppearedSockets(it.sockets, current.sockets).mapTo(hashSetOf()) { socket ->
            socket.name.lowercase(Locale.ROOT)
        }
    }.orEmpty()
    fun associated(kind: DevtoolsSocketKind): Boolean = current.sockets.any { socket ->
        val normalizedName = socket.name.lowercase(Locale.ROOT)
        socket.kind == kind && (normalizedName in pidMatchedNames || normalizedName in newSocketNames)
    }

    return when {
        associated(DevtoolsSocketKind.XWEB) -> "XWEB_SOCKET_FOUND"
        associated(DevtoolsSocketKind.WEBVIEW) -> "WEBVIEW_SOCKET_FOUND"
        current.sockets.any { it.kind == DevtoolsSocketKind.XWEB } -> "发现 XWEB 候选，归属未确认"
        current.sockets.any { it.kind == DevtoolsSocketKind.WEBVIEW } -> "发现 WebView 候选，归属未确认"
        current.sockets.isNotEmpty() -> "OTHER_DEVTOOLS_SOCKET_FOUND"
        else -> "NO_DEVTOOLS_SOCKET"
    }
}
