package com.burgess.schedule

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.platform.LocalDensity
import com.burgess.schedule.ui.components.ListGroup
import com.burgess.schedule.ui.components.ListRow
import com.burgess.schedule.ui.components.SubPageScaffold
import com.burgess.schedule.ui.components.AppSwitch
import com.burgess.schedule.ui.components.BottomSheetContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.semantics.semantics
import com.burgess.schedule.ui.week.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.compose.foundation.Image
import com.burgess.schedule.data.CourseDatabase
import com.burgess.schedule.data.PickupCodeEntity
import com.burgess.schedule.importer.OcrCourseDraft
import com.burgess.schedule.importer.OcrScheduleParser
import com.burgess.schedule.notifications.ReminderScheduler
import com.burgess.schedule.notifications.ReminderStatus
import com.burgess.schedule.notifications.TestNotificationResult
import com.burgess.schedule.data.CourseEntity
import com.burgess.schedule.pickup.CAINIAO_PACKAGE_PAGE_URL
import com.burgess.schedule.pickup.PickupWebViewPanel
import com.burgess.schedule.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.lifecycle.repeatOnLifecycle
import com.burgess.schedule.ui.today.*
import com.burgess.schedule.ui.more.*
import com.burgess.schedule.ui.model.*
import com.burgess.schedule.ui.components.rememberPressScale
import com.burgess.schedule.ui.components.SectionLabel
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.size
import com.burgess.schedule.ui.detail.CourseDetailAction
import com.burgess.schedule.ui.detail.CourseDetailHost
import com.burgess.schedule.ui.detail.rememberCourseDetailController
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal val Ink: Color @Composable get() = LocalAppColors.current.ink
internal val MutedInk: Color @Composable get() = LocalAppColors.current.mut
private val FaintInk: Color @Composable get() = LocalAppColors.current.mut
private val Accent: Color @Composable get() = LocalAppColors.current.ink
private val AccentDeep: Color @Composable get() = LocalAppColors.current.ink
private val Gray50: Color @Composable get() = LocalAppColors.current.bg
private val Gray100: Color @Composable get() = LocalAppColors.current.bg
internal val Hairline: Color @Composable get() = LocalAppColors.current.line
private val CourseSlotFill: Color @Composable get() = LocalAppColors.current.ink.copy(alpha = .035f)
private val CardColor: Color @Composable get() = LocalAppColors.current.card

// 课程用左侧细色条区分，色板保持饱和但不刺眼。
private val courseAccentColors = CoursePalette
@Composable private fun courseFillFor(course: Course): Color = courseTint(courseAccentFor(course)).first
@Composable
internal fun ScheduleApp(
    sharedImageUri: Uri? = null,
    onSharedImageHandled: () -> Unit = {}
) {
    val hostContext = LocalContext.current
    val context = hostContext.applicationContext
    val courseDao = remember {
        CourseDatabase.getInstance(context).courseDao()
    }
    val courseEntities by courseDao.observeAll()
        .collectAsState(initial = null as List<CourseEntity>?)
    val courses = remember(courseEntities) { courseEntities?.map { it.toCourse() }.orEmpty() }
    var designVisible by remember { mutableStateOf(false) }
    var selectedPage by remember { mutableStateOf(0) }
    var expandedCourse by remember { mutableStateOf<Course?>(null) }
    val detailController = rememberCourseDetailController()
    var weekEditing by remember { mutableStateOf(false) }
    var weekOffset by remember { mutableStateOf(0) }
    var editorVisible by remember { mutableStateOf(false) }
    var editingCourse by remember { mutableStateOf<Course?>(null) }
    var editorDay by remember { mutableStateOf(0) }
    var editorPeriod by remember { mutableStateOf(0) }
    var editorEndPeriod by remember { mutableStateOf(0) }
    var deletingCourse by remember { mutableStateOf<Course?>(null) }
    var ocrUri by remember { mutableStateOf<Uri?>(null) }
    var ocrVisible by remember { mutableStateOf(false) }
    var reminderVisible by remember { mutableStateOf(false) }
    var reminderStatus by remember { mutableStateOf(ReminderScheduler.getStatus(context)) }
    var devLogVisible by remember { mutableStateOf(false) }
    var pickupVisible by remember { mutableStateOf(false) }
    var pickupWebUrl by remember { mutableStateOf<String?>(null) }
    var pickupTrackingNumber by remember { mutableStateOf<String?>(null) }
    var quickPickupEntry by remember { mutableStateOf<PickupCodeEntity?>(null) }
    val pickupDao = remember { CourseDatabase.getInstance(context).pickupCodeDao() }
    val pickupCodes by pickupDao.observeAll().collectAsState(initial = emptyList())
    val pendingPickups = pickupCodes.filter { !it.picked }
    var importOptionsVisible by remember { mutableStateOf(false) }
    var importWeekNumber by remember { mutableStateOf(0) }
    var editorWeekNumber by remember { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            ocrUri = uri
            ocrVisible = true
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        reminderStatus = ReminderScheduler.getStatus(context)
        if (granted) {
            ReminderScheduler.rescheduleFromDatabase(context)
        }
        scope.launch {
            snackbarHostState.showSnackbar(if (granted) "通知权限已开启" else "通知权限未开启")
        }
    }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    // Only visible course pages need a minute ticker; overlays and background stop it.
    val scheduleVisible = selectedPage in 0..1 && expandedCourse == null && !designVisible &&
        !editorVisible && !ocrVisible && !reminderVisible && !devLogVisible && !pickupVisible && !importOptionsVisible
    LaunchedEffect(scheduleVisible) {
        if (scheduleVisible) (hostContext as LifecycleOwner).lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                nowMillis = System.currentTimeMillis()
                delay(60_000L - nowMillis % 60_000L)
            }
        }
    }
    val today = remember(nowMillis) {
        Calendar.getInstance().apply { timeInMillis = nowMillis }
    }
    val currentWeekStart = remember(today.timeInMillis) { mondayOfCurrentWeek(today) }
    val selectedWeekStart = remember(currentWeekStart.timeInMillis, weekOffset) {
        currentWeekStart.cloneCalendar().apply {
            add(Calendar.WEEK_OF_YEAR, weekOffset)
        }
    }
    val currentWeekNumber = scheduleWeekNumber(currentWeekStart)
    val selectedWeekNumber = scheduleWeekNumber(selectedWeekStart)
    val focus = remember(courses, currentWeekNumber, today.timeInMillis) {
        findFocusCourse(courses, today, currentWeekNumber)
    }

    fun openImport(targetWeekNumber: Int) {
        importWeekNumber = targetWeekNumber
        importOptionsVisible = true
    }

    fun openPickupPage(targetUrl: String = CAINIAO_PACKAGE_PAGE_URL) {
        pickupWebUrl = targetUrl
        pickupTrackingNumber = null
    }

    fun openTrackingPage(rawNumber: String) {
        val trackingNumber = normalizeTrackingNumber(rawNumber)
        if (!isTrackingNumberCandidate(trackingNumber)) {
            scope.launch { snackbarHostState.showSnackbar("请输入有效的快递单号") }
            return
        }
        // 单号查询也留在校园菜鸟页面内，由页面自己的输入框和查询流程处理。
        pickupTrackingNumber = trackingNumber
        pickupWebUrl = CAINIAO_PACKAGE_PAGE_URL
    }

    fun pasteTrackingNumber(): String {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val pasted = normalizeTrackingNumber(
            clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
                .orEmpty()
        )
        if (pasted.isBlank()) {
            scope.launch { snackbarHostState.showSnackbar("剪贴板没有可用的快递单号") }
        } else if (!isTrackingNumberCandidate(pasted)) {
            scope.launch { snackbarHostState.showSnackbar("剪贴板内容不是有效的快递单号") }
        }
        return pasted
    }

    fun exportPickupCapture() {
        val file = File(context.filesDir, "pickup_capture.log")
        if (!file.exists() || file.length() == 0L) {
            scope.launch { snackbarHostState.showSnackbar("暂无抓包数据，先去包裹页浏览一次") }
            return
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(send, "分享抓包数据")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun copyPickupCode(code: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("取件码", code))
        scope.launch { snackbarHostState.showSnackbar("已复制 $code") }
    }

    fun logoutPickupAccount() {
        pickupWebUrl = null
        pickupTrackingNumber = null
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            context.deleteFile("pickup_capture.log")
            scope.launch { snackbarHostState.showSnackbar("已退出淘宝账号，登录态和抓取缓存已清除") }
        }
    }

    LaunchedEffect(sharedImageUri) {
        sharedImageUri?.let { uri ->
            importWeekNumber = currentWeekNumber
            ocrUri = uri
            ocrVisible = true
            onSharedImageHandled()
        }
    }

    LaunchedEffect(courseEntities) {
        courseEntities?.let { ReminderScheduler.scheduleAll(context, it) }
    }

    LaunchedEffect(reminderVisible) {
        if (reminderVisible) {
            reminderStatus = ReminderScheduler.getStatus(context)
        }
    }
    val lifecycleOwner = hostContext as? LifecycleOwner
    DisposableEffect(lifecycleOwner, reminderVisible) {
        if (lifecycleOwner == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && reminderVisible) {
                    reminderStatus = ReminderScheduler.getStatus(context)
                    ReminderScheduler.rescheduleFromDatabase(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    fun openEditor(
        course: Course? = null,
        dayIndex: Int = 0,
        periodIndex: Int = 0,
        weekNumber: Int = currentWeekNumber
    ) {
        editingCourse = course
        editorDay = course?.dayIndex ?: dayIndex
        editorPeriod = course?.period ?: periodIndex
        editorEndPeriod = course?.endPeriod ?: (periodIndex + 1).coerceAtMost(periods.lastIndex)
        editorWeekNumber = course?.weekNumber ?: weekNumber
        editorVisible = true
    }

    fun clearEditorState() {
        editingCourse = null
        editorDay = -1
        editorPeriod = 0
        editorEndPeriod = 0
        editorVisible = false
    }

    fun deleteCourseWithUndo(removed: Course) {
        scope.launch {
            courseDao.delete(removed.toEntity())
            val result = withTimeoutOrNull(5_000L) {
                snackbarHostState.showSnackbar("已删除", "撤销", duration = SnackbarDuration.Indefinite)
            }
            if (result == SnackbarResult.ActionPerformed) courseDao.upsert(removed.toEntity())
        }
    }

    fun finishCourseDetail(action: CourseDetailAction) {
        val selected = expandedCourse
        when (action) {
            CourseDetailAction.Dismiss -> Unit
            CourseDetailAction.Edit -> selected?.let { openEditor(course = it) }
            CourseDetailAction.Delete -> selected?.let { deleteCourseWithUndo(it) }
        }
        expandedCourse = null
    }

    val copyNextWeek: (Int, List<Course>) -> Unit = { sourceWeekNumber, source ->
                                val targetWeekNumber = sourceWeekNumber + 1
                                val existingKeys = courses
                                    .filter { it.weekNumber == targetWeekNumber }
                                    .mapTo(mutableSetOf()) { courseKey(it) }
                                val copies = source.filter { course ->
                                    val key = courseKey(course)
                                    existingKeys.add(key)
                                }
                                val skipped = source.size - copies.size
                                if (source.isEmpty()) {
                                    scope.launch { snackbarHostState.showSnackbar("本周没有可复制的课程") }
                                } else {
                                    scope.launch {
                                        courseDao.insertAll(
                                            copies.mapIndexed { index, course ->
                                                course.copy(
                                                    id = System.nanoTime() + index,
                                                    weekNumber = targetWeekNumber
                                                ).toEntity()
                                            }
                                        )
                                        snackbarHostState.showSnackbar(
                                            "已复制 ${copies.size} 门课程" +
                                                if (skipped > 0) "，跳过 $skipped 门重复课程" else ""
                                        )
                                    }
                                }
                            }

    CourseDetailHost(
        controller = detailController,
        onAction = ::finishCourseDetail
    ) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (selectedPage == 1 && weekEditing) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().heightIn(min = AppSpace.navSpace).background(CardColor).horizontalScroll(rememberScrollState()).padding(horizontal = AppSpace.weekPage), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpace.gridGap)) {
                    com.burgess.schedule.ui.components.PillButton("复制到下周", { copyNextWeek(selectedWeekNumber, courses.filter { it.weekNumber == selectedWeekNumber }) }, com.burgess.schedule.ui.components.PillStyle.Ghost, true)
                    com.burgess.schedule.ui.components.PillButton("清空本周", {
                        val removed = courses.filter { it.weekNumber == selectedWeekNumber }
                        scope.launch {
                            courseDao.clearWeek(selectedWeekNumber)
                            val result = withTimeoutOrNull(5_000L) { snackbarHostState.showSnackbar("已清空本周", "撤销", duration = SnackbarDuration.Indefinite) }
                            if (result == SnackbarResult.ActionPerformed) courseDao.insertAll(removed.map { it.toEntity() })
                        }
                    }, com.burgess.schedule.ui.components.PillStyle.Ghost, true, danger = true)
                    com.burgess.schedule.ui.components.PillButton("添加课程", { openEditor(weekNumber = selectedWeekNumber) }, small = true)
                }
            } else {
            AppBottomNavigation(
                selectedPage = selectedPage,
                onPageSelected = {
                    weekEditing = false
                    devLogVisible = false
                    pickupVisible = false
                    pickupWebUrl = null
                    pickupTrackingNumber = null
                    selectedPage = it
                }
            )
            }
        },

    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Crossfade(
                    targetState = selectedPage,
                    modifier = Modifier.fillMaxSize(),
                    animationSpec = tween(Motion.TabCrossfadeMs),
                    label = "page transition"
                ) { page ->
                    when (page) {
                        0 -> TodayScreen(
                            today = today,
                            courses = courses,
                            focus = focus,
                            pendingPickups = pendingPickups,
                            onPickupOpen = { quickPickupEntry = it },
                            onPickupCopy = { copyPickupCode(it) },
                            detailController = detailController,
                            onCourseClick = { course -> expandedCourse = course },
                            onImport = { openImport(currentWeekNumber) },
                            onAdd = { openEditor(dayIndex = currentDayIndex(today), periodIndex = 0, weekNumber = currentWeekNumber) }
                        )
                        1 -> WeekScreen(
                            today = today,
                            selectedWeekStart = selectedWeekStart,
                            weekOffset = weekOffset,
                            courses = courses.filter { it.weekNumber == selectedWeekNumber },
                            focus = focus,
                            onImportClick = { weekNumber -> openImport(weekNumber) },
                            onReminderClick = { reminderVisible = true },
                            onPrevious = { weekOffset -= 1 },
                            onToday = { weekOffset = 0 },
                            onNext = { weekOffset += 1 },
                            editing = weekEditing,
                            onToggleEditing = { weekEditing = !weekEditing },
                            onAddCourse = { weekNumber, dayIndex, periodIndex ->
                                openEditor(
                                    dayIndex = dayIndex,
                                    periodIndex = periodIndex,
                                    weekNumber = weekNumber
                                )
                            },
                            onEditCourse = { course -> openEditor(course = course) },
                            onCopyNextWeek = copyNextWeek,
                            detailController = detailController,
                            onCourseClick = { course -> expandedCourse = course }
                        )
                        else -> MoreScreen(
                            today = today,
                            onReminderClick = { reminderVisible = true },
                            onPickupClick = { pickupVisible = true },
                            onDevLogClick = { devLogVisible = true },
                            onDesignClick = { designVisible = true }
                        )
                    }
                }
            AnimatedVisibility(
                visible = devLogVisible,
                enter = fadeIn(tween(220)) + slideInHorizontally(
                    animationSpec = tween(280),
                    initialOffsetX = { it / 10 }
                ),
                exit = fadeOut(tween(180)) + slideOutHorizontally(
                    animationSpec = tween(220),
                    targetOffsetX = { it / 10 }
                )
            ) {
                DevLogScreen(onBack = { devLogVisible = false })
            }
            AnimatedVisibility(
                visible = pickupVisible,
                enter = fadeIn(tween(220)) + slideInHorizontally(
                    animationSpec = tween(280),
                    initialOffsetX = { it / 10 }
                ),
                exit = fadeOut(tween(180)) + slideOutHorizontally(
                    animationSpec = tween(220),
                    targetOffsetX = { it / 10 }
                )
            ) {
                PickupScreen(
                    codes = pickupCodes,
                    onBack = { pickupVisible = false },
                    webViewUrl = pickupWebUrl,
                    trackingNumber = pickupTrackingNumber,
                    onCloseWebView = {
                        pickupWebUrl = null
                        pickupTrackingNumber = null
                    },
                    onOpenPackagePage = { openPickupPage() },
                    onTrackNumber = { openTrackingPage(it) },
                    onPasteNumber = { pasteTrackingNumber() },
                    onTrackingRequestHandled = { pickupTrackingNumber = null },
                    onTrackingFeedback = { message ->
                        scope.launch { snackbarHostState.showSnackbar(message) }
                    },
                    onLogout = {
                        logoutPickupAccount()
                    },
                    onExportCapture = { exportPickupCapture() },
                    onPickupCodes = { entries ->
                        scope.launch(Dispatchers.IO) {
                            entries.forEach { entry ->
                                if (pickupDao.findByCodeAndStation(entry.code, entry.station) == null) {
                                    pickupDao.insertAll(listOf(entry))
                                }
                            }
                        }
                    },
                    onCopy = { copyPickupCode(it.code) },
                    onQuickPickup = { quickPickupEntry = it },
                    onTogglePicked = { entry ->
                        scope.launch {
                            pickupDao.setPicked(entry.id, !entry.picked)
                            val result = withTimeoutOrNull(5_000L) { snackbarHostState.showSnackbar(if (entry.picked) "已标记待取" else "已标记已取件", "撤销", duration = SnackbarDuration.Indefinite) }
                            if (result == SnackbarResult.ActionPerformed) pickupDao.setPicked(entry.id, entry.picked)
                        }
                    }
                )
            }
            quickPickupEntry?.let { entry ->
                QuickPickupCodeOverlay(
                    entry = entry,
                    onCopy = { copyPickupCode(entry.code) },
                    onDismiss = { quickPickupEntry = null }
                )
            }
        }
        }
    }

    }

    if (importOptionsVisible) BottomSheetContainer({ importOptionsVisible = false }) {
        Text("导入课程", style = AppType.sheetTitle, color = Ink)
        ListRow("从课表截图导入", "选择图片，识别后检查并保存", { importOptionsVisible = false; imagePickerLauncher.launch("image/*") })
        SectionLabel("也可从企业微信等应用分享课表截图到课程表。")
    }

    if (designVisible && BuildConfig.DEBUG) {
        Dialog(onDismissRequest = { designVisible = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) { com.burgess.schedule.ui.components.DesignPreview(onClose = { designVisible = false }) }
    }

    if (editorVisible) {
        CourseEditorDialog(
            course = editingCourse,
            initialDay = editorDay,
            initialPeriod = editorPeriod,
            initialEndPeriod = editorEndPeriod,
            initialWeekNumber = editorWeekNumber,
            allCourses = courses,
            onDismissed = { clearEditorState() },
            onSave = { savedCourse ->
                scope.launch {
                    courseDao.upsert(savedCourse.toEntity())
                }
            },
            onDelete = { course ->
                deletingCourse = course
            }
        )
    }

    deletingCourse?.let { course ->
        AlertDialog(
            onDismissRequest = { deletingCourse = null },
            title = { Text("删除课程？") },
            text = { Text("“" + course.name + "”将从课程表中移除，这个操作无法撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            courseDao.delete(course.toEntity())
                        }
                        deletingCourse = null
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingCourse = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (ocrVisible && ocrUri != null) {
        OcrPreviewDialog(
            imageUri = ocrUri!!,
            onDismissed = {
                ocrVisible = false
                ocrUri = null
            },
            onImport = { drafts ->
                val importedCourses = drafts
                    .mapNotNull { draft ->
                        val importedName = normalizeImportedCourseName(draft.name).trim()
                        if (importedName.isBlank()) {
                            null
                        } else {
                            val start = draft.period.coerceIn(0, periods.lastIndex)
                            val end = draft.endPeriod.coerceIn(start, periods.lastIndex)
                            Course(
                                id = 0L,
                                name = importedName,
                                teacher = "",
                                room = draft.detail.ifBlank { defaultImportedRoom(importedName) },
                                dayIndex = draft.dayIndex.coerceIn(0, 6),
                                period = start,
                                endPeriod = end,
                                weekNumber = importWeekNumber,
                                color = CoursePalette.first()
                            )
                        }
                    }
                    .distinctBy(::courseKey)
                    .mapIndexed { index, course ->
                        val newId = System.currentTimeMillis() + index
                        course.copy(id = newId, color = courseAccentFor(newId))
                    }
                if (importedCourses.isEmpty()) {
                    scope.launch { snackbarHostState.showSnackbar("没有可导入的有效课程") }
                } else {
                    scope.launch {
                        courseDao.replaceWeek(importWeekNumber, importedCourses.map { it.toEntity() })
                        snackbarHostState.showSnackbar("已替换当前周并导入 " + importedCourses.size + " 门课程")
                    }
                }
            }
        )
    }

    if (reminderVisible) {
        ReminderDialog(
            status = reminderStatus,
            onDismiss = { reminderVisible = false },
            onToggle = { enabled ->
                ReminderScheduler.setRemindersEnabled(context, enabled)
                reminderStatus = ReminderScheduler.getStatus(context)
                scope.launch {
                    snackbarHostState.showSnackbar(if (enabled) "课程提醒已开启" else "课程提醒已关闭")
                }
            },
            onRequestPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    reminderStatus = ReminderScheduler.getStatus(context)
                    scope.launch { snackbarHostState.showSnackbar("当前系统不需要单独申请通知权限") }
                }
            },
            onRequestExactAlarm = {
                val intent = ReminderScheduler.exactAlarmPermissionIntent(context)
                if (intent == null) {
                    scope.launch { snackbarHostState.showSnackbar("当前系统不需要单独申请精确闹钟权限") }
                } else {
                    try {
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                        scope.launch { snackbarHostState.showSnackbar("无法打开系统提醒权限页面") }
                    }
                }
            },
            onSendTest = {
                val result = ReminderScheduler.sendTestNotification(context)
                reminderStatus = ReminderScheduler.getStatus(context)
                val message = when (result) {
                    TestNotificationResult.SENT -> "测试通知已发送"
                    TestNotificationResult.NOTIFICATION_PERMISSION_REQUIRED -> "通知权限未开启，测试未发送"
                    TestNotificationResult.NOTIFICATIONS_DISABLED -> "系统通知已关闭，测试未发送"
                }
                scope.launch { snackbarHostState.showSnackbar(message) }
            }
        )
    }
}

@Composable
private fun PickupScreen(
    codes: List<PickupCodeEntity>,
    onBack: () -> Unit,
    webViewUrl: String?,
    trackingNumber: String?,
    onCloseWebView: () -> Unit,
    onOpenPackagePage: () -> Unit,
    onTrackNumber: (String) -> Unit,
    onPasteNumber: () -> String,
    onTrackingRequestHandled: () -> Unit,
    onTrackingFeedback: (String) -> Unit,
    onLogout: () -> Unit,
    onExportCapture: () -> Unit,
    onPickupCodes: (List<PickupCodeEntity>) -> Unit,
    onCopy: (PickupCodeEntity) -> Unit,
    onQuickPickup: (PickupCodeEntity) -> Unit,
    onTogglePicked: (PickupCodeEntity) -> Unit
) {
    var adding by remember { mutableStateOf(false) }
    var codeInput by remember { mutableStateOf("") }
    var stationInput by remember { mutableStateOf("") }
    if (adding) BottomSheetContainer({ adding = false }) {
        Text("添加取件码", style = AppType.sheetTitle, color = Ink)
        OutlinedTextField(codeInput, { codeInput = it }, label = { Text("取件码") })
        OutlinedTextField(stationInput, { stationInput = it }, label = { Text("驿站或快递名") })
        com.burgess.schedule.ui.components.PillButton("保存取件码", {
            if (codeInput.isNotBlank()) {
                onPickupCodes(listOf(PickupCodeEntity(code = codeInput.trim(), station = stationInput.trim(), status = "待取", picked = false, createdAt = System.currentTimeMillis())))
                codeInput = ""; stationInput = ""; adding = false
            }
        })
    }
    var trackingInput by remember { mutableStateOf("") }
    val normalizedTrackingNumber = normalizeTrackingNumber(trackingInput)
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Gray50)
            .verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.fillMaxWidth().padding(AppSpace.page), verticalAlignment = Alignment.CenterVertically) {
            com.burgess.schedule.ui.components.CircleIconButton("‹", onBack)
            Spacer(Modifier.weight(1f))
            com.burgess.schedule.ui.components.PillButton("添加", { adding = true }, com.burgess.schedule.ui.components.PillStyle.Secondary, true)
            com.burgess.schedule.ui.components.PillButton("退出账号", onLogout, com.burgess.schedule.ui.components.PillStyle.Ghost, true)
        }
        Text("取件码", Modifier.padding(horizontal = AppSpace.page), style = AppType.title, color = Ink)
        webViewUrl?.let { targetUrl ->
            PickupWebViewPanel(
                targetUrl = targetUrl,
                trackingNumber = trackingNumber,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                onClose = onCloseWebView,
                onTrackingRequestHandled = onTrackingRequestHandled,
                onTrackingFeedback = onTrackingFeedback,
                onLogout = onLogout,
                onPickupCodes = onPickupCodes
            )
        }
        AppCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "查快递",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ink
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "粘贴单号后在校园菜鸟页面内查询，不打开全球物流页。",
                style = MaterialTheme.typography.bodySmall,
                color = MutedInk
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = trackingInput,
                onValueChange = { trackingInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("快递单号") },
                placeholder = { Text("输入或粘贴快递单号") },
                colors = scheduleOutlinedFieldColors()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { trackingInput = onPasteNumber() }) {
                    Text("从剪贴板粘贴")
                }
                Button(
                    onClick = { onTrackNumber(normalizedTrackingNumber) },
                    enabled = isTrackingNumberCandidate(normalizedTrackingNumber)
                ) {
                    Text("查询")
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                onClick = onOpenPackagePage,
                modifier = Modifier.fillMaxWidth()
            ) {
            Text("打开校园菜鸟驿站")
            }
            Text(
                text = "物流信息由菜鸟官方页面提供；取件码以登录后的菜鸟包裹页为准。",
                style = MaterialTheme.typography.labelSmall,
                color = FaintInk
            )
        }
        if (codes.isEmpty()) {
            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "还没有取件码",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "在包裹页登录淘宝账号后，快递到站的取件码会出现在这里；也可以先在包裹页里直接查看。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedInk
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onOpenPackagePage) {
                    Text("打开校园菜鸟驿站")
                }
            }
        } else {
            codes.sortedBy { it.picked }.forEachIndexed { index, entry ->
                if (index == 0 || entry.picked != codes.sortedBy { it.picked }[index - 1].picked) Text(if (entry.picked) "已取件" else "待取件", Modifier.padding(AppSpace.page), style = AppType.caption, color = MutedInk)
                PickupCodeCard(
                    entry = entry,
                    onOpen = { onQuickPickup(entry) },
                    onCopy = { onCopy(entry) },
                    onTogglePicked = { onTogglePicked(entry) }
                )
            }
        }
        TextButton(
            onClick = onOpenPackagePage,
            modifier = Modifier
                .align(Alignment.End)
                .padding(horizontal = 16.dp)
        ) {
            Text("打开完整驿站页面")
        }
        TextButton(
            onClick = onExportCapture,
            modifier = Modifier
                .align(Alignment.End)
                .padding(horizontal = 16.dp)
        ) {
            Text("导出抓包数据")
        }
        Text(
            text = "登录后在页面停留片刻，已识别的取件码会自动保存到本机。",
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun PickupCodeCard(
    entry: PickupCodeEntity,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onTogglePicked: () -> Unit
) {
    val density = LocalDensity.current
    var drag by remember { mutableStateOf(0f) }
    val color = if (entry.picked) MutedInk else Ink
    com.burgess.schedule.ui.components.AppCard(Modifier.fillMaxWidth().padding(horizontal = AppSpace.page, vertical = AppSpace.gridGap).draggable(rememberDraggableState { drag += it }, Orientation.Horizontal, onDragStarted = { drag = 0f }, onDragStopped = { if (!entry.picked && drag < -with(density) { AppSpace.touch.toPx() }) onTogglePicked(); drag = 0f }), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.code, Modifier.weight(1f), style = AppType.sheetTitle.copy(letterSpacing = 1.sp), color = color)
            Box(Modifier.background(Ink.copy(alpha = .06f), AppShape.pill).padding(AppSpace.gridGap)) { Text(if (entry.picked) "已取" else "待取", style = AppType.caption, color = MutedInk) }
            com.burgess.schedule.ui.components.PillButton("复制", onCopy, com.burgess.schedule.ui.components.PillStyle.Ghost, true)
        }
        Text(entry.station + " · " + formatPickupTime(entry.createdAt), style = AppType.caption, color = MutedInk)
        com.burgess.schedule.ui.components.PillButton(if (entry.picked) "标记待取" else "标记已取件", onTogglePicked, com.burgess.schedule.ui.components.PillStyle.Ghost, true)
    }
}
@Preview @Composable private fun PickupLight() { 课程表Theme(false) { PickupCodeCard(PickupCodeEntity(code="8-1024", station="校园驿站", status="待取", picked=false, createdAt=0), {}, {}, {}) } }
@Preview @Composable private fun PickupDark() { 课程表Theme(true) { PickupCodeCard(PickupCodeEntity(code="8-1024", station="校园驿站", status="已取", picked=true, createdAt=0), {}, {}, {}) } }

@Composable
private fun QuickPickupCodeOverlay(
    entry: PickupCodeEntity,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    var revealed by remember(entry.id) { mutableStateOf(false) }
    LaunchedEffect(entry.id) {
        revealed = true
    }
    val rotationY by animateFloatAsState(
        targetValue = if (revealed) 0f else -90f,
        animationSpec = tween(520),
        label = "quick pickup card flip"
    )
    val scale by animateFloatAsState(
        targetValue = if (revealed) 1f else 0.82f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
        label = "quick pickup card scale"
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    this.rotationY = rotationY
                    scaleX = scale
                    scaleY = scale
                    cameraDistance = 12f * density
                },
            shape = RoundedCornerShape(24.dp),
            color = CardColor,
            tonalElevation = 0.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "快捷取件码",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink
                )
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = entry.code,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 4.sp,
                    color = AccentDeep,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = entry.station.ifBlank { "驿站未标注" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Ink
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = entry.status.ifBlank { "待取件" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedInk
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCopy) {
                        Text("复制取件码")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}

@Composable
internal fun PickupBriefCard(
    pending: List<PickupCodeEntity>,
    onOpen: (PickupCodeEntity) -> Unit,
    onCopy: (String) -> Unit
) {
    val first = pending.first()
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interactionSource)
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { onOpen(first) }
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pending.size.toString() + " 个包裹待取",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = first.code + " · " + first.station.ifBlank { "点击查看全部" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentDeep
                )
            }
            TextButton(onClick = { onCopy(first.code) }) {
                Text("复制")
            }
        }
    }
}

private fun normalizeTrackingNumber(raw: String): String =
    raw.replace(Regex("\\s+"), "").trim().uppercase(Locale.ROOT)

private fun isTrackingNumberCandidate(value: String): Boolean =
    value.length in 6..40 && value.all { it.isLetterOrDigit() || it == '-' }

private fun formatPickupTime(timestamp: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(timestamp))


@Composable
private fun AppBottomNavigation(selectedPage: Int, onPageSelected: (Int) -> Unit) {
    com.burgess.schedule.ui.components.FloatingNavigation(selectedPage, onPageSelected)
}

@Composable
private fun AppCard(
    modifier: Modifier = Modifier,
    color: Color = CardColor,
    content: @Composable ColumnScope.() -> Unit
) {
    com.burgess.schedule.ui.components.AppCard(modifier = modifier, color = color, content = content)
}

@Composable
private fun AnimatedScheduleDialog(
    onDismissed: () -> Unit,
    content: @Composable ColumnScope.(requestDismiss: () -> Unit) -> Unit
) {
    val visibilityState = remember { MutableTransitionState(false) }
    var hasStarted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visibilityState.targetState = true
        hasStarted = true
    }
    LaunchedEffect(visibilityState.currentState, visibilityState.targetState, hasStarted) {
        if (hasStarted && !visibilityState.currentState && !visibilityState.targetState) {
            onDismissed()
        }
    }

    fun requestDismiss() {
        visibilityState.targetState = false
    }

    if (visibilityState.currentState || visibilityState.targetState) {
        Dialog(
            onDismissRequest = ::requestDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            AnimatedVisibility(
                visibleState = visibilityState,
                enter = fadeIn(tween(220)) +
                    scaleIn(
                        animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                        initialScale = 0.94f
                    ) +
                    slideInVertically(
                        animationSpec = tween(300),
                        initialOffsetY = { it / 18 }
                    ),
                exit = fadeOut(tween(150)) +
                    scaleOut(targetScale = 0.97f, animationSpec = tween(180)) +
                    slideOutVertically(
                        animationSpec = tween(190),
                        targetOffsetY = { it / 24 }
                    )
            ) {
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                        .fillMaxWidth()
                        .widthIn(max = 440.dp)
                        .heightIn(max = 760.dp)
                        .imePadding()
                        .navigationBarsPadding(),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Column(modifier = Modifier.padding(22.dp)) {
                        content(::requestDismiss)
                    }
                }
            }
        }
    }
}

@Composable
private fun OcrPreviewDialog(
    imageUri: Uri,
    onDismissed: () -> Unit,
    onImport: (List<OcrCourseDraft>) -> Unit
) {
    val context = LocalContext.current
    var isLoading by remember(imageUri) { mutableStateOf(true) }
    var drafts by remember(imageUri) { mutableStateOf(emptyList<OcrCourseDraft>()) }
    var errorMessage by remember(imageUri) { mutableStateOf<String?>(null) }

    LaunchedEffect(imageUri) {
        try {
            drafts = OcrScheduleParser.parseScheduleImage(context, imageUri)
            isLoading = false
        } catch (error: Exception) {
            errorMessage = error.localizedMessage ?: "无法识别这张图片"
            isLoading = false
        }
    }

    AnimatedScheduleDialog(onDismissed = onDismissed) { requestDismiss ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "截图识别预览",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "确认后仅替换目标周，不会改动其他周课程。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            CompactActionButton(text = "关闭", onClick = requestDismiss)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(min = 80.dp, max = 520.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
                when {
                    isLoading -> {
                        CircularProgressIndicator()
                        Text("正在识别课程位置…")
                    }

                    errorMessage != null -> {
                        Text(errorMessage!!)
                    }

                    drafts.isNotEmpty() -> {
                        Text(
                            text = "识别到 " + drafts.size + " 个课程块，将替换当前周课程。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        drafts.forEachIndexed { index, draft ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = (index + 1).toString() + ". " + draft.name,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "周" + weekDayLabels[draft.dayIndex] + " · " + periodRangeLabel(draft.period, draft.endPeriod),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (draft.detail.isNotBlank()) {
                                        Text(
                                            text = draft.detail,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            text = "导入后可以点击课程卡片继续修改名称、教室和节次。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    else -> {
                        Text("没有识别到可定位的彩色课程块，请选择包含完整课表网格的截图。")
                    }
                }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = requestDismiss) {
                Text("取消")
            }
            if (drafts.isNotEmpty()) {
                Button(
                    onClick = {
                        onImport(drafts)
                        requestDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Ink,
                        contentColor = CardColor
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("替换并导入 ${drafts.size} 门")
                }
            }
        }
    }
}


@Composable
private fun PillButton(text: String, filled: Boolean, onClick: () -> Unit) {
    com.burgess.schedule.ui.components.PillButton(
        text, onClick,
        if (filled) com.burgess.schedule.ui.components.PillStyle.Primary else com.burgess.schedule.ui.components.PillStyle.Secondary,
        small = true
    )
}

@Composable
private fun CompactActionButton(text: String, onClick: () -> Unit) {
    com.burgess.schedule.ui.components.PillButton(
        text, onClick, com.burgess.schedule.ui.components.PillStyle.Secondary, small = true
    )
}

@Composable
private fun ReminderDialog(
    status: ReminderStatus,
    onDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onRequestExactAlarm: () -> Unit,
    onSendTest: () -> Unit
) {
    val context = LocalContext.current
    var lead by remember { mutableStateOf(ReminderScheduler.leadMinutes(context)) }
    BottomSheetContainer(onDismiss) {
        Text("课程提醒", style = AppType.title, color = Ink)
        if (!status.notificationPermissionGranted || !status.notificationsEnabled) {
            SectionLabel("系统通知尚未开启，课程开始前无法显示提醒。请在系统设置中允许通知。")
            com.burgess.schedule.ui.components.PillButton("去开启", onRequestPermission)
        }
        ListGroup {
            ListRow("开启提醒", if (status.remindersEnabled) "上课前 $lead 分钟提醒" else "提醒已关闭", trailing = { AppSwitch(status.remindersEnabled, onToggle) })
            HorizontalDivider(color = Hairline)
            SectionLabel("提前提醒时间")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf(5, 10, 15, 30).forEach { minutes ->
                    com.burgess.schedule.ui.components.PillButton("$minutes 分", { lead = minutes; ReminderScheduler.setLeadMinutes(context, minutes) }, if (lead == minutes) com.burgess.schedule.ui.components.PillStyle.Primary else com.burgess.schedule.ui.components.PillStyle.Ghost, true)
                }
            }
            ListRow("发送测试通知", "检查通知是否正常显示", onSendTest)
            if (!status.exactAlarmAllowed) {
                HorizontalDivider(color = Hairline)
                ListRow("允许精确提醒", "在系统设置中允许闹钟和提醒权限", onRequestExactAlarm)
            }
        }
    }
}
@Preview @Composable private fun ReminderLight() { 课程表Theme(false) { ReminderDialog(ReminderStatus(true, false, false, false), {}, {}, {}, {}, {}) } }
@Preview @Composable private fun ReminderDark() { 课程表Theme(true) { ReminderDialog(ReminderStatus(true, true, true, true), {}, {}, {}, {}, {}) } }



@Composable
private fun scheduleOutlinedFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Ink,
    unfocusedTextColor = Ink,
    focusedContainerColor = CardColor,
    unfocusedContainerColor = Gray50,
    focusedBorderColor = Ink,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedLabelColor = AccentDeep,
    unfocusedLabelColor = MutedInk,
    cursorColor = Ink,
    errorBorderColor = MaterialTheme.colorScheme.error,
    errorLabelColor = MaterialTheme.colorScheme.error
)

@Composable
private fun CourseEditorDialog(
    course: Course?,
    initialDay: Int,
    initialPeriod: Int,
    initialEndPeriod: Int,
    initialWeekNumber: Int,
    allCourses: List<Course> = emptyList(),
    onDismissed: () -> Unit,
    onSave: (Course) -> Unit,
    onDelete: (Course) -> Unit
) {
    var name by remember(course?.id, initialDay, initialPeriod) {
        mutableStateOf(course?.name.orEmpty())
    }
    var customShortName by remember(course?.id) { mutableStateOf(course?.shortName.orEmpty()) }
    var teacher by remember(course?.id, initialDay, initialPeriod) {
        mutableStateOf(course?.teacher.orEmpty())
    }
    var room by remember(course?.id, initialDay, initialPeriod) {
        mutableStateOf(course?.room.orEmpty())
    }
    var selectedDay by remember(course?.id, initialDay, initialPeriod) {
        mutableStateOf(course?.dayIndex ?: initialDay.coerceIn(0, 6))
    }
    var selectedPeriod by remember(course?.id, initialDay, initialPeriod, initialEndPeriod) {
        mutableStateOf(
            (course?.period ?: initialPeriod.coerceIn(0, periods.lastIndex))
        )
    }
    var selectedEndPeriod by remember(course?.id, initialDay, initialPeriod, initialEndPeriod) {
        mutableStateOf(
            (course?.endPeriod ?: initialEndPeriod.coerceIn(0, periods.lastIndex))
                .coerceIn(selectedPeriod, periods.lastIndex)
        )
    }
    var dayMenuExpanded by remember { mutableStateOf(false) }
    var periodMenuExpanded by remember { mutableStateOf(false) }
    var endPeriodMenuExpanded by remember { mutableStateOf(false) }
    var nameTouched by remember(course?.id, initialDay, initialPeriod) { mutableStateOf(false) }
    val nameError = nameTouched && name.isBlank()
    val conflicts = allCourses.filter { it.id != course?.id && hasTimeConflict(selectedDay, selectedPeriod, selectedEndPeriod, course?.weekNumber ?: initialWeekNumber, it.dayIndex, it.period, it.endPeriod, it.weekNumber) }
    val fieldColors = scheduleOutlinedFieldColors()

    AnimatedScheduleDialog(onDismissed = onDismissed) { requestDismiss ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (course == null) "添加课程" else "编辑课程",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (course == null) "把今天的安排记下来" else "更新课程信息与上课位置",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            CompactActionButton(text = "关闭", onClick = requestDismiss)
        }
        Spacer(modifier = Modifier.height(18.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "课程信息",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MutedInk
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 2,
                label = { Text("课程名称") },
                isError = nameError,
                supportingText = { if (nameError) Text("请输入课程名称") },
                colors = fieldColors
            )
            OutlinedTextField(value = customShortName, onValueChange = { customShortName = it }, label = { Text("周视图简称（选填）") }, supportingText = { Text("预览：" + customShortName.ifBlank { shortName(name) }) }, modifier = Modifier.fillMaxWidth(), colors = fieldColors)
            Text(
                text = "时间安排",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MutedInk
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    EditorSelectField(
                        label = "星期",
                        value = "星期${weekDayLabels[selectedDay]}",
                        onClick = { dayMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = dayMenuExpanded,
                        onDismissRequest = { dayMenuExpanded = false }
                    ) {
                        weekDayLabels.forEachIndexed { index, label ->
                            DropdownMenuItem(
                                text = { Text("星期$label") },
                                onClick = {
                                    selectedDay = index
                                    dayMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    EditorSelectField(
                        label = "开始节次",
                        value = periods[selectedPeriod].label,
                        onClick = { periodMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = periodMenuExpanded,
                        onDismissRequest = { periodMenuExpanded = false }
                    ) {
                        periods.indices.forEach { index ->
                            DropdownMenuItem(
                                text = { Text(periods[index].label) },
                                onClick = {
                                    selectedPeriod = index
                                    selectedEndPeriod = selectedEndPeriod.coerceAtLeast(index)
                                    periodMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
            Box(modifier = Modifier.fillMaxWidth()) {
                EditorSelectField(
                    label = "结束节次",
                    value = periods[selectedEndPeriod].label,
                    onClick = { endPeriodMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                )
                DropdownMenu(
                    expanded = endPeriodMenuExpanded,
                    onDismissRequest = { endPeriodMenuExpanded = false }
                ) {
                    (selectedPeriod..periods.lastIndex).forEach { index ->
                        DropdownMenuItem(
                            text = { Text(periods[index].label) },
                            onClick = {
                                selectedEndPeriod = index
                                endPeriodMenuExpanded = false
                            }
                        )
                    }
                }
            }
            Text(
                text = "授课信息",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MutedInk
            )
            OutlinedTextField(
                value = teacher,
                onValueChange = { teacher = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("教师（可选）") },
                colors = fieldColors
            )
            OutlinedTextField(
                value = room,
                onValueChange = { room = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 4,
                label = { Text("教室 / 地点") },
                colors = fieldColors
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        if (conflicts.isNotEmpty()) SectionLabel("与《${conflicts.first().name}》时间冲突，仍可保存。")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (course != null) {
                TextButton(
                    onClick = {
                        onDelete(course)
                        requestDismiss()
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = requestDismiss) {
                Text("取消")
            }
            Button(
                onClick = {
                    if (name.isBlank()) {
                        nameTouched = true
                    } else {
                        val saved = Course(
                            id = course?.id ?: System.currentTimeMillis(),
                            name = name.trim(),
                        shortName = customShortName.trim().ifBlank { null },
                            teacher = teacher.trim(),
                            room = room.trim().ifBlank { "未填写教室" },
                            dayIndex = selectedDay,
                            period = selectedPeriod,
                            endPeriod = selectedEndPeriod,
                            weekNumber = course?.weekNumber ?: initialWeekNumber,
                            color = course?.color ?: courseAccentFor(System.currentTimeMillis())
                        )
                        onSave(saved)
                        requestDismiss()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Ink,
                    contentColor = CardColor
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("保存")
            }
        }
    }
}

@Composable
private fun EditorSelectField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = AppShape.card
    Box(
        modifier = modifier
            .clip(shape)
            .background(Gray50)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ink
            )
        }
    }
}

private fun normalizeImportedCourseName(name: String): String {
    return name
        .replace("杜会", "社会")
        .replace("休育", "体育")
        .replace("|", "")
        .replace("丨", "")
}

private fun defaultImportedRoom(name: String): String {
    return when {
        name == "产品原型设计" -> "先进制造产教融合实训基地D202"
        name.contains("习近平新时代中国特色社会主义思想") -> "二号教学楼B204"
        else -> ""
    }
}

private fun findFocusCourse(courses: List<Course>, now: Calendar, currentWeekNumber: Int): FocusCourse? {
    if (courses.isEmpty()) return null
    val todayIndex = currentDayIndex(now)
    val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    val currentWeekCourses = courses.filter { it.weekNumber == currentWeekNumber }
    val todayCourses = currentWeekCourses
        .filter { it.dayIndex == todayIndex }
        .sortedBy { periods[it.period].startMinutes }

    todayCourses.firstOrNull {
        val start = periods[it.period].startMinutes
        val end = periods[it.endPeriod.coerceIn(it.period, periods.lastIndex)].endMinutes
        currentMinutes in start until end
    }?.let {
        return FocusCourse(it, todayIndex, 0, true)
    }

    todayCourses.firstOrNull {
        periods[it.period].startMinutes > currentMinutes
    }?.let {
        return FocusCourse(it, todayIndex, 0, false)
    }

    currentWeekCourses
        .filter { it.dayIndex > todayIndex }
        .minByOrNull { it.dayIndex * 10 + it.period }
        ?.let {
            return FocusCourse(it, it.dayIndex, 0, false)
        }

    courses
        .filter { it.weekNumber > currentWeekNumber }
        .minWithOrNull(compareBy<Course>({ it.weekNumber }, { it.dayIndex }, { it.period }))
        ?.let {
            return FocusCourse(it, it.dayIndex, it.weekNumber - currentWeekNumber, false)
        }

    return null
}

internal fun mondayOfCurrentWeek(source: Calendar): Calendar {
    return source.cloneCalendar().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        val dayOfWeek = get(Calendar.DAY_OF_WEEK)
        val daysFromMonday = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
        add(Calendar.DAY_OF_MONTH, -daysFromMonday)
    }
}

internal fun currentDayIndex(calendar: Calendar = Calendar.getInstance()): Int {
    val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
    return if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
}

internal fun Calendar.cloneCalendar(): Calendar = (clone() as Calendar)

internal fun formatToday(calendar: Calendar): String {
    val date = SimpleDateFormat("M月d日", Locale.CHINA).format(calendar.time)
    return "$date · 星期${weekDayLabels[currentDayIndex(calendar)]}"
}

internal fun formatDateRange(weekStart: Calendar): String {
    val end = weekStart.cloneCalendar().apply { add(Calendar.DAY_OF_MONTH, 6) }
    val formatter = SimpleDateFormat("M月d日", Locale.CHINA)
    return "${formatter.format(weekStart.time)} - ${formatter.format(end.time)}"
}

internal fun scheduleWeekLabel(weekStart: Calendar): String {
    val weekNumber = scheduleWeekNumber(weekStart)
    return if (weekNumber < 1) "开学前" else "第 $weekNumber 周"
}

internal fun scheduleWeekNumber(weekStart: Calendar): Int {
    val firstWeek = mondayOfCurrentWeek(scheduleFirstWeekStart)
    val normalizedWeek = mondayOfCurrentWeek(weekStart)
    val daysFromFirstWeek = ((normalizedWeek.timeInMillis - firstWeek.timeInMillis) / (24 * 60 * 60 * 1000L)).toInt()
    return daysFromFirstWeek / 7 + 1
}

internal fun focusDateLabel(focus: FocusCourse, today: Calendar): String {
    val target = mondayOfCurrentWeek(today).apply {
        add(Calendar.WEEK_OF_YEAR, focus.weekOffset)
        add(Calendar.DAY_OF_MONTH, focus.dayIndex)
    }
    val todayStart = today.cloneCalendar().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val daysAway = ((target.timeInMillis - todayStart.timeInMillis) / (24 * 60 * 60 * 1000)).toInt()
    return when (daysAway) {
        0 -> "今天"
        1 -> "明天"
        else -> "周${weekDayLabels[focus.dayIndex]}"
    }
}

@Preview(showBackground = true)
@Composable
private fun SchedulePreview() {
    课程表Theme {
        ScheduleApp()
    }
}
