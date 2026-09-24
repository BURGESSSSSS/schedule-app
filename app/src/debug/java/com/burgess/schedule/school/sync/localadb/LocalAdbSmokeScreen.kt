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
import com.burgess.schedule.school.sync.localadb.LocalAdbReadOnlyCommand.WEBVIEW_SOCKET_SCAN
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

internal enum class LocalAdbReadIssue {
    NONE,
    STREAM_CLOSED,
    END_OF_STREAM,
    TIMEOUT,
    IO_ERROR
}

internal enum class WebViewSocketScanState {
    NOT_RUN,
    SCANNING,
    SCAN_SUCCESS,
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
    var webViewSocketNames by remember { mutableStateOf(emptyList<String>()) }
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
        webViewSocketNames = emptyList()
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
        webViewSocketNames = emptyList()
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

    fun scanWebViewSockets() {
        if (busy) return
        if (!uidVerified) {
            lastError = "请先运行 id 并验证 UID 2000，再扫描 WebView sockets。"
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
        webViewSocketNames = emptyList()
        socketScanState = WebViewSocketScanState.SCANNING
        connectionState = ADB_CONNECTING
        busy = true
        operationScope.launch {
            try {
                val connected = client.connect(port)
                if (!connected) throw IOException("TLS connection was not established")
                connectionState = ADB_CONNECTED

                shellState = SHELL_OPENING
                val scanOutput = client.executeReadOnly(WEBVIEW_SOCKET_SCAN)
                if (scanOutput.issue != LocalAdbReadIssue.NONE) {
                    throw IOException(scanOutput.failureSummary ?: "WebView socket scan did not complete.")
                }
                val exitCode = scanOutput.exitCode
                if (exitCode == null || exitCode !in 0..1) {
                    throw IOException("WebView socket scan failed (exit code ${exitCode ?: "unknown"}).")
                }

                shellState = SHELL_OPENED
                webViewSocketNames = parseWebViewSocketNames(scanOutput.output)
                socketScanState = WebViewSocketScanState.SCAN_SUCCESS
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

        SectionLabel("步骤 3 · 只读扫描 WebView sockets")
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
                Text(
                    "只筛选 /proc/net/unix 中名称含 webview_devtools_remote 的 socket。不会连接 socket 或读取页面内容。",
                    style = AppType.body,
                    color = colors.ink
                )
                PillButton(
                    if (busy && socketScanState == WebViewSocketScanState.SCANNING) "扫描中…" else "扫描 WebView sockets",
                    onClick = ::scanWebViewSockets,
                    style = PillStyle.Primary
                )
                Text("扫描状态：${socketScanState.name}", style = AppType.caption, color = colors.mut)
                if (socketScanState == WebViewSocketScanState.SCAN_SUCCESS) {
                    Text("WebView sockets：发现 ${webViewSocketNames.size} 个", style = AppType.caption, color = colors.ink)
                    if (webViewSocketNames.isEmpty()) {
                        Text("没有发现匹配的 socket。", style = AppType.caption, color = colors.mut)
                    } else {
                        webViewSocketNames.forEach { socketName ->
                            Text(socketName, style = AppType.caption, color = colors.ink)
                        }
                    }
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
