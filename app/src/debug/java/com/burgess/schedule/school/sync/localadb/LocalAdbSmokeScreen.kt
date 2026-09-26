package com.burgess.schedule.school.sync.localadb

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.burgess.schedule.school.sync.importpreview.SchoolCourseRule
import com.burgess.schedule.school.sync.importpreview.SchoolImportBlockReason
import com.burgess.schedule.school.sync.importpreview.SchoolImportExecutionResult
import com.burgess.schedule.school.sync.importpreview.SchoolImportMapper
import com.burgess.schedule.school.sync.importpreview.SchoolImportPlan
import com.burgess.schedule.school.sync.importpreview.SchoolImportPreview
import com.burgess.schedule.school.sync.importpreview.SchoolImportPreviewSummary
import com.burgess.schedule.school.sync.importpreview.SchoolImportRepository
import com.burgess.schedule.school.sync.importpreview.SchoolParseWarning
import com.burgess.schedule.school.sync.importpreview.MergedSemesterSchedule
import com.burgess.schedule.school.sync.importpreview.SemesterCollectionSession
import com.burgess.schedule.school.sync.importpreview.SemesterCollectionState
import com.burgess.schedule.school.sync.importpreview.SemesterCollectionStore
import com.burgess.schedule.school.sync.importpreview.SemesterCollectionWarning
import com.burgess.schedule.school.sync.importpreview.SemesterRuleCoverageWarning
import com.burgess.schedule.school.sync.importpreview.SemesterSessionLoadResult
import com.burgess.schedule.school.sync.importpreview.SemesterSnapshotCaptureResult
import com.burgess.schedule.data.CourseDatabase
import com.burgess.schedule.school.sync.importpreview.formatWeekSet
import com.burgess.schedule.school.sync.importpreview.mergeSemesterSession
import com.burgess.schedule.school.sync.importpreview.recordSnapshot
import io.github.muntashirakon.adb.AdbStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

private const val MAX_TARGETS_TO_DISPLAY = 32

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
    val semesterStore = remember(context) { SemesterCollectionStore(context) }
    val schoolImportRepository = remember(context) {
        SchoolImportRepository(CourseDatabase.getInstance(context))
    }
    val operationScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
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
    var commandProbe by remember { mutableStateOf(CommandExecutorProbeResult()) }
    var commandProbeResults by remember { mutableStateOf<Map<LocalAdbCommandDescriptor, CommandExecutorProbeResult>>(emptyMap()) }
    var devToolsStateHistory by remember { mutableStateOf<List<DevToolsDiscoveryState>>(emptyList()) }
    var devToolsResult by remember { mutableStateOf<DevToolsTargetDiscoveryResult?>(null) }
    var devToolsMessage by remember { mutableStateOf<String?>(null) }
    var devToolsProcessQueryStatus by remember { mutableStateOf("未查询") }
    var cdpWebSocketState by remember { mutableStateOf("NOT_RUN") }
    var cdpCommandState by remember { mutableStateOf("NOT_RUN") }
    var cdpHandshakeDiagnostic by remember { mutableStateOf<DevToolsHandshakeDiagnostic?>(null) }
    var cdpHttpTargetUrl by remember { mutableStateOf<String?>(null) }
    var cdpMainFrameUrl by remember { mutableStateOf<SanitizedTargetUrl?>(null) }
    var cdpUrlComparison by remember { mutableStateOf<String?>(null) }
    var cdpFailureMessage by remember { mutableStateOf<String?>(null) }
    var cdpFinalState by remember { mutableStateOf<String?>(null) }
    var appInForeground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var networkListenerJob by remember { mutableStateOf<Job?>(null) }
    var networkSessionState by remember { mutableStateOf("NOT_RUN") }
    var networkState by remember { mutableStateOf("NOT_RUN") }
    var networkListenerState by remember { mutableStateOf("IDLE") }
    var networkSchoolRequestCount by remember { mutableStateOf(0) }
    var networkRequests by remember { mutableStateOf<List<CourseRequestSummary>>(emptyList()) }
    var schoolImportPreview by remember { mutableStateOf<SchoolImportPreview?>(null) }
    var previewRequestNumber by remember { mutableStateOf<Int?>(null) }
    var networkMainFrameUrl by remember { mutableStateOf<SanitizedTargetUrl?>(null) }
    var networkFailureDetail by remember { mutableStateOf<String?>(null) }
    var networkFinalState by remember { mutableStateOf<String?>(null) }
    var semesterSession by remember { mutableStateOf<SemesterCollectionSession?>(null) }
    var semesterSessionStoreReady by remember { mutableStateOf(false) }
    var semesterSessionStoreError by remember { mutableStateOf<String?>(null) }
    var semesterCollectionJob by remember { mutableStateOf<Job?>(null) }
    var semesterCaptureProcessorJob by remember { mutableStateOf<Job?>(null) }
    var semesterCaptureChannel by remember { mutableStateOf<Channel<CourseCaptureSnapshot>?>(null) }
    var semesterListenerState by remember { mutableStateOf("IDLE") }
    var semesterListenerError by remember { mutableStateOf<String?>(null) }
    var semesterPreview by remember { mutableStateOf<MergedSemesterSchedule?>(null) }
    var schoolImportPlan by remember { mutableStateOf<SchoolImportPlan?>(null) }
    var schoolImportPreparing by remember { mutableStateOf(false) }
    var schoolImportWriting by remember { mutableStateOf(false) }
    var schoolImportConfirmVisible by remember { mutableStateOf(false) }
    var schoolImportResult by remember { mutableStateOf<SchoolImportExecutionResult?>(null) }
    val semesterStoreMutex = remember(semesterStore) { Mutex() }
    var probePort by remember { mutableStateOf<Int?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(semesterStore) {
        when (val loaded = semesterStore.load()) {
            SemesterSessionLoadResult.Empty -> Unit
            is SemesterSessionLoadResult.Invalid -> {
                semesterSessionStoreError = "临时会话无法读取（${loaded.errorType}）；没有自动删除文件。"
            }
            is SemesterSessionLoadResult.Loaded -> {
                semesterSession = loaded.session
                if (loaded.session.sessionState == SemesterCollectionState.CDP_SESSION_LOST) {
                    semesterListenerState = "CDP_SESSION_LOST"
                }
            }
        }
        semesterSessionStoreReady = true
    }

    suspend fun persistSemesterSession(session: SemesterCollectionSession) {
        semesterStoreMutex.withLock {
            withContext(Dispatchers.IO) { semesterStore.save(session) }
        }
    }

    suspend fun persistSemesterSessionSafely(session: SemesterCollectionSession) {
        try {
            persistSemesterSession(session)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            semesterSessionStoreError = exception.javaClass.simpleName.ifBlank { "IOException" }
        }
    }

    fun prepareSchoolImport(schedule: MergedSemesterSchedule) {
        if (schoolImportPreparing || schoolImportWriting) return
        schoolImportPreparing = true
        schoolImportPlan = null
        schoolImportResult = null
        operationScope.launch {
            try {
                schoolImportPlan = schoolImportRepository.prepare(SchoolImportMapper.prepare(schedule))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                schoolImportResult = SchoolImportExecutionResult.Failed(
                    exception.javaClass.simpleName.ifBlank { "ImportPlanException" }
                )
            } finally {
                schoolImportPreparing = false
            }
        }
    }

    fun confirmSchoolImport() {
        val plan = schoolImportPlan ?: return
        if (!plan.canConfirm || schoolImportWriting) return
        schoolImportConfirmVisible = false
        schoolImportWriting = true
        operationScope.launch {
            try {
                schoolImportResult = schoolImportRepository.import(plan)
            } finally {
                schoolImportWriting = false
            }
        }
    }

    fun recordCommandProbe(kind: LocalAdbCommandDescriptor, result: CommandExecutorProbeResult) {
        commandProbe = result
        commandProbeResults = commandProbeResults + (kind to result)
    }

    fun clearCdpProbeResult() {
        cdpWebSocketState = "NOT_RUN"
        cdpCommandState = "NOT_RUN"
        cdpHandshakeDiagnostic = null
        cdpHttpTargetUrl = null
        cdpMainFrameUrl = null
        cdpUrlComparison = null
        cdpFailureMessage = null
        cdpFinalState = null
    }

    DisposableEffect(client) {
        onDispose {
            networkListenerJob?.cancel()
            semesterCollectionJob?.cancel()
            semesterCaptureChannel?.close()
            val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            cleanupScope.launch {
                try {
                    semesterCollectionJob?.join()
                    semesterCaptureProcessorJob?.join()
                    client.close()
                } catch (_: Exception) {
                    // The diagnostics screen owns no connection after it is closed.
                } finally {
                    cleanupScope.cancel()
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> appInForeground = true
                Lifecycle.Event.ON_STOP -> appInForeground = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
        commandProbeResults = emptyMap()
        devToolsStateHistory = emptyList()
        devToolsResult = null
        devToolsMessage = null
        devToolsProcessQueryStatus = "未查询"
        clearCdpProbeResult()
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
        devToolsStateHistory = emptyList()
        devToolsResult = null
        devToolsMessage = null
        devToolsProcessQueryStatus = "未查询"
        clearCdpProbeResult()
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
        clearCdpProbeResult()
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
                    parseNamedUnixSockets(unixOutput.output, unixOutput.exitCode)
                } else {
                    NamedUnixSocketReadResult(successful = false, sockets = emptyList())
                }
                if (!unixResult.successful) {
                    recordState(DevToolsDiscoveryState.DEVTOOLS_SOCKET_SCAN_FAILED)
                    shellState = SHELL_OPENED
                    devToolsMessage = unixOutput.failureSummary
                        ?: "读取 /proc/net/unix 失败；没有扫描任何 socket。"
                    return@launch
                }

                val candidates = findDevToolsSocketCandidates(unixResult.sockets)
                recordState(DevToolsDiscoveryState.DEVTOOLS_SOCKET_SCAN_SUCCESS)

                val processOutput = client.executeReadOnly(LocalAdbReadOnlyCommand.PROCESS_QUERY)
                val processes = if (processOutput.issue == LocalAdbReadIssue.NONE &&
                    processOutput.commandCompleted && processOutput.exitCode == 0
                ) {
                    parseWeWorkProcessList(processOutput.output, processOutput.exitCode)
                } else null
                devToolsProcessQueryStatus = if (processes != null) {
                    "PROCESS_QUERY_SUCCESS · ${processes.size} 个企业微信进程"
                } else {
                    "PROCESS_QUERY_FAILED · socket PID 无法关联进程"
                }
                shellState = SHELL_OPENED

                devToolsResult = discoverDevToolsTargets(
                    client = client,
                    candidates = candidates,
                    processes = processes.orEmpty(),
                    onState = ::recordState
                )
                devToolsMessage = when (devToolsResult?.finalState) {
                    DevToolsDiscoveryState.SCHOOL_TARGET_FOUND -> "已发现山东商务职业学院教务系统 WebView。"
                    DevToolsDiscoveryState.SCHOOL_HOST_TARGET_FOUND -> "发现学校域名页面，但标题不是“我的课表”。"
                    DevToolsDiscoveryState.SCHOOL_TARGET_CANDIDATE_URL_UNKNOWN ->
                        "找到企业微信“我的课表”页面候选，但 URL 尚未确认。"
                    DevToolsDiscoveryState.HTTP_TARGET_URL_MISSING ->
                        "两个 HTTP discovery endpoint 都没有提供可用 URL；CDP 状态：${devToolsResult?.cdpFailure?.name ?: "未执行"}。"
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

    fun verifyCdpMinimalConnection() {
        if (busy) return
        val port = connectPort.toIntOrNull()
        if (port == null || port !in 1..65535) {
            cdpFailureMessage = "请输入 Wireless Debugging 主页面显示的有效连接端口。"
            cdpWebSocketState = "FAILED"
            return
        }
        if (!uidVerified || idState != ID_EXECUTED || probePort != port) {
            cdpFailureMessage = "请先使用当前连接端口运行 id，并确认 UID 2000。"
            cdpWebSocketState = "FAILED"
            return
        }

        val discovery = devToolsResult
        val endpoint = discovery?.schoolCdpEndpoint
        val target = endpoint?.let { selected ->
            discovery.targets.firstOrNull {
                it.id == selected.targetId &&
                    it.socketName == selected.socketName &&
                    it.title == SCHOOL_TITLE &&
                    it.type.equals("page", ignoreCase = true) &&
                    it.containerName == WEWORK_PACKAGE &&
                    it.schoolMatch == SchoolTargetMatch.SCHOOL_TARGET_FOUND
            }
        }
        if (discovery?.finalState != DevToolsDiscoveryState.SCHOOL_TARGET_FOUND || endpoint == null || target == null) {
            cdpFailureMessage = "请先确认已找到“我的课表”；本按钮只连接该课表页面。"
            cdpWebSocketState = "FAILED"
            return
        }

        clearCdpProbeResult()
        cdpHttpTargetUrl = target.displayUrl
        cdpWebSocketState = "CONNECTING"
        cdpCommandState = "CDP_CONNECT_START"
        busy = true
        operationScope.launch {
            var openedStream: AdbStream? = null
            var cdpClient: DevToolsCdpClient? = null
            try {
                connectionState = ADB_CONNECTING
                if (!client.connect(port)) throw IOException("TLS connection was not established")
                connectionState = ADB_CONNECTED
                cdpCommandState = "CDP_CONNECT_START"
                openedStream = client.openDevToolsSocket(endpoint.socketName)

                when (val connected = DevToolsCdpClient.connect(openedStream, endpoint.rawWebSocketUrl)) {
                    is DevToolsCdpConnectResult.Failed -> {
                        cdpWebSocketState = "FAILED"
                        cdpCommandState = connected.failure.name
                        cdpHandshakeDiagnostic = connected.handshakeDiagnostic
                        cdpFailureMessage = connected.handshakeDiagnostic?.classificationLabel
                            ?: connected.failure.name
                    }
                    is DevToolsCdpConnectResult.Connected -> {
                        cdpClient = connected.client
                        cdpHandshakeDiagnostic = connected.handshakeDiagnostic
                        cdpWebSocketState = "CONNECTED"
                        cdpCommandState = "CDP_CONNECTED"
                        cdpCommandState = "GET_FRAME_TREE_START"
                        when (val frameTree = requestMainFrameUrl(connected.client)) {
                            is CdpFrameTreeProbeResult.Success -> {
                                cdpCommandState = "GET_FRAME_TREE_SUCCESS"
                                cdpMainFrameUrl = frameTree.mainFrameUrl
                                cdpUrlComparison = compareHttpAndCdpTargetUrls(
                                    target.displayUrl,
                                    frameTree.mainFrameUrl
                                )
                                if (cdpUrlComparison == "MISMATCH") {
                                    cdpFinalState = "【需要总控判断】HTTP 与 CDP URL 不一致，已停止。"
                                } else if (isSchoolMainFrameUrl(frameTree.mainFrameUrl)) {
                                    cdpCommandState = "SCHOOL_FRAME_CONFIRMED"
                                    cdpFinalState = "CDP_READ_ONLY_PROBE_SUCCESS"
                                } else {
                                    cdpFinalState = "主 frame URL 未匹配学校课表页面，已停止。"
                                }
                            }
                            is CdpFrameTreeProbeResult.CommandFailed -> {
                                cdpCommandState = "CDP_COMMAND_ERROR"
                                cdpFailureMessage = buildString {
                                    append(frameTree.failure.name)
                                    frameTree.protocolCode?.let { append(" · code=$it") }
                                    frameTree.protocolMessage?.let { append(" · ").append(it) }
                                }
                            }
                            CdpFrameTreeProbeResult.MainFrameUrlMissing -> {
                                cdpCommandState = "GET_FRAME_TREE_SUCCESS"
                                cdpFailureMessage = "主 frame URL 缺失；未发送其他 CDP 命令。"
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                cdpWebSocketState = "FAILED"
                cdpCommandState = "CDP_CONNECT_FAILED"
                cdpFailureMessage = exception.javaClass.simpleName.ifBlank { "Exception" }
            } finally {
                runCatching { cdpClient?.close() }
                if (cdpClient == null) runCatching { openedStream?.close() }
                busy = false
            }
        }
    }

    fun stopCourseNetworkListener() {
        val job = networkListenerJob ?: return
        networkListenerState = "STOPPING"
        job.cancel()
    }

    fun stopSemesterCollection() {
        val job = semesterCollectionJob ?: return
        semesterListenerState = "STOPPING"
        semesterSession = semesterSession?.withState(SemesterCollectionState.SEMESTER_COLLECTION_STOPPED)
        job.cancel()
        operationScope.launch {
            job.join()
            semesterCaptureProcessorJob?.join()
            semesterSession?.let { session -> persistSemesterSessionSafely(session) }
            semesterListenerState = if (semesterSession?.coverageComplete == true) {
                "SEMESTER_COVERAGE_COMPLETE"
            } else {
                "SEMESTER_COLLECTION_STOPPED"
            }
        }
    }

    fun startSemesterCollection() {
        if (!semesterSessionStoreReady) {
            semesterListenerError = "临时会话状态仍在读取，请稍后再试。"
            return
        }
        if (semesterCollectionJob != null || networkListenerJob != null || busy) {
            semesterListenerError = "请先停止当前监听或操作。"
            return
        }
        val port = connectPort.toIntOrNull()
        if (port == null || port !in 1..65535) {
            semesterListenerError = "请输入 Wireless Debugging 主页面显示的有效连接端口。"
            semesterListenerState = "FAILED"
            return
        }
        if (!uidVerified || idState != ID_EXECUTED || probePort != port) {
            semesterListenerError = "请先用当前连接端口运行 id，并确认 UID 2000。"
            semesterListenerState = "FAILED"
            return
        }

        val discovery = devToolsResult
        val endpoint = discovery?.schoolCdpEndpoint
        val target = endpoint?.let { selected ->
            discovery.targets.firstOrNull {
                it.id == selected.targetId &&
                    it.socketName == selected.socketName &&
                    it.title == SCHOOL_TITLE &&
                    it.type.equals("page", ignoreCase = true) &&
                    it.containerName == WEWORK_PACKAGE &&
                    it.schoolMatch == SchoolTargetMatch.SCHOOL_TARGET_FOUND
            }
        }
        if (discovery?.finalState != DevToolsDiscoveryState.SCHOOL_TARGET_FOUND || endpoint == null || target == null) {
            semesterListenerState = "TARGET_NOT_CONFIRMED"
            semesterListenerError = "先重新发现并确认“我的课表”学校 Target，再开始或继续采集。"
            return
        }

        val session = (semesterSession ?: SemesterCollectionSession.start())
            .withState(SemesterCollectionState.SEMESTER_COLLECTION_RUNNING)
        semesterSession = session
        semesterPreview = null
        schoolImportPlan = null
        schoolImportResult = null
        schoolImportConfirmVisible = false
        semesterListenerError = null
        semesterSessionStoreError = null
        semesterListenerState = "CDP_CONNECT_START"
        networkState = "NOT_RUN"
        networkMainFrameUrl = null
        busy = true

        lateinit var collectionJob: Job
        collectionJob = operationScope.launch {
            val captureChannel = Channel<CourseCaptureSnapshot>(Channel.UNLIMITED)
            val handledRequestIds = linkedSetOf<String>()
            var processorJob: Job? = null
            var openedStream: AdbStream? = null
            var cdpClient: DevToolsCdpClient? = null
            var observerStarted = false
            try {
                persistSemesterSession(session)
                processorJob = operationScope.launch {
                    for (captureSnapshot in captureChannel) {
                        networkSchoolRequestCount = captureSnapshot.schoolRequestCount
                        networkRequests = captureSnapshot.recentRequests
                        val confirmed = captureSnapshot.recentRequests.filter {
                            it.bodyState == CourseBodyState.COURSE_JSON_CONFIRMED && it.scheduleSnapshot != null
                        }
                        confirmed.forEach { request ->
                            if (!handledRequestIds.add(request.requestId)) return@forEach
                            val sourceSnapshot = request.scheduleSnapshot ?: return@forEach
                            val current = semesterSession ?: return@forEach
                            val outcome = current.recordSnapshot(sourceSnapshot)
                            when (outcome) {
                                is SemesterSnapshotCaptureResult.Accepted -> {
                                    var updated = outcome.session
                                    if (!appInForeground) updated = updated.markBackgroundCdpSurvived()
                                    semesterSession = updated
                                    semesterPreview = null
                                    schoolImportPlan = null
                                    schoolImportResult = null
                                    schoolImportConfirmVisible = false
                                    semesterListenerState = when {
                                        updated.coverageComplete -> "SEMESTER_COVERAGE_COMPLETE"
                                        !appInForeground -> "BACKGROUND_CDP_SURVIVED"
                                        outcome.replacedExistingWeek -> "WEEK_${outcome.week}_RECAPTURED"
                                        else -> "WEEK_${outcome.week}_CAPTURED"
                                    }
                                    semesterListenerError = null
                                    persistSemesterSessionSafely(updated)
                                }
                                is SemesterSnapshotCaptureResult.Rejected -> {
                                    semesterSession = outcome.session
                                    semesterListenerState = outcome.warning.name
                                    semesterListenerError = when (outcome.warning) {
                                        SemesterCollectionWarning.SEMESTER_METADATA_MISMATCH ->
                                            "此响应的学年或学期标识与当前 Session 不同，已排除。"
                                        SemesterCollectionWarning.MAX_WEEK_MISMATCH ->
                                            "此响应的 maxzc 与 Session 基准不同，已排除并保留警告。"
                                        SemesterCollectionWarning.INVALID_CAPTURE_WEEK ->
                                            "此响应的 zc 或 maxzc 无效，未计入覆盖。"
                                        SemesterCollectionWarning.INVALID_SEMESTER_METADATA ->
                                            "此响应缺少学年或学期标识，未计入覆盖。"
                                        SemesterCollectionWarning.CDP_SESSION_LOST ->
                                            "CDP 连接已断开。"
                                    }
                                    persistSemesterSessionSafely(outcome.session)
                                }
                            }
                        }
                    }
                }
                semesterCaptureChannel = captureChannel
                semesterCaptureProcessorJob = processorJob

                connectionState = ADB_CONNECTING
                if (!client.connect(port)) throw IOException("TLS connection was not established")
                connectionState = ADB_CONNECTED
                openedStream = client.openDevToolsSocket(endpoint.socketName)
                when (val connected = DevToolsCdpClient.connect(openedStream, endpoint.rawWebSocketUrl)) {
                    is DevToolsCdpConnectResult.Failed -> {
                        semesterListenerState = connected.handshakeDiagnostic?.classificationLabel
                            ?: connected.failure.name
                        semesterListenerError = connected.handshakeDiagnostic?.classificationLabel
                            ?: connected.failure.name
                        semesterSession = semesterSession?.markCdpDisconnected()
                    }
                    is DevToolsCdpConnectResult.Connected -> {
                        cdpClient = connected.client
                        semesterListenerState = "CDP_CONNECT_SUCCESS"
                        when (val frameTree = requestMainFrameUrl(connected.client)) {
                            is CdpFrameTreeProbeResult.Success -> {
                                networkMainFrameUrl = frameTree.mainFrameUrl
                                val frame = frameTree.mainFrameUrl
                                if (!frame.scheme.equals("https", ignoreCase = true) ||
                                    !frame.host.equals(SCHOOL_HOST, ignoreCase = true)
                                ) {
                                    semesterListenerState = "TARGET_NOT_SCHOOL_PAGE"
                                    semesterListenerError = "主 Frame 不匹配已确认的学校域名，未启用 Network。"
                                } else {
                                    observerStarted = true
                                    val observer = CdpNetworkObserver(
                                        client = connected.client,
                                        onState = { state ->
                                            if (state == CdpNetworkObserverState.CDP_DISCONNECTED) {
                                                semesterListenerState = if (appInForeground) {
                                                    "CDP_SESSION_LOST"
                                                } else {
                                                    "CDP_SESSION_LOST_WHILE_BACKGROUND"
                                                }
                                            } else {
                                                semesterListenerState = state.name
                                                if (state == CdpNetworkObserverState.NETWORK_ENABLED) {
                                                    networkState = "ENABLED"
                                                }
                                            }
                                        },
                                        onSnapshot = { captureSnapshot ->
                                            captureChannel.trySend(captureSnapshot)
                                        }
                                    )
                                    when (val result = observer.observe()) {
                                        CdpNetworkObserverResult.Stopped -> Unit
                                        is CdpNetworkObserverResult.Disconnected -> {
                                            networkState = "DISCONNECTED"
                                            val lost = semesterSession?.markCdpDisconnected()
                                            if (lost != null) {
                                                semesterSession = lost
                                                persistSemesterSessionSafely(lost)
                                            }
                                            semesterListenerError = result.failure.name
                                        }
                                        is CdpNetworkObserverResult.NetworkEnableFailed -> {
                                            networkState = "NETWORK_ENABLE_FAILED"
                                            semesterListenerState = "NETWORK_ENABLE_FAILED"
                                            semesterListenerError = buildString {
                                                append(result.failure.name)
                                                result.protocolCode?.let { append(" · code=").append(it) }
                                            }
                                            semesterSession = semesterSession?.withState(
                                                SemesterCollectionState.SEMESTER_COLLECTION_STOPPED
                                            )
                                        }
                                    }
                                }
                            }
                            is CdpFrameTreeProbeResult.CommandFailed -> {
                                semesterListenerState = "PAGE_GET_FRAME_TREE_FAILED"
                                semesterListenerError = frameTree.failure.name
                                semesterSession = semesterSession?.withState(
                                    SemesterCollectionState.SEMESTER_COLLECTION_STOPPED
                                )
                            }
                            CdpFrameTreeProbeResult.MainFrameUrlMissing -> {
                                semesterListenerState = "TARGET_NOT_SCHOOL_PAGE"
                                semesterListenerError = "主 Frame URL 缺失，未启用 Network。"
                                semesterSession = semesterSession?.withState(
                                    SemesterCollectionState.SEMESTER_COLLECTION_STOPPED
                                )
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                semesterListenerState = if (observerStarted) "CDP_SESSION_LOST" else "SEMESTER_COLLECTION_START_FAILED"
                semesterListenerError = exception.javaClass.simpleName.ifBlank { "Exception" }
                val current = semesterSession
                if (current != null) {
                    semesterSession = if (observerStarted) current.markCdpDisconnected() else
                        current.withState(SemesterCollectionState.SEMESTER_COLLECTION_STOPPED)
                }
            } finally {
                captureChannel.close()
                withContext(NonCancellable) { processorJob?.join() }
                runCatching { cdpClient?.close() }
                if (cdpClient == null) runCatching { openedStream?.close() }
                if (semesterCaptureChannel === captureChannel) semesterCaptureChannel = null
                if (semesterCaptureProcessorJob === processorJob) semesterCaptureProcessorJob = null
                if (semesterCollectionJob === collectionJob) semesterCollectionJob = null
                if (semesterListenerState == "STOPPING") {
                    semesterListenerState = if (semesterSession?.coverageComplete == true) {
                        "SEMESTER_COVERAGE_COMPLETE"
                    } else {
                        "SEMESTER_COLLECTION_STOPPED"
                    }
                }
                val finalSession = semesterSession
                if (finalSession != null && finalSession.sessionState != SemesterCollectionState.SEMESTER_COLLECTION_RUNNING) {
                    withContext(NonCancellable) { persistSemesterSessionSafely(finalSession) }
                }
                busy = false
            }
        }
        semesterCollectionJob = collectionJob
    }

    fun clearSemesterCollection() {
        if (semesterCollectionJob != null || (semesterSession == null && semesterSessionStoreError == null)) return
        semesterSession = null
        semesterPreview = null
        schoolImportPlan = null
        schoolImportResult = null
        schoolImportConfirmVisible = false
        semesterListenerError = null
        semesterListenerState = "IDLE"
        semesterSessionStoreError = null
        operationScope.launch {
            try {
                withContext(Dispatchers.IO) { semesterStore.clear() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                semesterSessionStoreError = exception.javaClass.simpleName.ifBlank { "IOException" }
            }
        }
    }

    fun startCourseNetworkListener() {
        if (networkListenerJob != null || semesterCollectionJob != null || busy) return
        val port = connectPort.toIntOrNull()
        if (port == null || port !in 1..65535) {
            networkListenerState = "FAILED"
            networkFailureDetail = "请输入 Wireless Debugging 主页面显示的有效连接端口。"
            return
        }
        if (!uidVerified || idState != ID_EXECUTED || probePort != port) {
            networkListenerState = "FAILED"
            networkFailureDetail = "请先使用当前连接端口运行 id，并确认 UID 2000。"
            return
        }

        val discovery = devToolsResult
        val endpoint = discovery?.schoolCdpEndpoint
        val target = endpoint?.let { selected ->
            discovery.targets.firstOrNull {
                it.id == selected.targetId &&
                    it.socketName == selected.socketName &&
                    it.title == SCHOOL_TITLE &&
                    it.type.equals("page", ignoreCase = true) &&
                    it.containerName == WEWORK_PACKAGE &&
                    it.schoolMatch == SchoolTargetMatch.SCHOOL_TARGET_FOUND
            }
        }
        if (discovery?.finalState != DevToolsDiscoveryState.SCHOOL_TARGET_FOUND || endpoint == null || target == null) {
            networkListenerState = "FAILED"
            networkFailureDetail = "请先确认已找到企业微信中的“我的课表”。"
            return
        }

        networkSessionState = "CONNECTING"
        networkState = "NOT_RUN"
        networkListenerState = "CDP_CONNECT_START"
        networkSchoolRequestCount = 0
        networkRequests = emptyList()
        schoolImportPreview = null
        previewRequestNumber = null
        networkMainFrameUrl = null
        networkFailureDetail = null
        networkFinalState = null
        busy = true

        lateinit var listenerJob: Job
        listenerJob = operationScope.launch {
            var openedStream: AdbStream? = null
            var cdpClient: DevToolsCdpClient? = null
            try {
                connectionState = ADB_CONNECTING
                if (!client.connect(port)) throw IOException("TLS connection was not established")
                connectionState = ADB_CONNECTED
                openedStream = client.openDevToolsSocket(endpoint.socketName)
                when (val connected = DevToolsCdpClient.connect(openedStream, endpoint.rawWebSocketUrl)) {
                    is DevToolsCdpConnectResult.Failed -> {
                        networkSessionState = "FAILED"
                        networkListenerState = connected.handshakeDiagnostic?.classificationLabel
                            ?: connected.failure.name
                        cdpHandshakeDiagnostic = connected.handshakeDiagnostic
                        networkFailureDetail = connected.handshakeDiagnostic?.classificationLabel
                            ?: connected.failure.name
                    }
                    is DevToolsCdpConnectResult.Connected -> {
                        cdpClient = connected.client
                        cdpHandshakeDiagnostic = connected.handshakeDiagnostic
                        networkSessionState = "CONNECTED"
                        networkListenerState = "PAGE_GET_FRAME_TREE_START"
                        when (val frameTree = requestMainFrameUrl(connected.client)) {
                            is CdpFrameTreeProbeResult.Success -> {
                                networkMainFrameUrl = frameTree.mainFrameUrl
                                val frame = frameTree.mainFrameUrl
                                if (!frame.scheme.equals("https", ignoreCase = true) ||
                                    !frame.host.equals(SCHOOL_HOST, ignoreCase = true)
                                ) {
                                    networkListenerState = "TARGET_NOT_SCHOOL_PAGE"
                                    networkFailureDetail = "当前主 Frame 不在已确认的学校域名，未启用 Network。"
                                } else {
                                    val observer = CdpNetworkObserver(
                                        client = connected.client,
                                        onState = { state ->
                                            if (state == CdpNetworkObserverState.CDP_DISCONNECTED) {
                                                networkListenerState = when (classifyCdpDisconnect(appInForeground)) {
                                                    CdpDisconnectContext.FOREGROUND -> "CDP_DISCONNECTED"
                                                    CdpDisconnectContext.BACKGROUND -> "CDP_DISCONNECTED_WHILE_BACKGROUND"
                                                }
                                                networkState = "DISCONNECTED"
                                            } else {
                                                networkListenerState = state.name
                                                if (state == CdpNetworkObserverState.NETWORK_ENABLED) {
                                                    networkState = "ENABLED"
                                                }
                                            }
                                            if (state == CdpNetworkObserverState.COURSE_JSON_CONFIRMED) {
                                                networkFinalState = "NETWORK_OBSERVER_CAPTURE_SUCCESS"
                                                networkListenerJob?.cancel()
                                            }
                                        },
                                        onSnapshot = { snapshot ->
                                            networkSchoolRequestCount = snapshot.schoolRequestCount
                                            networkRequests = snapshot.recentRequests
                                            if (snapshot.recentRequests.any {
                                                    it.bodyState == CourseBodyState.COURSE_JSON_CONFIRMED
                                                }
                                            ) {
                                                networkFinalState = "NETWORK_OBSERVER_CAPTURE_SUCCESS"
                                                networkListenerJob?.cancel()
                                            }
                                        }
                                    )
                                    when (val result = observer.observe()) {
                                        CdpNetworkObserverResult.Stopped -> Unit
                                        is CdpNetworkObserverResult.Disconnected -> {
                                            networkState = "DISCONNECTED"
                                            networkFailureDetail = result.failure.name
                                        }
                                        is CdpNetworkObserverResult.NetworkEnableFailed -> {
                                            networkState = "NETWORK_ENABLE_FAILED"
                                            networkListenerState = "NETWORK_ENABLE_FAILED"
                                            networkFailureDetail = buildString {
                                                append(result.failure.name)
                                                result.protocolCode?.let { append(" · code=").append(it) }
                                                result.protocolMessage?.let { append(" · ").append(it) }
                                            }
                                        }
                                    }
                                }
                            }
                            is CdpFrameTreeProbeResult.CommandFailed -> {
                                networkListenerState = "PAGE_GET_FRAME_TREE_FAILED"
                                networkFailureDetail = buildString {
                                    append(frameTree.failure.name)
                                    frameTree.protocolCode?.let { append(" · code=").append(it) }
                                    frameTree.protocolMessage?.let { append(" · ").append(it) }
                                }
                            }
                            CdpFrameTreeProbeResult.MainFrameUrlMissing -> {
                                networkListenerState = "TARGET_NOT_SCHOOL_PAGE"
                                networkFailureDetail = "当前主 Frame URL 缺失，未启用 Network。"
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                networkSessionState = "FAILED"
                networkListenerState = if (networkListenerState == "CDP_CONNECT_START") {
                    "CDP_CONNECT_FAILED"
                } else {
                    "NETWORK_LISTENER_FAILED"
                }
                networkFailureDetail = exception.javaClass.simpleName.ifBlank { "Exception" }
            } finally {
                runCatching { cdpClient?.close() }
                if (cdpClient == null) runCatching { openedStream?.close() }
                networkListenerJob = null
                if (networkListenerState == "STOPPING") networkListenerState = "STOPPED"
                busy = false
            }
        }
        networkListenerJob = listenerJob
    }

    fun runExecutorProbe(command: LocalAdbReadOnlyCommand, summarize: (String, Int?) -> String?) {
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
                val printfAnalysis = if (command.kind == DebugLocalAdbCommandKind.PRINTF_TEST) {
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
                        summarize(result.output, result.exitCode)
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

    SubPageScaffold("学校同步诊断", onBack) {
        Text(
            "只连接本机 127.0.0.1。你需要在系统 Wireless Debugging 页面手动配对；本页不会打开或更改系统设置。",
            style = AppType.body,
            color = colors.mut
        )

        SectionLabel("无线调试配对")
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

        SectionLabel("连接与 UID 检查")
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

        SectionLabel("课表页面目标诊断")
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
                Text(
                    "扫描具名 abstract DevTools socket，通过 localabstract 读取 /json/list 与必要的 /json。先规范化 HTTP metadata URL；只有仍无法确认 URL 时才诊断 WebSocket 握手。本阶段不发送 CDP 方法命令。",
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
                    result.cdpFailure?.let {
                        Text("CDP 安全诊断：${it.name}", style = AppType.caption, color = colors.mut)
                    }
                    result.cdpHandshakeDiagnostic?.let { diagnostic ->
                        SectionLabel("WebSocket 握手诊断")
                        Text(
                            "分类=${diagnostic.classificationLabel} · HTTP=${diagnostic.responseStatus ?: "无响应"} · reason=${diagnostic.responseReason ?: "无"}",
                            style = AppType.caption,
                            color = if (diagnostic.classification != DevToolsCdpFailure.CDP_HANDSHAKE_ACCEPTED) colors.danger else colors.ink
                        )
                        Text("响应正文摘要：${diagnostic.responseBodySummary}", style = AppType.caption, color = colors.mut)
                        Text("发送 Header 名称：${diagnostic.request.headerNames.joinToString().ifBlank { "无" }}", style = AppType.caption, color = colors.mut)
                        Text(
                            "Host=${diagnostic.request.hostHeaderValue ?: "无"} · Origin=${if (diagnostic.request.originHeaderSent) diagnostic.request.originHeaderValue ?: "值未知" else "未发送"}",
                            style = AppType.caption,
                            color = colors.mut
                        )
                        Text(
                            "Upgrade=${diagnostic.request.upgradeHeaderValue ?: "无"} · Connection=${diagnostic.request.connectionHeaderValue ?: "无"} · Sec-WebSocket-Version=${diagnostic.request.webSocketVersionHeaderValue ?: "无"}",
                            style = AppType.caption,
                            color = colors.mut
                        )
                    }
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
                            Text("URL 来源：${target.urlSource?.name ?: "HTTP 中未取得"}", style = AppType.caption, color = colors.mut)
                            target.endpointSummaries.forEach { metadata ->
                                Text(
                                    "${metadata.endpoint.path} · HTTP ${metadata.httpStatus} · id=${metadata.targetId} · type=${metadata.type} · title=${metadata.title.ifBlank { "（空）" }}",
                                    style = AppType.caption,
                                    color = colors.ink
                                )
                                Text(
                                    "url=${metadata.urlKind} · 长度=${metadata.urlStringLength ?: "—"} · ${metadata.displayUrl.ifBlank { "无可显示 URL" }}",
                                    style = AppType.caption,
                                    color = colors.mut
                                )
                                if (metadata.strictUrlParseFailureType != null) {
                                    Text(
                                        "完整 URL 严格解析：${metadata.strictUrlParseFailureType}${metadata.strictUrlParseFailureIndex?.let { " · index=$it" }.orEmpty()}；原始 query/fragment 已隐藏",
                                        style = AppType.caption,
                                        color = colors.mut
                                    )
                                }
                                Text(
                                    "description=${metadata.descriptionKind} · keys=${metadata.descriptionKeys.joinToString().ifBlank { "无" }}",
                                    style = AppType.caption,
                                    color = colors.mut
                                )
                                Text(
                                    "webSocketDebuggerUrl=${if (metadata.hasWebSocketDebuggerUrl) "存在" else "缺失"} · 长度=${metadata.webSocketUrlStringLength ?: "—"}",
                                    style = AppType.caption,
                                    color = colors.mut
                                )
                                Text(
                                    "字段：" + metadata.fields.joinToString(" · ") { field ->
                                        "${field.name}=${field.kind}${field.stringLength?.let { "($it)" }.orEmpty()}"
                                    },
                                    style = AppType.caption,
                                    color = colors.mut
                                )
                            }
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

        SectionLabel("只读页面会话诊断")
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
                Text(
                    "只连接已确认的“我的课表”页面，完成 WebSocket 握手后仅发送一次 Page.getFrameTree，再立即关闭连接。不会读取 DOM、页面正文、Cookie、Storage 或学校接口。",
                    style = AppType.body,
                    color = colors.ink
                )
                val phase4Endpoint = devToolsResult?.schoolCdpEndpoint
                val phase4Target = phase4Endpoint?.let { endpoint ->
                    devToolsResult?.targets?.firstOrNull {
                        it.id == endpoint.targetId &&
                            it.socketName == endpoint.socketName &&
                            it.title == SCHOOL_TITLE &&
                            it.type.equals("page", ignoreCase = true) &&
                            it.containerName == WEWORK_PACKAGE &&
                            it.schoolMatch == SchoolTargetMatch.SCHOOL_TARGET_FOUND
                    }
                }
                if (phase4Target != null && devToolsResult?.finalState == DevToolsDiscoveryState.SCHOOL_TARGET_FOUND) {
                    Text("Target：${phase4Target.title} · ${phase4Target.type} · ${phase4Target.containerName}", style = AppType.caption, color = colors.ink)
                    Text("Socket：${phase4Target.socketName} · target id：${phase4Target.id}", style = AppType.caption, color = colors.mut)
                    PillButton(
                        if (busy && cdpWebSocketState == "CONNECTING") "CDP 连接中…" else "验证 CDP 最小连接",
                        onClick = ::verifyCdpMinimalConnection,
                        style = PillStyle.Primary
                    )
                } else {
                    Text(
                        if (devToolsResult?.finalState == DevToolsDiscoveryState.SCHOOL_TARGET_FOUND) {
                            "学校 Target 已确认，但其 WebSocket 地址不可用；本轮无法建立 CDP 会话。"
                        } else {
                            "先确认已找到企业微信中的“我的课表”；确认前不会显示连接按钮。"
                        },
                        style = AppType.caption,
                        color = colors.mut
                    )
                }

                if (cdpWebSocketState != "NOT_RUN") {
                    Text("WebSocket：$cdpWebSocketState", style = AppType.caption, color = if (cdpWebSocketState == "FAILED") colors.danger else colors.ink)
                    Text("CDP：$cdpCommandState", style = AppType.caption, color = colors.ink)
                    cdpHandshakeDiagnostic?.let { diagnostic ->
                        Text(
                            "Handshake：${diagnostic.classificationLabel} · HTTP=${diagnostic.responseStatus ?: "无响应"} · reason=${diagnostic.responseReason ?: "无"}",
                            style = AppType.caption,
                            color = if (diagnostic.classification != DevToolsCdpFailure.CDP_HANDSHAKE_ACCEPTED) colors.danger else colors.ink
                        )
                        Text("响应正文摘要：${diagnostic.responseBodySummary}", style = AppType.caption, color = colors.mut)
                        Text("发送 Header：${diagnostic.request.headerNames.joinToString()}", style = AppType.caption, color = colors.mut)
                        Text(
                            "Host=${diagnostic.request.hostHeaderValue ?: "无"} · Origin=${if (diagnostic.request.originHeaderSent) diagnostic.request.originHeaderValue ?: "值未知" else "未发送"}",
                            style = AppType.caption,
                            color = colors.mut
                        )
                        Text(
                            "Upgrade=${diagnostic.request.upgradeHeaderValue ?: "无"} · Connection=${diagnostic.request.connectionHeaderValue ?: "无"} · Sec-WebSocket-Version=${diagnostic.request.webSocketVersionHeaderValue ?: "无"}",
                            style = AppType.caption,
                            color = colors.mut
                        )
                    }
                    cdpHttpTargetUrl?.let { Text("HTTP Target URL：$it", style = AppType.caption, color = colors.mut) }
                    cdpMainFrameUrl?.let { Text("CDP main frame：${it.displayUrl}", style = AppType.caption, color = colors.ink) }
                    cdpUrlComparison?.let { Text("HTTP / CDP：$it", style = AppType.caption, color = if (it == "MATCH") colors.ink else colors.danger) }
                    cdpFailureMessage?.let { Text("诊断：$it", style = AppType.caption, color = colors.danger) }
                    cdpFinalState?.let { Text(it, style = AppType.body, color = if (it == "CDP_READ_ONLY_PROBE_SUCCESS") colors.ink else colors.danger) }
                }
            }
        }

        SectionLabel("课程请求观察诊断")
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
                Text(
                    "只监听已确认的企业微信“我的课表”页面自身发出的请求。仅匹配学校域名与课表路径；不会读取请求头、Cookie、Storage、页面内容，也不会主动请求或刷新页面。响应正文只在内存中解析为课表摘要，不保存原文。",
                    style = AppType.body,
                    color = colors.ink
                )
                val networkEndpoint = devToolsResult?.schoolCdpEndpoint
                val networkTarget = networkEndpoint?.let { endpoint ->
                    devToolsResult?.targets?.firstOrNull {
                        it.id == endpoint.targetId &&
                            it.socketName == endpoint.socketName &&
                            it.title == SCHOOL_TITLE &&
                            it.type.equals("page", ignoreCase = true) &&
                            it.containerName == WEWORK_PACKAGE &&
                            it.schoolMatch == SchoolTargetMatch.SCHOOL_TARGET_FOUND
                    }
                }
                if (networkTarget != null && devToolsResult?.finalState == DevToolsDiscoveryState.SCHOOL_TARGET_FOUND) {
                    Text("Target：${networkTarget.title} · ${networkTarget.type} · ${networkTarget.containerName}", style = AppType.caption, color = colors.ink)
                    Text("URL：${networkTarget.displayUrl}", style = AppType.caption, color = colors.mut)
                    PillButton(
                        if (networkListenerJob != null) "停止监听" else "开始监听",
                        onClick = {
                            if (networkListenerJob != null) stopCourseNetworkListener()
                            else startCourseNetworkListener()
                        },
                        style = if (networkListenerJob != null) PillStyle.Secondary else PillStyle.Primary
                    )
                } else {
                    Text(
                        "先确认已找到企业微信中的“我的课表”；未确认前不会连接或开始观察。",
                        style = AppType.caption,
                        color = colors.mut
                    )
                }

                if (networkListenerState != "IDLE" || networkSessionState != "NOT_RUN") {
                    Text("CDP：$networkSessionState", style = AppType.caption, color = colors.ink)
                    Text("Network：$networkState", style = AppType.caption, color = colors.ink)
                    Text("监听：$networkListenerState", style = AppType.caption, color = colors.ink)
                    Text("学校域请求：$networkSchoolRequestCount", style = AppType.caption, color = colors.mut)
                    networkMainFrameUrl?.let {
                        Text("主 Frame：${it.displayUrl}", style = AppType.caption, color = colors.mut)
                    }
                    networkFailureDetail?.let {
                        Text("诊断：$it", style = AppType.caption, color = colors.danger)
                    }
                    networkRequests.forEach { request ->
                        SectionLabel("Request #${request.number}")
                        Text("${request.method} ${request.host}${request.path}", style = AppType.caption, color = colors.ink)
                        Text(
                            "HTTP：${request.status ?: "等待响应"} · MIME：${request.mimeType ?: "未知"} · 类型：${request.resourceType ?: "未知"}",
                            style = AppType.caption,
                            color = colors.mut
                        )
                        Text("Body：${request.bodyState.name}", style = AppType.caption, color = if (
                            request.bodyState in setOf(CourseBodyState.BODY_CDP_ERROR, CourseBodyState.BODY_NOT_JSON,
                                CourseBodyState.BODY_DECODE_ERROR, CourseBodyState.BODY_NOT_AVAILABLE,
                                CourseBodyState.BODY_REQUEST_ID_INVALID, CourseBodyState.HTTP_NOT_OK,
                                CourseBodyState.REQUEST_FAILED, CourseBodyState.RESPONSE_MISSING,
                                CourseBodyState.BODY_TOO_LARGE)
                        ) colors.danger else colors.ink)
                        request.cdpErrorCode?.let { code ->
                            Text("CDP error：$code · ${request.cdpErrorMessage.orEmpty()}", style = AppType.caption, color = colors.danger)
                        }
                        request.payload?.let { payload ->
                            Text(
                                "JSON：${if (payload.isCourseSchedulePayload) "COURSE_JSON_CONFIRMED" else "未确认课表结构"}",
                                style = AppType.caption,
                                color = if (payload.isCourseSchedulePayload) colors.ink else colors.mut
                            )
                            Text(
                                "学年：${payload.academicYear ?: "—"} · 学期：${payload.term ?: "—"} · 当前周：${payload.currentWeek ?: "—"} · 最大周：${payload.maximumWeek ?: "—"}",
                                style = AppType.caption,
                                color = colors.ink
                            )
                            Text(
                                "日期：${payload.startDate ?: "—"} ~ ${payload.endDate ?: "—"} · 最大节次：${payload.maximumPeriod ?: "—"}",
                                style = AppType.caption,
                                color = colors.mut
                            )
                            Text(
                                (1..7).joinToString(" · ") { week -> "week$week: ${payload.weekItemCounts[week] ?: 0}" },
                                style = AppType.caption,
                                color = colors.mut
                            )
                        }
                    }
                    val confirmedRequest = networkRequests.lastOrNull {
                        it.bodyState == CourseBodyState.COURSE_JSON_CONFIRMED && it.scheduleSnapshot != null
                    }
                    if (confirmedRequest != null) {
                        PillButton(
                            "生成课程导入预览",
                            onClick = {
                                val snapshot = confirmedRequest.scheduleSnapshot ?: return@PillButton
                                val candidates = SchoolImportMapper.map(snapshot)
                                schoolImportPreview = SchoolImportPreview(
                                    candidates = candidates,
                                    summary = SchoolImportPreviewSummary.from(snapshot, candidates)
                                )
                                previewRequestNumber = confirmedRequest.number
                            },
                            style = PillStyle.Primary
                        )
                        val preview = schoolImportPreview
                        if (preview != null && previewRequestNumber == confirmedRequest.number) {
                            SchoolImportPreviewPanel(confirmedRequest.scheduleSnapshot!!, preview)
                        }
                    }
                    networkFinalState?.let {
                        Text(it, style = AppType.body, color = colors.ink)
                    }
                }
            }
        }

        SectionLabel("整学期采集诊断")
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
                Text(
                    "只接收企业微信课表页手动切周后产生的已确认响应，并用响应 zc 识别周次。不会自动切周、主动构造学校请求或写入正式课程表数据库。采集进度只保存在本机 App 私有目录；不保存原始 JSON、完整请求、headers 或 liveUrl。",
                    style = AppType.body,
                    color = colors.ink
                )
                Text(
                    "先完成 SCHOOL_TARGET_FOUND 并确认 UID 2000。点击开始后切回企业微信，手动浏览各周；如果 CDP 断开，返回后重新发现 Target，再继续已有 Session。",
                    style = AppType.caption,
                    color = colors.mut
                )
                Text("采集状态：${semesterListenerState}", style = AppType.caption, color = colors.ink)
                if (!semesterSessionStoreReady) {
                    Text("正在读取本机临时采集进度…", style = AppType.caption, color = colors.mut)
                } else {
                    val session = semesterSession
                    if (session == null) {
                        Text("当前没有临时 Session。", style = AppType.caption, color = colors.mut)
                    } else {
                        Text(
                            "Session：${session.sessionState.name} · 学年：${session.academicYear ?: "等待响应"} · 学校学期：${session.semesterFlag ?: "等待响应"}",
                            style = AppType.caption,
                            color = colors.ink
                        )
                        Text(
                            "最大周：${session.maxWeek ?: "等待学校返回 maxzc"} · 已采集：${session.capturedWeeks.size} / ${session.maxWeek ?: "—"} 周",
                            style = AppType.caption,
                            color = colors.ink
                        )
                        Text("已采集周：${formatWeekSet(session.capturedWeeks)}", style = AppType.caption, color = colors.ink)
                        Text(
                            "缺失周：${if (session.maxWeek == null) "等待首个有效响应" else formatWeekSet(session.missingWeeks)}",
                            style = AppType.caption,
                            color = if (session.missingWeeks.isEmpty() && session.maxWeek != null) colors.ink else colors.mut
                        )
                        Text(
                            "原始规则发现：${session.rawRuleCount} · 跨周精确去重后：${session.uniqueRuleCount} · 有警告规则：${session.rulesByKey.values.count { it.warnings.isNotEmpty() || !session.ruleWarningsByKey[it.ruleKey].isNullOrEmpty() }}",
                            style = AppType.caption,
                            color = colors.ink
                        )
                        session.lastCapturedWeek?.let { week ->
                            val recaptured = session.recaptureCountByWeek[week] ?: 0
                            Text(
                                "最后捕获：第${week}周${if (recaptured > 0) " · 已重新采集 $recaptured 次" else ""}",
                                style = AppType.caption,
                                color = colors.mut
                            )
                        }
                        if (session.backgroundCdpSurvived) {
                            Text("BACKGROUND_CDP_SURVIVED：App 切到后台期间仍收到并保存课表响应。", style = AppType.caption, color = colors.ink)
                        }
                        if (session.warnings.isNotEmpty()) {
                            Text(
                                "Session 警告：${session.warnings.joinToString(" · ") { it.name }}",
                                style = AppType.caption,
                                color = colors.danger
                            )
                        }
                    }
                }
                semesterSessionStoreError?.let {
                    Text("本机保存诊断：$it", style = AppType.caption, color = colors.danger)
                }
                semesterListenerError?.let {
                    Text("监听诊断：$it", style = AppType.caption, color = colors.danger)
                }
                when {
                    semesterCollectionJob != null -> PillButton(
                        "停止采集",
                        onClick = ::stopSemesterCollection,
                        style = PillStyle.Secondary
                    )
                    !semesterSessionStoreReady -> Unit
                    else -> PillButton(
                        when (semesterSession?.sessionState) {
                            null -> "开始整学期采集"
                            SemesterCollectionState.CDP_SESSION_LOST -> "重新连接并继续"
                            SemesterCollectionState.SEMESTER_COVERAGE_COMPLETE -> "重连后复采（可选）"
                            SemesterCollectionState.SEMESTER_COLLECTION_STOPPED -> "重新连接并继续"
                            SemesterCollectionState.SEMESTER_COLLECTION_RUNNING -> "恢复采集连接"
                        },
                        onClick = ::startSemesterCollection,
                        style = PillStyle.Primary
                    )
                }
                if (semesterSession?.snapshotsByWeek?.isNotEmpty() == true) {
                    PillButton(
                        "生成整学期预览",
                        onClick = {
                            semesterSession?.let {
                                semesterPreview = mergeSemesterSession(it)
                                schoolImportPlan = null
                                schoolImportResult = null
                                schoolImportConfirmVisible = false
                            }
                        },
                        style = PillStyle.Secondary
                    )
                }
                if (semesterSession != null || semesterSessionStoreError != null) {
                    PillButton(
                        "清除采集",
                        onClick = ::clearSemesterCollection,
                        style = PillStyle.Ghost,
                        danger = true
                    )
                }
                semesterPreview?.let { preview ->
                    SemesterPreviewPanel(preview)
                    SchoolImportPlanPanel(
                        plan = schoolImportPlan,
                        preparing = schoolImportPreparing,
                        writing = schoolImportWriting,
                        confirmVisible = schoolImportConfirmVisible,
                        result = schoolImportResult,
                        onPrepare = { prepareSchoolImport(preview) },
                        onRequestConfirm = { schoolImportConfirmVisible = true },
                        onDismissConfirm = { schoolImportConfirmVisible = false },
                        onConfirm = ::confirmSchoolImport
                    )
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
                        runExecutorProbe(LocalAdbReadOnlyCommand.PRINTF_TEST) { output, _ ->
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
                    runExecutorProbe(LocalAdbReadOnlyCommand.PROC_UNIX_READ) { output, exitCode ->
                            val result = parseNamedUnixSockets(output, exitCode)
                            if (result.successful) "读取成功 · ${output.lineSequence().count()} 行" else null
                        }
                    },
                    style = PillStyle.Secondary
                )
                PillButton(
                    "验证不存在的进程查询",
                    onClick = {
                        runExecutorProbe(LocalAdbReadOnlyCommand.MISSING_PROCESS_QUERY) { output, exitCode ->
                            if (isEmptyMissingProcessQueryOutput(output, exitCode)) {
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
                    DebugLocalAdbCommandKind.PRINTF_TEST,
                    LocalAdbCommandKind.PROC_UNIX_READ,
                    DebugLocalAdbCommandKind.MISSING_PROCESS_QUERY
                ).forEach { kind ->
                    val status = commandProbeResults[kind]?.state?.name ?: "NOT_RUN"
                    Text("${kind.safeLabel}：$status", style = AppType.caption, color = if (status == CommandExecutorProbeState.FAILED.name) colors.danger else colors.mut)
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
private fun SemesterPreviewPanel(schedule: MergedSemesterSchedule) {
    val colors = LocalAppColors.current
    val snapshot = schedule.snapshot
    val candidatesByRule = schedule.candidates.groupBy { it.sourceRuleKey }
    SectionLabel("整学期合并预览")
    Text(
        if (schedule.coverageComplete) "SEMESTER_COVERAGE_COMPLETE" else "PARTIAL_SEMESTER_PREVIEW",
        style = AppType.caption,
        color = if (schedule.coverageComplete) colors.ink else colors.mut
    )
    Text("来源：山东商务职业学院", style = AppType.body, color = colors.ink)
    Text(
        "学年：${snapshot.academicYear ?: "—"} · 学校学期标识：${snapshot.semesterFlag ?: "—"}",
        style = AppType.caption,
        color = colors.ink
    )
    Text(
        "覆盖：${schedule.capturedWeeks.size} / ${snapshot.maxWeek ?: "—"} 周 · 已采集：${formatWeekSet(schedule.capturedWeeks)} · 缺失：${formatWeekSet(schedule.missingWeeks)}",
        style = AppType.caption,
        color = colors.ink
    )
    Text(
        "原始 Snapshot：${schedule.rawSnapshotCount} · 原始排课规则累计：${schedule.rawRuleCount} · 跨周去重：${schedule.crossWeekDuplicateCount}",
        style = AppType.caption,
        color = colors.ink
    )
    Text(
        "整学期唯一规则：${schedule.uniqueRuleCount} · 按周展开候选条目：${schedule.candidates.size} · 有警告规则：${schedule.warningRuleCount}",
        style = AppType.caption,
        color = colors.ink
    )
    Text(
        "规则警告仅提示数据覆盖差异，不自动改写学校数据。",
        style = AppType.caption,
        color = colors.mut
    )
    if (schedule.collectionWarnings.isNotEmpty()) {
        Text(
            "Session 警告：${schedule.collectionWarnings.joinToString(" · ") { it.name }}",
            style = AppType.caption,
            color = colors.danger
        )
    }

    val groupedRules = snapshot.courseRules
        .groupBy { it.courseCode.orEmpty() to (it.courseName?.takeIf(String::isNotBlank) ?: "（课程名称缺失）") }
        .entries
        .sortedWith(compareBy({ it.key.second.lowercase() }, { it.key.first }))
    val weekdayLabels = listOf("一", "二", "三", "四", "五", "六", "日")
    groupedRules.forEach { (identity, rules) ->
        val groupTitle = buildString {
            append(identity.second)
            if (identity.first.isNotBlank()) append(" · ").append(identity.first)
        }
        SectionLabel(groupTitle)
        rules.sortedWith(compareBy<SchoolCourseRule> { it.dayOfWeek }.thenBy { it.startSection ?: Int.MAX_VALUE }
            .thenBy { it.displayLocation.orEmpty() }
        ).forEachIndexed { index, rule ->
            SchoolImportRulePreview(
                rule = rule,
                ruleIndex = index + 1,
                candidates = candidatesByRule[rule.ruleKey].orEmpty(),
                weekdayLabel = weekdayLabels[(rule.dayOfWeek - 1).coerceIn(0, 6)]
            )
            Text(
                "采集来源周：${formatWeekSet(schedule.sourceWeeksByRuleKey[rule.ruleKey].orEmpty())}",
                style = AppType.caption,
                color = colors.mut
            )
            val coverageWarnings = schedule.ruleWarningsByKey[rule.ruleKey].orEmpty()
            if (coverageWarnings.isNotEmpty()) {
                Text(
                    coverageWarnings.joinToString(" · ") { it.name },
                    style = AppType.caption,
                    color = colors.danger
                )
            }
        }
    }
    Text(
        "正式课表数据库尚未修改；完整覆盖后可在下方生成导入计划。",
        style = AppType.caption,
        color = colors.mut
    )
}

@Composable
private fun SchoolImportPreviewPanel(
    snapshot: com.burgess.schedule.school.sync.importpreview.SchoolScheduleSnapshot,
    preview: SchoolImportPreview
) {
    val colors = LocalAppColors.current
    SectionLabel("整学期课程预览诊断")
    Text("SCHEDULE_PARSE_SUCCESS · SNAPSHOT_PREVIEW_READY", style = AppType.caption, color = colors.ink)
    Text("来源：山东商务职业学院", style = AppType.body, color = colors.ink)
    Text(
        "学年：${snapshot.academicYear ?: "—"} · 学校学期标识：${snapshot.semesterFlag ?: "—"}",
        style = AppType.caption,
        color = colors.ink
    )
    Text(
        "本次响应周：${snapshot.currentWeek ?: "—"} · 最大周：${snapshot.rawMaxWeek ?: "—"} · 学校最大节次：${snapshot.rawMaxSection ?: "—"}",
        style = AppType.caption,
        color = colors.ink
    )
    Text(
        "周起止日期：${snapshot.weekStartDateText ?: "—"} ~ ${snapshot.weekEndDateText ?: "—"}",
        style = AppType.caption,
        color = colors.mut
    )
    Text(
        "规则：${preview.summary.rawRuleCount} · 畸形项：${preview.summary.malformedRuleCount} · 精确重复：${preview.summary.exactDuplicateCount} · 去重后：${preview.summary.uniqueRuleCount}",
        style = AppType.caption,
        color = colors.ink
    )
    Text(
        "可映射规则：${preview.summary.importableRuleCount} · 按周展开可导入条目：${preview.summary.importableCount}",
        style = AppType.caption,
        color = colors.ink
    )
    Text(
        "有警告：${preview.summary.warningRuleCount} 条规则 · 快照警告：${preview.summary.snapshotWarningCount}",
        style = AppType.caption,
        color = if (preview.summary.warningRuleCount + preview.summary.snapshotWarningCount > 0) colors.mut else colors.ink
    )
    Text(
        "当前预览基于本次学校课表响应，不代表已完成整学期完整性校验。",
        style = AppType.caption,
        color = colors.mut
    )

    val candidatesByRule = preview.candidates.groupBy { it.sourceRuleKey }
    val weekdayLabels = listOf("一", "二", "三", "四", "五", "六", "日")
    snapshot.courseRules
        .groupBy { it.courseName?.takeIf(String::isNotBlank) ?: "（课程名称缺失）" }
        .forEach { (courseName, rules) ->
            SectionLabel(courseName)
            rules.forEachIndexed { index, rule ->
                SchoolImportRulePreview(
                    rule = rule,
                    ruleIndex = index + 1,
                    candidates = candidatesByRule[rule.ruleKey].orEmpty(),
                    weekdayLabel = weekdayLabels[(rule.dayOfWeek - 1).coerceIn(0, 6)]
                )
            }
        }
    Text("ID、课程颜色、周视图简称：IMPORT_DEFAULT（未生成正式值）", style = AppType.caption, color = colors.mut)
    Text("单周预览不会直接写入正式数据库；请完成整学期采集后再准备导入。", style = AppType.caption, color = colors.mut)
}

@Composable
private fun SchoolImportPlanPanel(
    plan: SchoolImportPlan?,
    preparing: Boolean,
    writing: Boolean,
    confirmVisible: Boolean,
    result: SchoolImportExecutionResult?,
    onPrepare: () -> Unit,
    onRequestConfirm: () -> Unit,
    onDismissConfirm: () -> Unit,
    onConfirm: () -> Unit
) {
    val colors = LocalAppColors.current
    SectionLabel("数据库导入计划诊断")
    AppCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
            Text(
                "仅写入 SDBI 学校课程；NON_SDBI 旧课程不会被导入流程修改或删除。学校中已不存在的课程只列出，不自动删除。",
                style = AppType.body,
                color = colors.ink
            )
            PillButton(
                if (preparing) "正在计算导入计划…" else "准备导入",
                onClick = { if (!preparing && !writing) onPrepare() },
                style = PillStyle.Secondary
            )
            plan?.let { current ->
                Text("学校：山东商务职业学院", style = AppType.caption, color = colors.ink)
                Text(
                    "学年：${current.input.academicYear ?: "—"} · 学期：${current.input.semesterFlag ?: "—"}",
                    style = AppType.caption,
                    color = colors.ink
                )
                Text(
                    "覆盖：${current.input.capturedWeeks.size} / ${current.input.maxWeek ?: "—"}",
                    style = AppType.caption,
                    color = colors.ink
                )
                Text(
                    "新增：${current.toInsert.size} · 更新：${current.toUpdate.size} · 无变化：${current.unchanged.size}",
                    style = AppType.body,
                    color = colors.ink
                )
                Text(
                    "学校中已不存在：${current.missingFromSchool.size} · 冲突：${current.conflictCount}",
                    style = AppType.caption,
                    color = if (current.conflictCount > 0) colors.danger else colors.mut
                )
                current.blockReason?.let { reason ->
                    Text(importBlockReasonLabel(reason), style = AppType.caption, color = colors.danger)
                }
                if (current.conflicts.isNotEmpty()) {
                    Text(
                        current.conflicts.joinToString(" · ") { "${it.code.name} (${it.count})" },
                        style = AppType.caption,
                        color = colors.danger
                    )
                }
                if (current.canConfirm) {
                    PillButton(
                        if (writing) "正在导入…" else "确认导入",
                        onClick = { if (!writing && !preparing) onRequestConfirm() },
                        style = PillStyle.Primary
                    )
                }
            }
            when (val state = result) {
                is SchoolImportExecutionResult.Success -> {
                    Text(
                        "SCHOOL_IMPORT_SUCCESS · 新增 ${state.inserted} · 更新 ${state.updated} · 无变化 ${state.unchanged} · 未自动删除 ${state.missingFromSchool}",
                        style = AppType.caption,
                        color = colors.ink
                    )
                    Text("学校课程已写入本地课程表。", style = AppType.body, color = colors.ink)
                }
                SchoolImportExecutionResult.StalePlan -> Text(
                    "IMPORT_PLAN_STALE · 数据库状态已变化，请重新准备并核对计划。",
                    style = AppType.caption,
                    color = colors.danger
                )
                is SchoolImportExecutionResult.Blocked -> Text(
                    "IMPORT_BLOCKED · ${importBlockReasonLabel(state.reason)}",
                    style = AppType.caption,
                    color = colors.danger
                )
                is SchoolImportExecutionResult.Failed -> Text(
                    "SCHOOL_IMPORT_FAILED · ${state.exceptionType} · 数据库事务已回滚。",
                    style = AppType.caption,
                    color = colors.danger
                )
                null -> Unit
            }
            Text(
                "导入后可从此页返回，再打开今日页或周视图检查课程。",
                style = AppType.caption,
                color = colors.mut
            )
        }
    }

    if (confirmVisible) {
        AlertDialog(
            onDismissRequest = onDismissConfirm,
            title = { Text("确认导入学校课表？") },
            text = { Text("将向本地课程表写入学校课程。现有本地课程不会被删除。") },
            confirmButton = {
                TextButton(onClick = onConfirm) { Text("确认导入") }
            },
            dismissButton = {
                TextButton(onClick = onDismissConfirm) { Text("取消") }
            }
        )
    }
}

private fun importBlockReasonLabel(reason: SchoolImportBlockReason): String = when (reason) {
    SchoolImportBlockReason.INCOMPLETE_SEMESTER -> "IMPORT_BLOCKED_INCOMPLETE_SEMESTER · 必须完整采集全部周次。"
    SchoolImportBlockReason.MISSING_SEMESTER_METADATA -> "导入已阻止：学年或学期标识缺失。"
    SchoolImportBlockReason.FATAL_CONFLICTS -> "导入已阻止：先处理身份、规则或数据库冲突。"
}

@Composable
private fun SchoolImportRulePreview(
    rule: SchoolCourseRule,
    ruleIndex: Int,
    candidates: List<com.burgess.schedule.school.sync.importpreview.SchoolImportCandidate>,
    weekdayLabel: String
) {
    val colors = LocalAppColors.current
    val sectionLabel = when {
        rule.startSection == null || rule.endSection == null -> rule.sectionText?.takeIf(String::isNotBlank) ?: "节次未解析"
        rule.startSection == rule.endSection -> "${rule.startSection}节"
        else -> "${rule.startSection}-${rule.endSection}节"
    }
    Text(
        "规则 $ruleIndex · 周$weekdayLabel · $sectionLabel",
        style = AppType.body,
        color = colors.ink
    )
    Text(
        "教师：${rule.teacher?.takeIf(String::isNotBlank) ?: "未提供"}",
        style = AppType.caption,
        color = colors.mut
    )
    Text(
        "地点：${rule.displayLocation ?: "未提供"}",
        style = AppType.caption,
        color = colors.mut
    )
    val parsedWeeks = formatPreviewWeeks(rule.weeks).ifBlank { "未解析" }
    val rawWeekSuffix = if (
        rule.weekParseStatus == com.burgess.schedule.school.sync.importpreview.RuleParseStatus.SUCCESS
    ) {
        ""
    } else {
        " · 原文：${rule.rawWeekText?.ifBlank { "未提供" } ?: "未提供"}"
    }
    Text(
        "周次：$parsedWeeks$rawWeekSuffix",
        style = AppType.caption,
        color = colors.ink
    )
    Text(
        "映射：${candidates.count { it.importable }} 条单周课程记录",
        style = AppType.caption,
        color = colors.ink
    )
    val warningLabels = (rule.warnings + candidates.flatMap { it.warnings })
        .filterNot { it == SchoolParseWarning.IMPORT_DEFAULT }
        .distinct()
        .map(::schoolWarningLabel)
    if (warningLabels.isNotEmpty()) {
        Text(
            warningLabels.joinToString(" · "),
            style = AppType.caption,
            color = colors.mut
        )
    }
    if (candidates.any { !it.importable }) {
        Text("此规则未计入可导入条目，请核对节次、课程名或周次。", style = AppType.caption, color = colors.mut)
    }
}

private fun formatPreviewWeeks(weeks: List<Int>): String {
    if (weeks.isEmpty()) return ""
    val ranges = mutableListOf<String>()
    var start = weeks.first()
    var end = start
    weeks.drop(1).forEach { week ->
        if (week == end + 1) {
            end = week
        } else {
            ranges += if (start == end) "$start" else "$start-$end"
            start = week
            end = week
        }
    }
    ranges += if (start == end) "$start" else "$start-$end"
    return "第" + ranges.joinToString("、") + "周"
}

private fun schoolWarningLabel(warning: SchoolParseWarning): String = when (warning) {
    SchoolParseWarning.MISSING_COURSE_NAME -> "课程名缺失"
    SchoolParseWarning.MISSING_TEACHER -> "教师缺失"
    SchoolParseWarning.MISSING_LOCATION -> "地点缺失"
    SchoolParseWarning.SECTION_PARSE_FAILED -> "节次无法解析"
    SchoolParseWarning.WEEK_PARSE_FAILED -> "周次无法完整解析"
    SchoolParseWarning.SECTION_MISMATCH -> "jcxx 与 jcdm 不一致"
    SchoolParseWarning.WEEK_OUT_OF_RANGE -> "周次超出 maxzc"
    SchoolParseWarning.ODD_EVEN_CONFLICT -> "单双周标记与 skzs 冲突"
    SchoolParseWarning.DATE_PARSE_WARNING -> "日期格式异常"
    SchoolParseWarning.NON_OBJECT_RULE -> "存在非课程对象项"
    SchoolParseWarning.SECTION_OUT_OF_APP_RANGE -> "超出 App 当前 8 节范围"
    SchoolParseWarning.IMPORT_DEFAULT -> "使用预览默认值"
}
