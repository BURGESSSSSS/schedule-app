package com.burgess.schedule.ui.schoolsync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.burgess.schedule.data.CourseDatabase
import com.burgess.schedule.school.sync.importpreview.MergedSemesterSchedule
import com.burgess.schedule.school.sync.importpreview.SchoolImportBlockReason
import com.burgess.schedule.school.sync.importpreview.SchoolImportExecutionResult
import com.burgess.schedule.school.sync.importpreview.SchoolImportMapper
import com.burgess.schedule.school.sync.importpreview.SchoolImportPlan
import com.burgess.schedule.school.sync.importpreview.SchoolImportRepository
import com.burgess.schedule.school.sync.importpreview.SemesterCollectionSession
import com.burgess.schedule.school.sync.importpreview.SemesterCollectionState
import com.burgess.schedule.school.sync.importpreview.SemesterCollectionStore
import com.burgess.schedule.school.sync.importpreview.SemesterCollectionWarning
import com.burgess.schedule.school.sync.importpreview.SemesterSessionLoadResult
import com.burgess.schedule.school.sync.importpreview.SemesterSnapshotCaptureResult
import com.burgess.schedule.school.sync.importpreview.formatWeekSet
import com.burgess.schedule.school.sync.importpreview.mergeSemesterSession
import com.burgess.schedule.school.sync.importpreview.recordSnapshot
import com.burgess.schedule.school.sync.localadb.CourseBodyState
import com.burgess.schedule.school.sync.localadb.CourseCaptureSnapshot
import com.burgess.schedule.school.sync.localadb.CdpFrameTreeProbeResult
import com.burgess.schedule.school.sync.localadb.CdpNetworkObserver
import com.burgess.schedule.school.sync.localadb.CdpNetworkObserverResult
import com.burgess.schedule.school.sync.localadb.CdpNetworkObserverState
import com.burgess.schedule.school.sync.localadb.DevToolsCdpClient
import com.burgess.schedule.school.sync.localadb.DevToolsCdpConnectResult
import com.burgess.schedule.school.sync.localadb.DevToolsDiscoveryState
import com.burgess.schedule.school.sync.localadb.DevToolsTargetDiscoveryResult
import com.burgess.schedule.school.sync.localadb.LocalAdbClient
import com.burgess.schedule.school.sync.localadb.LocalAdbReadIssue
import com.burgess.schedule.school.sync.localadb.LocalAdbReadOnlyCommand
import com.burgess.schedule.school.sync.localadb.SCHOOL_HOST
import com.burgess.schedule.school.sync.localadb.SCHOOL_TITLE
import com.burgess.schedule.school.sync.localadb.SchoolTargetMatch
import com.burgess.schedule.school.sync.localadb.WEWORK_PACKAGE
import com.burgess.schedule.school.sync.localadb.discoverDevToolsTargets
import com.burgess.schedule.school.sync.localadb.evaluateLocalAdbUidCheck
import com.burgess.schedule.school.sync.localadb.findDevToolsSocketCandidates
import com.burgess.schedule.school.sync.localadb.parseNamedUnixSockets
import com.burgess.schedule.school.sync.localadb.parseWeWorkProcessList
import com.burgess.schedule.school.sync.localadb.requestMainFrameUrl
import com.burgess.schedule.ui.components.AppCard
import com.burgess.schedule.ui.components.PillButton
import com.burgess.schedule.ui.components.PillStyle
import com.burgess.schedule.ui.components.SectionLabel
import com.burgess.schedule.ui.components.SubPageScaffold
import com.burgess.schedule.ui.theme.AppSpace
import com.burgess.schedule.ui.theme.AppType
import com.burgess.schedule.ui.theme.LocalAppColors
import io.github.muntashirakon.adb.AdbStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val XWEB_INSPECTOR_ADDRESS = "http://debugxweb.qq.com/?inspector=true"

@Composable
internal fun SchoolSyncScreen(
    onBack: () -> Unit,
    onViewToday: () -> Unit,
    onViewWeek: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current.applicationContext
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val adb = remember(context) { LocalAdbClient(context) }
    val sessionStore = remember(context) { SemesterCollectionStore(context) }
    val importRepository = remember(context) { SchoolImportRepository(CourseDatabase.getInstance(context)) }
    val storeMutex = remember(sessionStore) { Mutex() }

    var pairingPort by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var connectionPort by remember { mutableStateOf("") }
    var connectedPort by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf("") }
    var pairingComplete by remember { mutableStateOf(false) }
    var uidVerified by remember { mutableStateOf(false) }
    var productError by remember { mutableStateOf<String?>(null) }
    var discovery by remember { mutableStateOf<DevToolsTargetDiscoveryResult?>(null) }
    var discoveredSchoolPage by remember { mutableStateOf(false) }
    var collectionSession by remember { mutableStateOf<SemesterCollectionSession?>(null) }
    var sessionReady by remember { mutableStateOf(false) }
    var collectionJob by remember { mutableStateOf<Job?>(null) }
    var collectionStatus by remember { mutableStateOf("尚未开始") }
    var collectionError by remember { mutableStateOf<String?>(null) }
    var sessionWriteError by remember { mutableStateOf(false) }
    var foreground by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) }
    var mergedSchedule by remember { mutableStateOf<MergedSemesterSchedule?>(null) }
    var importPlan by remember { mutableStateOf<SchoolImportPlan?>(null) }
    var importPreparing by remember { mutableStateOf(false) }
    var importWriting by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<SchoolImportExecutionResult?>(null) }
    var capturedRequestCount by remember { mutableStateOf(0) }

    DisposableEffect(adb) {
        onDispose { adb.close() }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> foreground = true
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> foreground = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(sessionStore) {
        when (val loaded = sessionStore.load()) {
            SemesterSessionLoadResult.Empty -> Unit
            is SemesterSessionLoadResult.Invalid -> sessionWriteError = true
            is SemesterSessionLoadResult.Loaded -> {
                collectionSession = loaded.session
                collectionStatus = when (loaded.session.sessionState) {
                    SemesterCollectionState.CDP_SESSION_LOST -> "同步已暂停，已获取的数据已保留。"
                    SemesterCollectionState.SEMESTER_COVERAGE_COMPLETE -> "整学期课表已获取完成。"
                    else -> "已恢复上次保存的采集进度。"
                }
            }
        }
        sessionReady = true
    }

    suspend fun persistSession(session: SemesterCollectionSession) {
        storeMutex.withLock { withContext(Dispatchers.IO) { sessionStore.save(session) } }
    }

    suspend fun persistSessionSafely(session: SemesterCollectionSession) {
        try {
            persistSession(session)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            sessionWriteError = true
        }
    }

    fun pairDevice() {
        if (busy) return
        val port = pairingPort.toIntOrNull()
        val code = pairingCode
        if (port !in 1..65535 || code.length != 6 || code.any { !it.isDigit() }) {
            productError = "请填写系统配对页面显示的端口和 6 位配对码。"
            return
        }
        pairingCode = ""
        productError = null
        busy = true
        operation = "pair"
        scope.launch {
            try {
                pairingComplete = adb.pair(port!!, code)
                productError = if (pairingComplete) null else "配对未完成，请检查系统配对窗口中的信息后重试。"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                pairingComplete = false
                productError = "无线调试配对失败，请确认系统无线调试已开启后重试。"
            } finally {
                busy = false
                operation = ""
            }
        }
    }

    fun connectAndVerify() {
        if (busy) return
        val port = connectionPort.toIntOrNull()
        if (port !in 1..65535) {
            productError = "请填写系统无线调试页面当前显示的连接端口。"
            return
        }
        productError = null
        busy = true
        operation = "connect"
        uidVerified = false
        scope.launch {
            try {
                if (!adb.connect(port!!)) throw IOException("Wireless debugging connection failed")
                val id = adb.executeReadOnly(LocalAdbReadOnlyCommand.ID)
                val checked = evaluateLocalAdbUidCheck(
                    id.output.takeIf { id.commandCompleted && id.issue == LocalAdbReadIssue.NONE }.orEmpty(),
                    id.issue,
                    id.failureSummary
                )
                if (!checked.uidVerified) {
                    connectedPort = null
                    productError = "无线调试连接异常，请确认连接端口正确后重新连接。"
                } else {
                    connectedPort = port
                    uidVerified = true
                    productError = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                connectedPort = null
                uidVerified = false
                productError = "无线调试连接失败，请重新检查系统无线调试端口。"
            } finally {
                busy = false
                operation = ""
            }
        }
    }

    fun copyInspectorAddress() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("企业微信调试地址", XWEB_INSPECTOR_ADDRESS))
        productError = "调试地址已复制。"
    }

    fun detectSchoolPage() {
        if (busy) return
        val port = connectionPort.toIntOrNull()
        if (!uidVerified || port == null || connectedPort != port) {
            productError = "请先连接无线调试。"
            return
        }
        busy = true
        operation = "detect"
        productError = null
        discoveredSchoolPage = false
        discovery = null
        scope.launch {
            try {
                if (!adb.connect(port)) throw IOException("Wireless debugging connection failed")
                val unix = adb.executeReadOnly(LocalAdbReadOnlyCommand.PROC_UNIX_READ)
                if (unix.issue != LocalAdbReadIssue.NONE || !unix.commandCompleted || unix.exitCode != 0) {
                    productError = "读取企业微信课表状态失败，请稍后重试。"
                    return@launch
                }
                val socketRead = parseNamedUnixSockets(unix.output, unix.exitCode)
                if (!socketRead.successful) {
                    productError = "暂时无法检测课表页面，请确认无线调试连接正常。"
                    return@launch
                }
                val processesOutput = adb.executeReadOnly(LocalAdbReadOnlyCommand.PROCESS_QUERY)
                val processes = if (processesOutput.issue == LocalAdbReadIssue.NONE &&
                    processesOutput.commandCompleted && processesOutput.exitCode == 0
                ) parseWeWorkProcessList(processesOutput.output, processesOutput.exitCode).orEmpty() else emptyList()
                val candidates = findDevToolsSocketCandidates(socketRead.sockets)
                val result = discoverDevToolsTargets(adb, candidates, processes) { }
                discovery = result
                discoveredSchoolPage = result.finalState == DevToolsDiscoveryState.SCHOOL_TARGET_FOUND
                productError = if (discoveredSchoolPage) null else
                    "暂未找到课表页面，请确认企业微信中的“我的课表”保持打开后重试。"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                productError = "检测课表页面失败，请确认无线调试已连接且企业微信课表仍在打开。"
            } finally {
                busy = false
                operation = ""
            }
        }
    }

    fun prepareImport(schedule: MergedSemesterSchedule) {
        if (importPreparing || importWriting || !schedule.coverageComplete) return
        importPreparing = true
        importPlan = null
        importResult = null
        scope.launch {
            try {
                importPlan = importRepository.prepare(SchoolImportMapper.prepare(schedule))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                importResult = SchoolImportExecutionResult.Failed("ImportPlanException")
            } finally {
                importPreparing = false
            }
        }
    }

    fun confirmImportNow() {
        val plan = importPlan ?: return
        if (!plan.canConfirm || importWriting) return
        confirmImport = false
        importWriting = true
        scope.launch {
            try {
                importResult = importRepository.import(plan)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                importWriting = false
            }
        }
    }

    fun createPreview() {
        val session = collectionSession ?: return
        if (!session.coverageComplete) return
        val merged = mergeSemesterSession(session)
        mergedSchedule = merged
        importPlan = null
        importResult = null
        prepareImport(merged)
    }

    fun stopCollection() {
        val job = collectionJob ?: return
        collectionStatus = "正在暂停同步…"
        collectionSession = collectionSession?.withState(SemesterCollectionState.SEMESTER_COLLECTION_STOPPED)
        job.cancel()
    }

    fun startCollection() {
        if (busy || collectionJob != null || !sessionReady) return
        val port = connectionPort.toIntOrNull()
        if (!uidVerified || port == null || connectedPort != port) {
            collectionError = "请先连接无线调试。"
            return
        }
        val found = discovery
        val endpoint = found?.schoolCdpEndpoint
        val target = endpoint?.let { selected -> found.targets.firstOrNull { candidate ->
            candidate.id == selected.targetId && candidate.socketName == selected.socketName &&
                candidate.type.equals("page", true) && candidate.title == SCHOOL_TITLE &&
                candidate.containerName == WEWORK_PACKAGE &&
                candidate.schoolMatch == SchoolTargetMatch.SCHOOL_TARGET_FOUND
        } }
        if (!discoveredSchoolPage || endpoint == null || target == null) {
            collectionError = "请先检测到企业微信中的“我的课表”。"
            return
        }

        val initial = (collectionSession ?: SemesterCollectionSession.start())
            .withState(SemesterCollectionState.SEMESTER_COLLECTION_RUNNING)
        collectionSession = initial
        collectionError = null
        sessionWriteError = false
        mergedSchedule = null
        importPlan = null
        importResult = null
        collectionStatus = "正在连接课表…"

        lateinit var job: Job
        job = scope.launch {
            val channel = Channel<CourseCaptureSnapshot>(Channel.UNLIMITED)
            val handledIds = linkedSetOf<String>()
            var processor: Job? = null
            var openedStream: AdbStream? = null
            var cdp: DevToolsCdpClient? = null
            var observerStarted = false
            try {
                persistSession(initial)
                processor = scope.launch {
                    for (snapshot in channel) {
                        capturedRequestCount = snapshot.schoolRequestCount
                        val confirmed = snapshot.recentRequests.filter {
                            it.bodyState == CourseBodyState.COURSE_JSON_CONFIRMED && it.scheduleSnapshot != null
                        }
                        confirmed.forEach { request ->
                            if (!handledIds.add(request.requestId)) return@forEach
                            val payload = request.scheduleSnapshot ?: return@forEach
                            val current = collectionSession ?: return@forEach
                            when (val result = current.recordSnapshot(payload)) {
                                is SemesterSnapshotCaptureResult.Accepted -> {
                                    val updated = if (!foreground) result.session.markBackgroundCdpSurvived() else result.session
                                    collectionSession = updated
                                    mergedSchedule = null
                                    importPlan = null
                                    importResult = null
                                    collectionStatus = if (updated.coverageComplete) {
                                        "整学期课表已获取完成。"
                                    } else {
                                        "已获取 ${updated.capturedWeeks.size} / ${updated.maxWeek ?: "—"} 周"
                                    }
                                    collectionError = null
                                    persistSessionSafely(updated)
                                    if (updated.coverageComplete) job.cancel()
                                }
                                is SemesterSnapshotCaptureResult.Rejected -> {
                                    collectionSession = result.session
                                    collectionStatus = "该周数据未计入进度。"
                                    collectionError = when (result.warning) {
                                        SemesterCollectionWarning.SEMESTER_METADATA_MISMATCH -> "该页返回的学年或学期与当前采集不一致，已忽略。"
                                        SemesterCollectionWarning.MAX_WEEK_MISMATCH -> "该页返回的总周数与当前采集不一致，已忽略。"
                                        SemesterCollectionWarning.INVALID_CAPTURE_WEEK -> "学校返回的周次信息无效，未计入进度。"
                                        SemesterCollectionWarning.INVALID_SEMESTER_METADATA -> "学校返回的学期信息不完整，未计入进度。"
                                        SemesterCollectionWarning.CDP_SESSION_LOST -> "连接已中断，已获取的数据已保留。"
                                    }
                                    persistSessionSafely(result.session)
                                }
                            }
                        }
                    }
                }
                if (!adb.connect(port)) throw IOException("Connection failed")
                openedStream = adb.openDevToolsSocket(endpoint.socketName)
                when (val connection = DevToolsCdpClient.connect(openedStream, endpoint.rawWebSocketUrl)) {
                    is DevToolsCdpConnectResult.Failed -> {
                        collectionSession = collectionSession?.markCdpDisconnected()
                        collectionError = "连接课表已中断，已获取的数据会保留。请重新连接后继续。"
                        collectionStatus = "同步已暂停"
                    }
                    is DevToolsCdpConnectResult.Connected -> {
                        cdp = connection.client
                        when (val frame = requestMainFrameUrl(connection.client)) {
                            is CdpFrameTreeProbeResult.Success -> {
                                val url = frame.mainFrameUrl
                                if (!url.scheme.equals("https", true) || !url.host.equals(SCHOOL_HOST, true)) {
                                    collectionError = "当前页面不是学校课表页面，请返回企业微信课表后重试。"
                                    collectionStatus = "等待课表页面"
                                    collectionSession = collectionSession?.withState(SemesterCollectionState.SEMESTER_COLLECTION_STOPPED)
                                } else {
                                    observerStarted = true
                                    collectionStatus = "正在采集。请切回企业微信，依次切换尚未获取的周次。"
                                    val observer = CdpNetworkObserver(
                                        client = connection.client,
                                        onState = { state ->
                                            if (state == CdpNetworkObserverState.CDP_DISCONNECTED) {
                                                collectionSession = collectionSession?.markCdpDisconnected()
                                                collectionStatus = "同步已暂停，已获取的数据已保留。"
                                            }
                                        },
                                        onSnapshot = { snapshot -> channel.trySend(snapshot) }
                                    )
                                    when (val result = observer.observe()) {
                                        CdpNetworkObserverResult.Stopped -> Unit
                                        is CdpNetworkObserverResult.Disconnected -> {
                                            collectionSession = collectionSession?.markCdpDisconnected()
                                            collectionStatus = "同步已暂停，已获取的数据已保留。"
                                            collectionError = "连接已中断，请重新连接并继续。"
                                        }
                                        is CdpNetworkObserverResult.NetworkEnableFailed -> {
                                            collectionSession = collectionSession?.withState(SemesterCollectionState.SEMESTER_COLLECTION_STOPPED)
                                            collectionStatus = "暂时无法开始采集"
                                            collectionError = "连接学校课表失败，请重新检测页面后重试。"
                                        }
                                    }
                                }
                            }
                            is CdpFrameTreeProbeResult.CommandFailed,
                            CdpFrameTreeProbeResult.MainFrameUrlMissing -> {
                                collectionSession = collectionSession?.withState(SemesterCollectionState.SEMESTER_COLLECTION_STOPPED)
                                collectionStatus = "等待课表页面"
                                collectionError = "请确认企业微信的“我的课表”仍在前台，再重新连接。"
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                collectionSession = if (observerStarted) collectionSession?.markCdpDisconnected()
                    else collectionSession?.withState(SemesterCollectionState.SEMESTER_COLLECTION_STOPPED)
                collectionStatus = if (observerStarted) "同步已暂停，已获取的数据已保留。" else "暂时无法开始采集"
                collectionError = if (observerStarted) "连接已中断，请重新连接并继续。" else "连接课表失败，请重新检测页面后重试。"
            } finally {
                channel.close()
                withContext(NonCancellable) { processor?.join() }
                runCatching { cdp?.close() }
                if (cdp == null) runCatching { openedStream?.close() }
                if (collectionJob === job) collectionJob = null
                collectionSession?.let { finalSession ->
                    if (finalSession.sessionState != SemesterCollectionState.SEMESTER_COLLECTION_RUNNING) {
                        withContext(NonCancellable) { persistSessionSafely(finalSession) }
                    }
                }
            }
        }
        collectionJob = job
    }

    SubPageScaffold("学校课表同步", onBack) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = AppSpace.page),
            verticalArrangement = Arrangement.spacedBy(AppSpace.gap)
        ) {
            Text("从企业微信教务系统读取你的学校课表并导入本地课程表。", style = AppType.body, color = colors.ink)

            SectionLabel("连接设备")
            AppCard(Modifier.fillMaxWidth()) {
                Text("无线调试：${if (uidVerified) "已连接" else "未连接"}", style = AppType.body, color = colors.ink)
                if (!uidVerified) {
                    Text("请打开系统设置 → 开发者选项 → 无线调试 → 使用配对码配对。", style = AppType.caption, color = colors.mut)
                    OutlinedTextField(
                        value = pairingPort,
                        onValueChange = { pairingPort = it.filter(Char::isDigit).take(5) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("系统显示的配对端口") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("6 位配对码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    PillButton(if (busy && operation == "pair") "正在配对…" else "连接无线调试", ::pairDevice)
                    if (pairingComplete) Text("配对成功。请返回无线调试主页面填写连接端口。", style = AppType.caption, color = colors.ink)
                    OutlinedTextField(
                        value = connectionPort,
                        onValueChange = { connectionPort = it.filter(Char::isDigit).take(5) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("系统显示的连接端口") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    PillButton(if (busy && operation == "connect") "正在连接…" else "验证连接", ::connectAndVerify)
                } else {
                    Text("设备已连接。配对信息只用于本次操作。", style = AppType.caption, color = colors.mut)
                }
            }

            SectionLabel("打开企业微信课表")
            AppCard(Modifier.fillMaxWidth()) {
                Text("企业微信课表：${if (discoveredSchoolPage) "已找到" else "等待打开"}", style = AppType.body, color = colors.ink)
                Text("首次使用需在企业微信中开启 XWeb 调试，然后打开“工作台 → 教务系统 → 我的课表”。", style = AppType.caption, color = colors.mut)
                PillButton("复制调试地址", ::copyInspectorAddress, PillStyle.Secondary, small = true)
                if (uidVerified) {
                    PillButton(if (busy && operation == "detect") "正在检测…" else "检测课表页面", ::detectSchoolPage)
                }
                if (discoveredSchoolPage) Text("已找到“我的课表”。", style = AppType.body, color = colors.ink)
            }

            if (discoveredSchoolPage || collectionSession != null) {
                SectionLabel("采集整学期课表")
                AppCard(Modifier.fillMaxWidth()) {
                    val session = collectionSession
                    val maxWeek = session?.maxWeek
                    Text(
                        if (session == null || maxWeek == null) "已获取：${session?.capturedWeeks?.size ?: 0} / — 周"
                        else "已获取：${session.capturedWeeks.size} / $maxWeek 周",
                        style = AppType.body,
                        color = colors.ink
                    )
                    if (session?.capturedWeeks?.isNotEmpty() == true) {
                        Text("已获取第 ${formatWeekSet(session.capturedWeeks)} 周", style = AppType.caption, color = colors.mut)
                        if (session.missingWeeks.isNotEmpty()) {
                            Text("还需：${formatWeekSet(session.missingWeeks)}", style = AppType.caption, color = colors.mut)
                        }
                    }
                    Text(collectionStatus, style = AppType.caption, color = colors.ink)
                    if (capturedRequestCount > 0) Text("已识别课表响应：$capturedRequestCount", style = AppType.caption, color = colors.mut)
                    if (sessionWriteError) Text("本机保存进度失败，请检查设备存储空间。", style = AppType.caption, color = colors.mut)
                    collectionError?.let { Text(it, style = AppType.caption, color = MaterialTheme.colorScheme.error) }
                    if (!sessionReady) {
                        Text("正在恢复已保存的进度…", style = AppType.caption, color = colors.mut)
                    } else if (collectionJob != null) {
                        PillButton("暂停同步", ::stopCollection, PillStyle.Secondary)
                    } else if (session?.coverageComplete == true) {
                        PillButton("查看课程预览", ::createPreview)
                        PillButton("重新连接并继续", ::startCollection, PillStyle.Secondary, small = true)
                    } else {
                        PillButton(
                            if (session?.capturedWeeks.isNullOrEmpty()) "开始采集" else "重新连接并继续",
                            ::startCollection
                        )
                    }
                    if (collectionJob != null || session?.coverageComplete != true) {
                        Text("切回企业微信，手动切换尚未获取的周次。连接中断后可继续，已保存的数据不会丢失。", style = AppType.caption, color = colors.mut)
                    }
                }
            }

            mergedSchedule?.let { schedule ->
                SectionLabel("课程预览")
                AppCard(Modifier.fillMaxWidth()) {
                    Text("课程规则 ${schedule.uniqueRuleCount} 条 · 按周课次 ${schedule.candidates.size} 条", style = AppType.body, color = colors.ink)
                    schedule.snapshot.courseRules.sortedWith(compareBy({ it.dayOfWeek }, { it.startSection ?: Int.MAX_VALUE }, { it.courseName.orEmpty() })).forEach { rule ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(rule.courseName.orEmpty(), style = AppType.body, color = colors.ink)
                            val day = listOf("一", "二", "三", "四", "五", "六", "日").getOrElse(rule.dayOfWeek - 1) { "?" }
                            Text(
                                listOfNotNull(
                                    rule.teacher?.takeIf(String::isNotBlank),
                                    "周$day · ${rule.startSection ?: "?"}-${rule.endSection ?: "?"} 节",
                                    rule.displayLocation?.takeIf(String::isNotBlank),
                                    "第 ${formatWeekSet(rule.weeks.toSet())} 周"
                                ).joinToString(" · "),
                                style = AppType.caption,
                                color = colors.mut
                            )
                        }
                    }
                }
                importPlan?.let { plan ->
                    SectionLabel("导入计划")
                    AppCard(Modifier.fillMaxWidth()) {
                        Text("新增课程：${plan.toInsert.size}", style = AppType.body, color = colors.ink)
                        Text("更新课程：${plan.toUpdate.size}", style = AppType.body, color = colors.ink)
                        Text("无变化：${plan.unchanged.size}", style = AppType.body, color = colors.ink)
                        if (plan.missingFromSchool.isNotEmpty()) {
                            Text("学校课表中已不存在：${plan.missingFromSchool.size}（不会自动删除）", style = AppType.caption, color = colors.mut)
                        }
                        if (plan.conflicts.isNotEmpty()) {
                            Text("发现课程冲突，请检查后重试。", style = AppType.body, color = MaterialTheme.colorScheme.error)
                        }
                        if (!plan.canConfirm && plan.conflicts.isEmpty()) {
                            Text(importBlockLabel(plan.blockReason), style = AppType.caption, color = MaterialTheme.colorScheme.error)
                        }
                        if (importPreparing) Text("正在检查本地课程…", style = AppType.caption, color = colors.mut)
                        if (importWriting) Text("正在导入…", style = AppType.caption, color = colors.mut)
                        if (plan.canConfirm && importResult !is SchoolImportExecutionResult.Success &&
                            importResult !is SchoolImportExecutionResult.StalePlan
                        ) {
                            PillButton("导入课表", { confirmImport = true })
                        }
                        if (importResult is SchoolImportExecutionResult.StalePlan) {
                            PillButton("重新检查导入计划", { mergedSchedule?.let(::prepareImport) }, PillStyle.Secondary)
                        }
                    }
                }
                importResult?.let { result ->
                    when (result) {
                        is SchoolImportExecutionResult.Success -> {
                            SectionLabel("同步完成")
                            AppCard(Modifier.fillMaxWidth()) {
                                Text("课表同步完成", style = AppType.body, color = colors.ink)
                                Text("新增 ${result.inserted} 条 · 更新 ${result.updated} 条 · 无变化 ${result.unchanged} 条", style = AppType.caption, color = colors.mut)
                                Text("原有本地课程不会被删除。", style = AppType.caption, color = colors.mut)
                                Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
                                    PillButton("查看今日课程", onViewToday, PillStyle.Secondary, small = true)
                                    PillButton("查看周视图", onViewWeek, PillStyle.Secondary, small = true)
                                }
                            }
                        }
                        SchoolImportExecutionResult.StalePlan -> Text("课程表有变化，请重新检查导入计划。", style = AppType.caption, color = MaterialTheme.colorScheme.error)
                        is SchoolImportExecutionResult.Blocked -> Text("导入未执行：${importBlockLabel(result.reason)}", style = AppType.caption, color = MaterialTheme.colorScheme.error)
                        is SchoolImportExecutionResult.Failed -> Text("导入失败，请稍后重新检查。", style = AppType.caption, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            productError?.let { Text(it, style = AppType.caption, color = if (it.endsWith("已复制。")) colors.mut else MaterialTheme.colorScheme.error) }
        }
    }

    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("确认导入课表") },
            text = { Text("将把学校课表写入本地课程表。你原有的本地课程不会被删除。") },
            confirmButton = { TextButton(onClick = ::confirmImportNow) { Text("导入课表") } },
            dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("取消") } }
        )
    }
}

private fun importBlockLabel(reason: SchoolImportBlockReason?): String = when (reason) {
    SchoolImportBlockReason.INCOMPLETE_SEMESTER -> "还有部分周次没有获取，请完成整学期采集后再导入。"
    SchoolImportBlockReason.MISSING_SEMESTER_METADATA -> "学校课表信息不完整，暂时无法导入。"
    SchoolImportBlockReason.FATAL_CONFLICTS -> "发现课程冲突，请检查后重试。"
    null -> "暂时无法导入，请重新检查课程计划。"
}
