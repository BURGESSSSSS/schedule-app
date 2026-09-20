package com.burgess.schedule

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.compose.foundation.Image
import com.burgess.schedule.data.CourseDatabase
import com.burgess.schedule.data.PickupCodeEntity
import com.burgess.schedule.data.SchoolSchedule
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class Course(
    val id: Long,
    val name: String,
    val teacher: String,
    val room: String,
    val dayIndex: Int,
    val period: Int,
    val color: Color,
    val endPeriod: Int = period,
    val weekNumber: Int = 0
)

private data class FocusCourse(
    val course: Course,
    val dayIndex: Int,
    val weekOffset: Int,
    val isInProgress: Boolean
)

private data class WeekSnapshot(
    val weekOffset: Int,
    val weekStartMillis: Long,
    val weekNumber: Int,
    val courses: List<Course>
)

private data class Period(
    val label: String,
    val startMinutes: Int,
    val endMinutes: Int
)

private val weekDayLabels = listOf("一", "二", "三", "四", "五", "六", "日")
private val scheduleFirstWeekStart = SchoolSchedule.firstWeekStart()
private val periods = SchoolSchedule.periods.mapIndexed { index, period ->
    Period("第 ${index + 1} 节", period.startMinutes, period.endMinutes)
}

private val Ink: Color @Composable get() = LocalAppColors.current.ink
private val MutedInk: Color @Composable get() = LocalAppColors.current.mut
private val FaintInk: Color @Composable get() = LocalAppColors.current.mut
private val Accent: Color @Composable get() = LocalAppColors.current.ink
private val AccentDeep: Color @Composable get() = LocalAppColors.current.ink
private val Gray50: Color @Composable get() = LocalAppColors.current.bg
private val Gray100: Color @Composable get() = LocalAppColors.current.bg
private val Hairline: Color @Composable get() = LocalAppColors.current.line
private val CourseSlotFill: Color @Composable get() = LocalAppColors.current.ink.copy(alpha = .035f)
private val CardColor: Color @Composable get() = LocalAppColors.current.card

private fun displayLocation(raw: String): String {
    val compact = raw
        .replace('\r', ' ')
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
    if (compact.isBlank()) return ""

    return buildString(compact.length) {
        compact.forEachIndexed { index, character ->
            val previous = compact.getOrNull(index - 1)
            val next = compact.getOrNull(index + 1)
            val isOcrGap = character == ' ' && previous != null && next != null &&
                ((previous.isLocationCjkOrDigit() && next.isLetterOrDigit()) ||
                    (next.isLocationCjkOrDigit() && previous.isLetterOrDigit()))
            if (!isOcrGap) append(character)
        }
    }.trim()
}

private fun Char.isLocationCjkOrDigit(): Boolean =
    isDigit() || this in '\u4E00'..'\u9FFF'

// 课程用左侧细色条区分，色板保持饱和但不刺眼。
private val courseAccentColors = CoursePalette
private fun courseAccentFor(id: Long): Color = CoursePalette[Math.floorMod(id, CoursePalette.size.toLong()).toInt()]
private fun stableCoursePaletteIndex(course: Course): Int = Math.floorMod(course.color.toArgb(), CoursePalette.size)
@Composable private fun courseFillFor(course: Course): Color = courseTint(courseAccentFor(course)).first
private fun courseAccentFor(course: Course): Color = CoursePalette[stableCoursePaletteIndex(course)]

private fun CourseEntity.toCourse(): Course {
    val safePeriod = period.coerceIn(0, periods.lastIndex)
    val safeEndPeriod = endPeriod.coerceIn(safePeriod, periods.lastIndex)
    return Course(
        id = id,
        name = name,
        teacher = teacher,
        room = room,
        dayIndex = dayIndex.coerceIn(0, 6),
        period = safePeriod,
        endPeriod = safeEndPeriod,
        weekNumber = weekNumber,
        color = colorValue.takeIf { it != 0 }?.let { Color(it) } ?: courseAccentFor(id)
    )
}

private fun Course.toEntity(): CourseEntity {
    val safePeriod = period.coerceIn(0, periods.lastIndex)
    val safeEndPeriod = endPeriod.coerceIn(safePeriod, periods.lastIndex)
    return CourseEntity(
        id = id,
        name = name,
        teacher = teacher,
        room = room,
        dayIndex = dayIndex.coerceIn(0, 6),
        period = safePeriod,
        endPeriod = safeEndPeriod,
        weekNumber = weekNumber,
        colorValue = color.toArgb()
    )
}

private fun courseKey(course: Course): String {
    return listOf(
        course.name.trim().lowercase(Locale.ROOT),
        course.room.trim().lowercase(Locale.ROOT),
        course.dayIndex,
        course.period,
        course.endPeriod
    ).joinToString("|")
}

class MainActivity : ComponentActivity() {
    private var sharedImageUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedImageUri = intent.sharedImageUri()
        // 界面固定浅色，系统栏图标始终用深色，避免深色系统的白图标落在白底上。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        setContent {
            课程表Theme {
                ScheduleApp(
                    sharedImageUri = sharedImageUri,
                    onSharedImageHandled = { sharedImageUri = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedImageUri = intent.sharedImageUri()
    }
}

private fun Intent.sharedImageUri(): Uri? {
    if (action != Intent.ACTION_SEND || type?.startsWith("image/") != true) {
        return null
    }
    return getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        ?: clipData?.getItemAt(0)?.uri
}

@Composable
private fun ScheduleApp(
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
    val courses = courseEntities?.map { it.toCourse() }.orEmpty()
    var designVisible by remember { mutableStateOf(false) }
    var selectedPage by remember { mutableStateOf(0) }
    var expandedCourse by remember { mutableStateOf<Course?>(null) }
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
    LaunchedEffect(Unit) {
        while (isActive) {
            nowMillis = System.currentTimeMillis()
            // Refresh date/highlight state without keeping an animation alive.
            delay(30_000L)
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
        imagePickerLauncher.launch("image/*")
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
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
        },

    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                AnimatedContent(
                    targetState = selectedPage,
                    transitionSpec = {
                        fadeIn(tween(Motion.TabCrossfadeMs)) togetherWith fadeOut(tween(Motion.TabCrossfadeMs))
                    },
                    label = "page transition"
                ) { page ->
                    when (page) {
                        0 -> TodayScreen(
                            today = today,
                            courses = courses.filter { it.weekNumber == currentWeekNumber },
                            focus = focus,
                            pendingPickups = pendingPickups,
                            onPickupOpen = { quickPickupEntry = it },
                            onPickupCopy = { copyPickupCode(it) },
                            onCourseClick = { expandedCourse = it }
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
                            onCopyNextWeek = { sourceWeekNumber, source ->
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
                            },
                            onCourseClick = { expandedCourse = it }
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
            expandedCourse?.let { course ->
                ExpandedCourseOverlay(
                    course = course,
                    onDismiss = { expandedCourse = null }
                )
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
                        scope.launch { pickupDao.setPicked(entry.id, !entry.picked) }
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

    if (designVisible && BuildConfig.DEBUG) {
        Dialog(onDismissRequest = { designVisible = false }) { com.burgess.schedule.ui.components.DesignPreview() }
    }

    if (editorVisible) {
        CourseEditorDialog(
            course = editingCourse,
            initialDay = editorDay,
            initialPeriod = editorPeriod,
            initialEndPeriod = editorEndPeriod,
            initialWeekNumber = editorWeekNumber,
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
    var trackingInput by remember { mutableStateOf("") }
    val normalizedTrackingNumber = normalizeTrackingNumber(trackingInput)
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Gray50, Gray100)))
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("‹ 返回")
            }
            Text(
                text = "取件码",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onLogout) {
                Text("退出账号")
            }
        }
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
            codes.forEach { entry ->
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
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onOpen)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.code,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (entry.picked) FaintInk else Ink,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = entry.station.ifBlank { "驿站未标注" } + " · " + formatPickupTime(entry.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedInk
                )
            }
            if (entry.picked) {
                Text(
                    text = "已取",
                    style = MaterialTheme.typography.labelLarge,
                    color = FaintInk
                )
            } else {
                TextButton(onClick = onCopy) {
                    Text("复制")
                }
                TextButton(onClick = onTogglePicked) {
                    Text("已取")
                }
            }
        }
    }
}

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
            tonalElevation = 8.dp
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
private fun PickupBriefCard(
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
private fun StaggeredReveal(
    index: Int,
    initialScale: Float = 0.985f,
    content: @Composable () -> Unit
) {
    val visibilityState = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) {
        delay((index * 60L).coerceAtMost(360L))
        visibilityState.targetState = true
    }
    AnimatedVisibility(
        visibleState = visibilityState,
        enter = fadeIn(tween(300)) +
            slideInVertically(
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
                initialOffsetY = { -it / 12 }
            ) +
            scaleIn(
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
                initialScale = initialScale
            ),
        exit = fadeOut(tween(140))
    ) {
        content()
    }
}

private data class DevLogSection(
    val title: String,
    val body: String
)

private data class DevLogEntry(
    val version: String,
    val date: String,
    val intro: String,
    val sections: List<DevLogSection>,
    val closing: String
)

private val devLogEntries = listOf(
    DevLogEntry(
        version = "1.0.0",
        date = "2026年8月23日",
        intro = "针对日常使用商务课表时需要频繁打开网页、等待加载，以及课程表截图保存后查找不便等问题，我开发了这款简单、轻量的离线课程表软件。",
        sections = listOf(
            DevLogSection("✨ 本次更新", ""),
            DevLogSection(
                "1. 离线 OCR 课程表识别",
                "软件采用离线 OCR 截图识别，并针对商务课表的排版特点进行了适配与优化。\n不过，由于商务课表本身排版较为复杂，不同情况下可能存在识别偏差，因此目前无法保证 100% 的识别准确率。\n如果出现课程名称、时间、地点等信息识别错误，可以直接使用软件内的编辑功能进行修改，无需重新识别。"
            ),
            DevLogSection(
                "2. 上课通知提醒",
                "1.0.0 版本新增课程通知功能。\n在「更多」页面开启通知权限后，软件会在上课前 10 分钟发送通知，提醒下一节课程的课程时间、上课地点，帮助大家减少错过课程或临时翻找课表的情况。"
            ),
            DevLogSection(
                "📌 关于目前版本",
                "目前软件还处于初期开发阶段，功能和体验仍有进一步完善的空间。\n如果你在使用过程中遇到问题，或者有更好的功能建议，欢迎向我反馈。你的每一条意见都会成为后续版本优化的重要参考。\n也欢迎大家把这款软件推荐给身边有需要的同学，一起让它变得更好用。"
            ),
            DevLogSection(
                "🔮 后续计划",
                "目前下一版本已经有一些初步规划，其中比较希望加入的是淘宝取件码快捷查询。\n希望将日常学习、生活中常用的信息进一步整合到软件中，减少不同应用之间来回切换的麻烦，后续还会根据大家的实际使用反馈，持续完善功能。"
            )
        ),
        closing = "感谢每一位愿意尝试这款软件的同学，1.0.0 只是开始，欢迎提出建议，也期待和大家一起把它做得更好。"
    ),
    DevLogEntry(
        version = "1.0.1",
        date = "2026年8月28日",
        intro = "1.0.1 是一次集中解决流畅度的更新。之前版本的光幕背景动画每一帧都在计算全屏模糊，手机压力很大，不少同学反馈使用时卡顿、发热，这次把它彻底解决了。",
        sections = listOf(
            DevLogSection("✨ 本次更新", ""),
            DevLogSection(
                "1. 流畅度优化",
                "背景光幕改为静态绘制，去掉了逐帧计算的实时模糊。\n现在滑动课程表、切换页面应该明显更跟手，长时间挂着也不会持续发热耗电。"
            ),
            DevLogSection(
                "2. 正式版安装包",
                "从这一版开始提供开启完整优化的正式安装包，安装体积更小，运行效率更高。"
            ),
            DevLogSection(
                "📌 覆盖安装说明",
                "如果你之前安装的是调试版本，直接覆盖安装新版即可，已录入的课程数据会完整保留，不需要重新导入。"
            )
        ),
        closing = "流畅度问题解决之后，我会把精力放回功能开发上。如果你的手机上仍然感觉卡顿，欢迎告诉我你的手机型号，我会继续排查。"
    ),
    DevLogEntry(
        version = "1.0.2",
        date = "2026年8月28日",
        intro = "1.0.2 是一次界面打磨版本。核心功能稳定下来之后，这一版按照 Material 3 的设计规范把细节重新梳理了一遍，让软件看起来更清爽、用起来更舒服。",
        sections = listOf(
            DevLogSection("✨ 本次更新", ""),
            DevLogSection(
                "1. 视觉全面打磨",
                "统一了字号、间距和留白节奏，首页标题更大更醒目，卡片之间呼吸感更好，整体配色保持克制。"
            ),
            DevLogSection(
                "2. 按压弹性反馈",
                "课程卡片和功能入口在按下时会有轻微的弹性缩放，松手后回弹，操作手感更真实。"
            ),
            DevLogSection(
                "3. 新增开发日志",
                "「更多」页面新增「开发日志」入口，你正在看的这个页面就是它。以后每次更新我都会在这里记录改动和想法。"
            ),
            DevLogSection(
                "🔮 后续计划",
                "下一版本计划加入淘宝取件码快捷查询，把常用信息进一步整合到软件里。有功能建议随时反馈。"
            )
        ),
        closing = "感谢每一位愿意尝试这款软件的同学，欢迎继续提出建议，也期待和大家一起把它做得更好。"
    ),
    DevLogEntry(
        version = "1.1.0",
        date = "2026年8月28日",
        intro = "1.1.0 是一次界面全面改版。参考 OpenAI 官网的设计语言，整个软件换成了一套安静的中性视觉：白底、黑字、细线分隔，只在状态标识上保留一抹绿色。课程格子也按可读性优先的原则重新设计，课程名称完整清晰是第一位的。所有功能和使用习惯保持不变。",
        sections = listOf(
            DevLogSection("✨ 本次更新", ""),
            DevLogSection(
                "1. 全新视觉语言",
                "首页、周视图、更多页面统一为纯白卡片加 1px 细线分隔，标题更大更利落；界面固定使用浅色主题，无论系统如何设置，打开永远是最清晰的样子。"
            ),
            DevLogSection(
                "2. 课程格子重做",
                "课程用低饱和的课程色作为格子底色，课程名称加粗完整显示，教室紧随其后；正在上的课会有绿色描边和「进行中」标识，扫一眼就能找到重点。"
            ),
            DevLogSection(
                "3. 状态强调色",
                "「正在进行」和「下一节课」统一用绿色小圆点和描边标识，按下卡片和按钮时仍有轻微的弹性反馈，操作手感更踏实。"
            ),
            DevLogSection(
                "🔮 后续计划",
                "界面稳定之后，下一版本计划加入淘宝取件码快捷查询，把常用信息进一步整合进来。"
            )
        ),
        closing = "这次改版把「读得清楚」放在了「好看」前面。如果你觉得哪里还不够清楚，随时告诉我。"
    ),
    DevLogEntry(
        version = "1.2.0",
        date = "2026年8月29日",
        intro = "1.2.0 新增了「取件码」功能。快递到驿站后，可以直接在软件里查看和复制取件码，不用再打开淘宝、找入口、等加载。所有数据只保存在手机本地。",
        sections = listOf(
            DevLogSection("✨ 本次更新", ""),
            DevLogSection(
                "1. 取件码",
                "「更多」页新增「取件码」入口，可以在这里查看取件码、一键复制并标记已取，取完的包裹会自动从列表收起。"
            ),
            DevLogSection(
                "2. 包裹页直显",
                "内嵌的淘宝包裹页只需登录一次，之后从软件里一键直达。今日页也会在有包裹待取时出现一张快捷卡片，看课的同时顺手就能把码复制了。"
            ),
            DevLogSection(
                "3. 说明",
                "现阶段取件码以包裹页直显为主，自动识别快递通知、把新包裹自动收进列表的能力会在后续版本加入。"
            ),
            DevLogSection(
                "🔮 后续计划",
                "下一版本计划让取件码自动从快递通知里获取，做到零手动操作。"
            )
        ),
        closing = "功能还在早期，使用中有任何问题欢迎反馈。"
    ),
    DevLogEntry(
        version = "1.3.0",
        date = "2026年9月19日",
        intro = "1.3.0 继续完善课程表的日常使用体验：优先保证已有课程和取件码不丢失，再把日期刷新、周末课程和导入安全补齐。",
        sections = listOf(
            DevLogSection("✨ 本次更新", ""),
            DevLogSection(
                "1. 课程数据更稳",
                "导入替换改为事务写入，确认后的截图识别结果要么完整替换目标周，要么保留原数据；复制到下周会跳过重复课程。"
            ),
            DevLogSection(
                "2. 周视图和编辑",
                "当前时间会定期刷新，跨天或跨时段后今日页和进行中标识会自动更新。有周末课程时，周六日以独立网格显示，不压窄常规周一至周五列；编辑课程可直接选择周六日、教师和起止节次。"
            ),
            DevLogSection(
                "3. 视觉和反馈",
                "保留白、黑、灰和绿色状态强调，页面与周切换只使用短暂轻量过渡；课程详情继续支持展开，课程卡片和主要入口保留按压反馈。"
            )
        ),
        closing = "课程数据、取件码和包裹页仍然保存在本机，通知权限和测试结果以系统实际反馈为准。"
    ),
    DevLogEntry(
        version = "1.3.1",
        date = "2026年9月20日",
        intro = "1.3.1 面向日常打开体验做了一次视觉返工：补齐中性色角色，重做编辑表单与周视图排版，并让关键切换更有方向感。",
        sections = listOf(
            DevLogSection("✨ 本次更新", ""),
            DevLogSection(
                "1. 表单和主题",
                "编辑与截图导入面板改为白底分组表单，边线、焦点和键盘滚动状态更清楚。"
            ),
            DevLogSection(
                "2. 周视图",
                "周视图按可用高度调整格子，课程名称和地点按空间自然换行，复制入口收紧为小操作。"
            ),
            DevLogSection(
                "3. 轻量动效",
                "今日页加入层级更清晰的概览，周切换、课程详情和面板进出增加轻量滑动、缩放与按压反馈。"
            )
        ),
        closing = "课程数据、通知、取件码和包裹页行为保持不变，本版仅调整展示与交互反馈。"
    ),
    DevLogEntry(
        version = "1.3.2",
        date = "2026年9月20日",
        intro = "1.3.2 聚焦今日页、周切换和课程详情的显示稳定性，保留现有数据与系统功能。",
        sections = listOf(
            DevLogSection("✨ 本次更新", ""),
            DevLogSection(
                "1. 今日页与课程格",
                "今日页改为轻量白底头部；课程格收紧留白，地点清理 OCR 空格后自然换行，进行中的课程只保留细绿色强调。"
            ),
            DevLogSection(
                "2. 切换与详情",
                "周视图按整周快照滑动切换，课程详情改为同页底部面板，遮罩与面板同步进出。"
            )
        ),
        closing = "通知、签名、数据库和取件功能保持不变，本版仅调整四处界面展示与交互反馈。"
    )
)

@Composable
private fun DevLogScreen(onBack: () -> Unit) {
    var detailVisible by remember { mutableStateOf(false) }
    var currentEntry by remember { mutableStateOf<DevLogEntry?>(null) }

    BackHandler(enabled = detailVisible) {
        detailVisible = false
    }
    BackHandler(enabled = !detailVisible) {
        onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Gray50, Gray100)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text("‹ 返回")
                }
                Text(
                    text = "开发日志",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(64.dp))
            }
            devLogEntries.forEachIndexed { index, entry ->
                StaggeredReveal(index) {
                    DevLogVersionRow(entry = entry, onClick = {
                        currentEntry = entry
                        detailVisible = true
                    })
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
        AnimatedVisibility(
            visible = detailVisible,
            enter = fadeIn(tween(200)) + slideInHorizontally(
                animationSpec = tween(280),
                initialOffsetX = { it }
            ),
            exit = fadeOut(tween(200)) + slideOutHorizontally(
                animationSpec = tween(240),
                targetOffsetX = { it }
            )
        ) {
            currentEntry?.let { entry ->
                DevLogDetailScreen(entry = entry, onBack = { detailVisible = false })
            }
        }
    }
}

@Composable
private fun DevLogVersionRow(entry: DevLogEntry, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interactionSource)
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "V" + entry.version,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = entry.date,
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedInk
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.titleMedium,
                color = FaintInk
            )
        }
    }
}

@Composable
private fun DevLogDetailScreen(entry: DevLogEntry, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Gray50, Gray100)))
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("‹ 返回")
            }
            Text(
                text = "V" + entry.version,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(64.dp))
        }
        AppCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = entry.date,
                style = MaterialTheme.typography.labelMedium,
                color = MutedInk
            )
            Spacer(modifier = Modifier.height(10.dp))
            if (entry.intro.isNotBlank()) {
                Text(
                    text = entry.intro,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                    color = MutedInk
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            entry.sections.forEach { section ->
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink
                )
                if (section.body.isNotBlank()) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = section.body,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        color = MutedInk
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            Text(
                text = entry.closing,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                color = MutedInk
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun rememberPressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f
): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 700f),
        label = "press scale"
    )
    return scale
}

@Composable
private fun MoreScreen(
    today: Calendar,
    onReminderClick: () -> Unit,
    onPickupClick: () -> Unit,
    onDevLogClick: () -> Unit,
    onDesignClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        if (BuildConfig.DEBUG) { TextButton(onClick = onDesignClick) { Text("设计预览") } }
        StaggeredReveal(0) {
            ScheduleHeader(
                title = "更多",
                today = today,
                showActions = false
            )
        }
        StaggeredReveal(1) {
            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "课程表",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "一款专注于快速查看课程、下一节课和上课地点的本地课程表工具。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedInk
                )
            }
        }
        StaggeredReveal(2) {
            MoreActionRow(
                title = "课程提醒",
                subtitle = "通知权限和测试通知",
                onClick = onReminderClick
            )
        }
        StaggeredReveal(3) {
            MoreActionRow(
                title = "取件码",
                subtitle = "包裹取件码和驿站",
                onClick = onPickupClick
            )
        }
        StaggeredReveal(4) {
            MoreActionRow(
                title = "开发日志",
                subtitle = "1.0.0 - 1.3.2 更新记录",
                onClick = onDevLogClick
            )
        }
        StaggeredReveal(5) {
            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.developer_avatar),
                        contentDescription = "开发者头像",
                        modifier = Modifier
                            .width(72.dp)
                            .height(72.dp)
                            .clip(RoundedCornerShape(20.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "开发者",
                            style = MaterialTheme.typography.labelLarge,
                            color = MutedInk
                        )
                        Text(
                            text = "Arihara",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Ink
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "为爱发电❤",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedInk
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "邮箱：suyu20651@gmail.com",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedInk
                )
            }
        }
        StaggeredReveal(6) {
            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "版本 1.3.2",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedInk
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "课程表和取件码数据均保存在本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedInk
                )
            }
        }
    }
}

@Composable
private fun MoreActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interactionSource)
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedInk
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.titleMedium,
                color = FaintInk
            )
        }
    }
}

@Composable
private fun TodayScreen(
    today: Calendar,
    courses: List<Course>,
    focus: FocusCourse?,
    pendingPickups: List<PickupCodeEntity>,
    onPickupOpen: (PickupCodeEntity) -> Unit,
    onPickupCopy: (String) -> Unit,
    onCourseClick: (Course) -> Unit
) {
    val todayCourses = courses
        .filter { it.dayIndex == currentDayIndex(today) }
        .sortedBy { it.period }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        StaggeredReveal(0) {
            TodayHero(today = today, courseCount = todayCourses.size)
        }
        if (focus != null) {
            StaggeredReveal(1) {
                NextCourseCard(
                    focus = focus,
                    today = today,
                    onClick = { onCourseClick(focus.course) }
                )
            }
        }
        if (pendingPickups.isNotEmpty()) {
            StaggeredReveal(3) {
                PickupBriefCard(
                    pending = pendingPickups,
                    onOpen = onPickupOpen,
                    onCopy = onPickupCopy
                )
            }
        }
        if (todayCourses.isEmpty()) {
            StaggeredReveal(2) {
                Text(
                    text = "今天没有安排课程",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedInk
                )
            }
        } else {
            todayCourses.forEachIndexed { index, course ->
                StaggeredReveal(index + 2) {
                    TodayCourseCard(course = course, onClick = { onCourseClick(course) })
                }
            }
        }
    }
}

@Composable
private fun TodayHero(today: Calendar, courseCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "今天",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
                color = Ink
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = formatToday(today),
                style = MaterialTheme.typography.bodyMedium,
                color = MutedInk
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = courseCount.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Ink
            )
            Text(
                text = "节课",
                style = MaterialTheme.typography.labelMedium,
                color = MutedInk
            )
        }
    }
}

@Composable
private fun TodayIntro(today: Calendar) {
    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
        Text(
            text = "今天 · 星期" + weekDayLabels[currentDayIndex(today)],
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = Ink
        )
    }
}

@Composable
private fun TodayCourseCard(course: Course, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interactionSource)
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(8.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(courseAccentFor(course))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = periodRangeLabel(course) + " · " + periodTimeRange(course),
                        style = MaterialTheme.typography.labelMedium,
                        color = MutedInk
                    )
                }
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = course.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = displayLocation(course.room).ifBlank { "地点待确认" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink
                )
            }
            Text(
                text = "›",
                fontSize = 24.sp,
                color = FaintInk
            )
        }
    }
}

@Composable
private fun WeekScreen(
    today: Calendar,
    selectedWeekStart: Calendar,
    weekOffset: Int,
    courses: List<Course>,
    focus: FocusCourse?,
    onImportClick: (Int) -> Unit,
    onReminderClick: () -> Unit,
    onPrevious: () -> Unit,
    onToday: () -> Unit,
    onNext: () -> Unit,
    editing: Boolean,
    onToggleEditing: () -> Unit,
    onAddCourse: (Int, Int, Int) -> Unit,
    onEditCourse: (Course) -> Unit,
    onCopyNextWeek: (Int, List<Course>) -> Unit,
    onCourseClick: (Course) -> Unit
) {
    val scrollState = rememberScrollState()
    val weekSnapshot = remember(weekOffset, selectedWeekStart.timeInMillis, courses) {
        WeekSnapshot(
            weekOffset = weekOffset,
            weekStartMillis = selectedWeekStart.timeInMillis,
            weekNumber = scheduleWeekNumber(selectedWeekStart),
            courses = courses.toList()
        )
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val rowHeight = ((maxHeight - 230.dp) / periods.size).coerceIn(54.dp, 76.dp)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            StaggeredReveal(0) {
                ScheduleHeader(
                    title = "周视图",
                    today = today,
                    onImportClick = { onImportClick(weekSnapshot.weekNumber) },
                    onReminderClick = onReminderClick,
                    showReminder = false,
                    showEdit = true,
                    editing = editing,
                    onEditClick = onToggleEditing
                )
            }
            StaggeredReveal(2, initialScale = 1f) {
                AnimatedContent(
                    targetState = weekSnapshot,
                    transitionSpec = {
                        val direction = if (targetState.weekOffset >= initialState.weekOffset) 1 else -1
                        (fadeIn(tween(220)) + slideInHorizontally(
                            animationSpec = tween(260),
                            initialOffsetX = { direction * it / 3 }
                        )) togetherWith
                            (fadeOut(tween(160)) + slideOutHorizontally(
                                animationSpec = tween(220),
                                targetOffsetX = { -direction * it / 3 }
                            ))
                    },
                    label = "week transition"
                ) { snapshot ->
                    val snapshotWeekStart = remember(snapshot.weekStartMillis) {
                        Calendar.getInstance().apply { timeInMillis = snapshot.weekStartMillis }
                    }
                    val snapshotFocus = focus?.takeIf { candidate ->
                        candidate.weekOffset == snapshot.weekOffset &&
                            snapshot.courses.any { it.id == candidate.course.id }
                    }
                    Column {
                        WeekNavigator(
                            weekStart = snapshotWeekStart,
                            weekOffset = snapshot.weekOffset,
                            onPrevious = onPrevious,
                            onToday = onToday,
                            onNext = onNext
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 1.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            CompactActionButton(
                                text = "复制到下周",
                                onClick = { onCopyNextWeek(snapshot.weekNumber, snapshot.courses) }
                            )
                        }
                        if (snapshot.courses.isEmpty()) {
                            AppCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "本周还没有课程",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Ink
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (editing) "点击下方空白时段添加课程，也可以使用导入。" else "点击右上角编辑，或从课表截图导入本周课程。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MutedInk
                                )
                            }
                        }
                        ScheduleGrid(
                            today = today,
                            courses = snapshot.courses,
                            focus = snapshotFocus,
                            selectedWeekOffset = snapshot.weekOffset,
                            editMode = editing,
                            rowHeight = rowHeight,
                            onAddCourse = { dayIndex, periodIndex ->
                                onAddCourse(snapshot.weekNumber, dayIndex, periodIndex)
                            },
                            onEditCourse = onEditCourse,
                            onCourseClick = onCourseClick
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

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
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(color)
            .border(1.dp, Hairline, shape)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
private fun ExpandedCourseOverlay(course: Course, onDismiss: () -> Unit) {
    val visibilityState = remember { MutableTransitionState(false) }
    var hasStarted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visibilityState.targetState = true
        hasStarted = true
    }
    LaunchedEffect(visibilityState.currentState, visibilityState.targetState, hasStarted) {
        if (hasStarted && !visibilityState.currentState && !visibilityState.targetState) {
            onDismiss()
        }
    }

    fun requestDismiss() {
        visibilityState.targetState = false
    }

    BackHandler(enabled = visibilityState.targetState) {
        requestDismiss()
    }

    val transition = updateTransition(visibilityState, label = "course detail overlay")
    val scrimAlpha by transition.animateFloat(
        transitionSpec = { tween(if (targetState) 300 else 180) },
        label = "course detail scrim"
    ) { visible -> if (visible) 0.24f else 0f }
    val panelAlpha by transition.animateFloat(
        transitionSpec = { tween(if (targetState) 280 else 170) },
        label = "course detail panel alpha"
    ) { visible -> if (visible) 1f else 0f }
    val panelOffsetFraction by transition.animateFloat(
        transitionSpec = { tween(if (targetState) 300 else 190) },
        label = "course detail panel offset"
    ) { visible -> if (visible) 0f else 1f }
    val panelScale by transition.animateFloat(
        transitionSpec = { tween(if (targetState) 280 else 170) },
        label = "course detail panel scale"
    ) { visible -> if (visible) 1f else 0.985f }

    if (visibilityState.currentState || visibilityState.targetState) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha))
                    .clickable(onClick = { requestDismiss() })
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .padding(horizontal = 12.dp)
                    .navigationBarsPadding()
                    .graphicsLayer {
                        translationY = panelOffsetFraction * 48.dp.toPx()
                        scaleX = panelScale
                        scaleY = panelScale
                        alpha = panelAlpha
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = CardColor,
                contentColor = Ink,
                tonalElevation = 0.dp,
                shadowElevation = 16.dp
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .width(8.dp)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(courseAccentFor(course))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "课程详情",
                            style = MaterialTheme.typography.labelLarge,
                            color = MutedInk
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = course.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "时间",
                        style = MaterialTheme.typography.labelMedium,
                        color = MutedInk
                    )
                    Text(
                        text = periodRangeLabel(course) + " · " + periodTimeRange(course),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "上课地点",
                        style = MaterialTheme.typography.labelMedium,
                        color = MutedInk
                    )
                    Text(
                        text = displayLocation(course.room).ifBlank { "地点待确认" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink
                    )
                    if (course.teacher.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "教师：" + course.teacher,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedInk
                        )
                    }
                }
            }
        }
    }
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
                    shadowElevation = 18.dp
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
private fun ScheduleHeader(
    title: String = "课程表",
    today: Calendar,
    onImportClick: () -> Unit = {},
    onReminderClick: () -> Unit = {},
    showActions: Boolean = true,
    showReminder: Boolean = true,
    showEdit: Boolean = false,
    editing: Boolean = false,
    onEditClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
                color = Ink
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatToday(today),
                style = MaterialTheme.typography.bodyMedium,
                color = MutedInk
            )
        }
        if (showActions) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showReminder) {
                    PillButton(text = "提醒", filled = false, onClick = onReminderClick)
                }
                if (showEdit) {
                    PillButton(
                        text = if (editing) "完成" else "编辑",
                        filled = editing,
                        onClick = onEditClick
                    )
                }
                PillButton(text = "导入", filled = false, onClick = onImportClick)
            }
        }
    }
}

@Composable
private fun PillButton(text: String, filled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interactionSource, pressedScale = 0.95f)
    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(shape)
            .background(if (filled) Ink else Gray100)
            .border(
                width = 1.dp,
                color = if (filled) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                shape = shape
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (filled) CardColor else Ink
        )
    }
}

@Composable
private fun CompactActionButton(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(11.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interactionSource, pressedScale = 0.96f)
    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(shape)
            .background(Gray50)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Ink
        )
    }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("课程提醒") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "课程提醒",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (status.remindersEnabled) "上课前 20 分钟提醒" else "已关闭，不会安排提醒",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedInk
                        )
                    }
                    Switch(
                        checked = status.remindersEnabled,
                        onCheckedChange = onToggle
                    )
                }
                Text(
                    text = when {
                        !status.notificationPermissionGranted -> "通知权限未授予。"
                        !status.notificationsEnabled -> "系统通知已关闭。"
                        else -> "通知不会创建闹钟页面，可在系统设置中随时关闭。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!status.notificationPermissionGranted) {
                    TextButton(onClick = onRequestPermission) {
                        Text("授权通知权限")
                    }
                }
                if (status.remindersEnabled && !status.exactAlarmAllowed) {
                    Text(
                        text = "未授权精确闹钟，将使用系统允许的非精确提醒。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedInk
                    )
                    TextButton(onClick = onRequestExactAlarm) {
                        Text("允许更准的提醒")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onSendTest) {
                Text("发送测试")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun WeekNavigator(
    weekStart: Calendar,
    weekOffset: Int,
    onPrevious: () -> Unit,
    onToday: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompactActionButton(text = "‹", onClick = onPrevious)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = scheduleWeekLabel(weekStart),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = formatDateRange(weekStart),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (weekOffset != 0) {
                CompactActionButton(text = "本周", onClick = onToday)
            }
            CompactActionButton(text = "›", onClick = onNext)
        }
    }
}

@Composable
private fun NextCourseCard(
    focus: FocusCourse,
    today: Calendar,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interactionSource)
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(shape)
            .background(CardColor)
            .border(1.dp, Hairline, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(7.dp)
                            .height(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Accent)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (focus.isInProgress) "正在进行" else "下一节",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = AccentDeep
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = focusDateLabel(focus, today),
                        style = MaterialTheme.typography.labelMedium,
                        color = MutedInk
                    )
                }
                Spacer(modifier = Modifier.height(7.dp))
                Text(
                    text = focus.course.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = periodRangeLabel(focus.course) + " · " + periodTimeRange(focus.course),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = displayLocation(focus.course.room).ifBlank { "地点待确认" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Ink
                )
            }
            Text(
                text = "›",
                fontSize = 24.sp,
                color = AccentDeep
            )
        }
    }
}

@Composable
private fun ScheduleGrid(
    today: Calendar,
    courses: List<Course>,
    focus: FocusCourse?,
    selectedWeekOffset: Int,
    editMode: Boolean,
    rowHeight: Dp,
    onAddCourse: (Int, Int) -> Unit,
    onEditCourse: (Course) -> Unit,
    onCourseClick: (Course) -> Unit
) {
    val hasWeekendCourses = courses.any { it.dayIndex >= 5 }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        ScheduleGridSection(
            today = today,
            courses = courses,
            focus = focus,
            selectedWeekOffset = selectedWeekOffset,
            editMode = editMode,
            rowHeight = rowHeight,
            visibleDays = (0..4).toList(),
            onAddCourse = onAddCourse,
            onEditCourse = onEditCourse,
            onCourseClick = onCourseClick
        )
        if (hasWeekendCourses) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "周末课程",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MutedInk,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
            ScheduleGridSection(
                today = today,
                courses = courses,
                focus = focus,
                selectedWeekOffset = selectedWeekOffset,
                editMode = editMode,
                rowHeight = rowHeight,
                visibleDays = listOf(5, 6),
                onAddCourse = onAddCourse,
                onEditCourse = onEditCourse,
                onCourseClick = onCourseClick
            )
        }
    }
}

@Composable
private fun ScheduleGridSection(
    today: Calendar,
    courses: List<Course>,
    focus: FocusCourse?,
    selectedWeekOffset: Int,
    editMode: Boolean,
    rowHeight: Dp,
    visibleDays: List<Int>,
    onAddCourse: (Int, Int) -> Unit,
    onEditCourse: (Course) -> Unit,
    onCourseClick: (Course) -> Unit
) {
    val timeColumnWidth = 26.dp

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 7.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Gray100)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(horizontal = 4.dp, vertical = 7.dp)
    ) {
        val cellWidth = (maxWidth - timeColumnWidth - 8.dp) / visibleDays.count()
        Column {
            Row {
                Box(
                    modifier = Modifier
                        .width(timeColumnWidth)
                        .height(36.dp)
                )
                visibleDays.forEach { dayIndex ->
                    DayHeader(
                        label = weekDayLabels[dayIndex],
                        isToday = selectedWeekOffset == 0 && dayIndex == currentDayIndex(today),
                        modifier = Modifier.width(cellWidth)
                    )
                }
            }
            Row {
                Column(modifier = Modifier.width(timeColumnWidth)) {
                    periods.forEach { period ->
                        Box(
                            modifier = Modifier
                                .width(timeColumnWidth)
                                .height(rowHeight),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Text(
                                text = period.label.replace("第 ", ""),
                                modifier = Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                visibleDays.forEach { dayIndex ->
                    Column(modifier = Modifier.width(cellWidth)) {
                        var periodIndex = 0
                        while (periodIndex < periods.size) {
                            val slotPeriod = periodIndex
                            val course = courses.firstOrNull {
                                it.dayIndex == dayIndex && it.period == slotPeriod
                            }
                            val nextSlotIsEmpty = slotPeriod % 2 == 0 &&
                                slotPeriod + 1 < periods.size &&
                                courses.none { it.dayIndex == dayIndex && it.period == slotPeriod + 1 }
                            val span = if (course == null) {
                                if (nextSlotIsEmpty) 2 else 1
                            } else {
                                (course.endPeriod - course.period + 1)
                                    .coerceIn(1, periods.size - periodIndex)
                            }
                            val isFocus = focus != null &&
                                selectedWeekOffset == focus.weekOffset &&
                                focus.dayIndex == dayIndex &&
                                focus.course.id == course?.id
                            CourseSlot(
                                course = course,
                                isFocus = isFocus,
                                isInProgress = isFocus && focus?.isInProgress == true,
                                editMode = editMode,
                                width = cellWidth,
                                height = rowHeight * span,
                                onClick = {
                                    if (course != null) {
                                        if (editMode) onEditCourse(course) else onCourseClick(course)
                                    } else if (editMode) {
                                        onAddCourse(dayIndex, (slotPeriod / 2) * 2)
                                    }
                                }
                            )
                            periodIndex += span
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(
    label: String,
    isToday: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "周$label",
            modifier = Modifier
                .then(
                    if (isToday) {
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Ink)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 12.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Medium,
            color = if (isToday) CardColor else MutedInk
        )
    }
}

@Composable
private fun CourseSlot(
    course: Course?,
    isFocus: Boolean,
    isInProgress: Boolean,
    editMode: Boolean,
    width: Dp,
    height: Dp,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(9.dp)
    val borderColor = if (isInProgress) AccentDeep else Hairline
    val borderWidth = if (isInProgress) 1.5.dp else 0.5.dp
    val titleLines = if (height >= 72.dp) 2 else 1
    val roomLines = when {
        height >= 160.dp -> 6
        height >= 128.dp -> 5
        height >= 100.dp -> 4
        height >= 76.dp -> 3
        else -> 1
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interactionSource, pressedScale = 0.96f)
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .padding(1.dp)
            .clip(shape)
            .background(
                when {
                    course != null -> if (isFocus) CourseSlotFill else CourseSlotFill
                    editMode -> CardColor
                    else -> Color.Transparent
                }
            )
            .border(
                width = borderWidth,
                color = borderColor,
                shape = shape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = course != null || editMode,
                onClick = onClick
            )
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        if (course == null) {
            if (editMode) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+",
                        style = MaterialTheme.typography.titleMedium,
                        color = FaintInk
                    )
                }
            } else {
                Spacer(modifier = Modifier.fillMaxSize())
            }
        } else {
            val location = displayLocation(course.room)
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.34f),
                    contentAlignment = Alignment.TopStart
                ) {
                    Text(
                        text = course.name,
                        maxLines = titleLines,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 16.sp),
                        fontWeight = FontWeight.Medium,
                        color = Ink
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.66f),
                    contentAlignment = Alignment.TopStart
                ) {
                    if (location.isNotBlank()) {
                        Text(
                            text = location,
                            maxLines = roomLines,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 15.sp),
                            fontWeight = FontWeight.Medium,
                            color = Ink
                        )
                    }
                }
            }
        }
    }
}

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
    onDismissed: () -> Unit,
    onSave: (Course) -> Unit,
    onDelete: (Course) -> Unit
) {
    var name by remember(course?.id, initialDay, initialPeriod) {
        mutableStateOf(course?.name.orEmpty())
    }
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
    val shape = RoundedCornerShape(14.dp)
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

private fun periodTimeRange(course: Course): String {
    val start = periods[course.period].startMinutes
    val end = periods[course.endPeriod.coerceIn(course.period, periods.lastIndex)].endMinutes
    fun format(minutes: Int): String = String.format(Locale.CHINA, "%02d:%02d", minutes / 60, minutes % 60)
    return format(start) + " - " + format(end)
}

private fun periodRangeLabel(course: Course): String = periodRangeLabel(course.period, course.endPeriod)

private fun periodRangeLabel(startPeriod: Int, endPeriod: Int): String {
    return if (endPeriod <= startPeriod) {
        periods[startPeriod].label
    } else {
        "第 " + (startPeriod + 1) + "-" + (endPeriod + 1) + " 节"
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

private fun mondayOfCurrentWeek(source: Calendar): Calendar {
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

private fun currentDayIndex(calendar: Calendar = Calendar.getInstance()): Int {
    val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
    return if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
}

private fun Calendar.cloneCalendar(): Calendar = (clone() as Calendar)

private fun formatToday(calendar: Calendar): String {
    val date = SimpleDateFormat("M月d日", Locale.CHINA).format(calendar.time)
    return "$date · 星期${weekDayLabels[currentDayIndex(calendar)]}"
}

private fun formatDateRange(weekStart: Calendar): String {
    val end = weekStart.cloneCalendar().apply { add(Calendar.DAY_OF_MONTH, 6) }
    val formatter = SimpleDateFormat("M月d日", Locale.CHINA)
    return "${formatter.format(weekStart.time)} - ${formatter.format(end.time)}"
}

private fun scheduleWeekLabel(weekStart: Calendar): String {
    val weekNumber = scheduleWeekNumber(weekStart)
    return if (weekNumber < 1) "开学前" else "第 $weekNumber 周"
}

private fun scheduleWeekNumber(weekStart: Calendar): Int {
    val firstWeek = mondayOfCurrentWeek(scheduleFirstWeekStart)
    val normalizedWeek = mondayOfCurrentWeek(weekStart)
    val daysFromFirstWeek = ((normalizedWeek.timeInMillis - firstWeek.timeInMillis) / (24 * 60 * 60 * 1000L)).toInt()
    return daysFromFirstWeek / 7 + 1
}

private fun focusDateLabel(focus: FocusCourse, today: Calendar): String {
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
