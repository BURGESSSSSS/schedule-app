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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.burgess.schedule.ui.week.*
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
import androidx.lifecycle.repeatOnLifecycle
import com.burgess.schedule.ui.today.*
import com.burgess.schedule.ui.components.InkCard
import com.burgess.schedule.ui.components.AppChip
import com.burgess.schedule.ui.components.SectionLabel
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import com.burgess.schedule.ui.components.CourseTransitionHost
import com.burgess.schedule.ui.components.CourseDetail
import com.burgess.schedule.ui.components.CourseSource
import com.burgess.schedule.ui.components.courseTitle
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

private data class Course(
    val id: Long,
    val name: String,
    val teacher: String,
    val room: String,
    val dayIndex: Int,
    val period: Int,
    val color: Color,
    val endPeriod: Int = period,
    val weekNumber: Int = 0,
    val shortName: String? = null
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
        shortName = shortName,
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
        colorValue = color.toArgb(),
        shortName = shortName
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
    LaunchedEffect(Unit) {
        (hostContext as LifecycleOwner).lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
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

    CourseTransitionHost(
        detail = expandedCourse?.let { CourseDetail(it.id, it.name, periodTimeRange(it), "周${weekDayLabels[it.dayIndex]} · ${periodRangeLabel(it)}", it.room, courseAccentFor(it)) },
        onDismiss = { expandedCourse = null },
        onEdit = { expandedCourse?.let { openEditor(course = it) }; expandedCourse = null },
        onDelete = {
            expandedCourse?.let { removed ->
                expandedCourse = null
                scope.launch {
                    courseDao.delete(removed.toEntity())
                    val result = withTimeoutOrNull(5_000L) { snackbarHostState.showSnackbar("已删除", "撤销", duration = SnackbarDuration.Indefinite) }
                    if (result == SnackbarResult.ActionPerformed) courseDao.upsert(removed.toEntity())
                }
            }
        }
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
                            courses = courses,
                            focus = focus,
                            pendingPickups = pendingPickups,
                            onPickupOpen = { quickPickupEntry = it },
                            onPickupCopy = { copyPickupCode(it) },
                            onCourseClick = { expandedCourse = it },
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
        Dialog(onDismissRequest = { designVisible = false }) { com.burgess.schedule.ui.components.DesignPreview() }
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
    BackHandler(onBack = onBack)
    SubPageScaffold("开发日志", onBack) {
        devLogEntries.forEachIndexed { index, entry ->
            key(entry.version) {
                var expanded by remember { mutableStateOf(index == 0) }
                com.burgess.schedule.ui.components.AppCard(Modifier.fillMaxWidth(), onClick = { expanded = !expanded }) {
                    Text(entry.version, style = AppType.cardTitle, color = Ink)
                    SectionLabel(entry.date)
                    AnimatedVisibility(expanded, enter = fadeIn(tween(Motion.TabCrossfadeMs)), exit = fadeOut(tween(Motion.TabCrossfadeMs))) {
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
                            if (entry.intro.isNotBlank()) Text(entry.intro, style = AppType.body, color = MutedInk)
                            entry.sections.forEach { section ->
                                Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
                                    Box(Modifier.padding(top = AppSpace.cellVertical).size(AppSpace.gridGap).background(MutedInk, AppShape.pill))
                                    Column {
                                        Text(section.title, style = AppType.cardTitle, color = Ink)
                                        if (section.body.isNotBlank()) Text(section.body, style = AppType.body, color = MutedInk)
                                    }
                                }
                            }
                            if (entry.closing.isNotBlank()) Text(entry.closing, style = AppType.body, color = MutedInk)
                        }
                    }
                }
            }
        }
    }
}
@Preview @Composable private fun DevLogLight() { 课程表Theme(false) { DevLogScreen({}) } }
@Preview @Composable private fun DevLogDark() { 课程表Theme(true) { DevLogScreen({}) } }

@Composable
private fun rememberPressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = Motion.PressScale
): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(Motion.PressMs),
        label = "press scale"
    )
    return scale
}

@Composable
private fun MoreScreen(today: Calendar, onReminderClick: () -> Unit, onPickupClick: () -> Unit, onDevLogClick: () -> Unit, onDesignClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val theme by remember { Appearance.theme(context) }.collectAsState(initial = "跟随系统")
    var themeVisible by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AppSpace.page), verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
            Text("更多", style = AppType.title, color = Ink)
            SectionLabel("设置与工具")
            Spacer(Modifier.height(AppSpace.gap))
            SectionLabel("功能")
            ListGroup {
                ListRow("课程提醒", "通知权限和测试通知", onReminderClick)
                HorizontalDivider(color = Hairline)
                ListRow("取件码", "包裹取件码和驿站", onPickupClick)
            }
            Spacer(Modifier.height(AppSpace.gap))
            SectionLabel("外观")
            ListGroup { ListRow("主题", theme, { themeVisible = true }) }
            Spacer(Modifier.height(AppSpace.gap))
            com.burgess.schedule.backup.BackupActions { snack.showSnackbar(it) }
            SectionLabel("关于")
            ListGroup {
                ListRow("开发日志", "查看更新记录", onDevLogClick)
                HorizontalDivider(color = Hairline)
                ListRow("Arihara", "为爱发电", {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("开发者邮箱", "suyu20651@gmail.com"))
                    scope.launch { snack.showSnackbar("已复制邮箱") }
                }, trailing = { Image(painterResource(R.drawable.developer_avatar), "开发者头像", Modifier.size(AppSpace.iconButton).clip(AppShape.pill), contentScale = ContentScale.Crop) })
            }
            if (BuildConfig.DEBUG) com.burgess.schedule.ui.components.PillButton("设计预览", onDesignClick, com.burgess.schedule.ui.components.PillStyle.Ghost, true)
            Spacer(Modifier.height(AppSpace.card * 2))
            Text("课程表 ${BuildConfig.VERSION_NAME} · 数据仅保存在本机", Modifier.fillMaxWidth(), style = AppType.caption, color = MutedInk, textAlign = TextAlign.Center)
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter)) { com.burgess.schedule.ui.components.AppSnackbar(it) }
    }
    if (themeVisible) BottomSheetContainer({ themeVisible = false }) {
        Text("主题", style = AppType.sheetTitle, color = Ink)
        listOf("跟随系统", "浅色", "深色").forEach { option ->
            ListRow(option, onClick = { scope.launch { Appearance.setTheme(context, option) }; themeVisible = false }, trailing = { androidx.compose.material3.RadioButton(theme == option, onClick = { scope.launch { Appearance.setTheme(context, option) }; themeVisible = false }) })
        }
    }
}
@Preview @Composable private fun MoreLight() { 课程表Theme(false) { MoreScreen(Calendar.getInstance(), {}, {}, {}, {}) } }
@Preview @Composable private fun MoreDark() { 课程表Theme(true) { MoreScreen(Calendar.getInstance(), {}, {}, {}, {}) } }


private fun Course.timedLesson(): TimedLesson {
    val date = SchoolSchedule.firstWeekStart().apply { add(Calendar.WEEK_OF_YEAR, weekNumber - 1); add(Calendar.DAY_OF_YEAR, dayIndex) }
    val start = date.cloneCalendar().apply { add(Calendar.MINUTE, periods[period].startMinutes) }.timeInMillis
    val end = date.cloneCalendar().apply { add(Calendar.MINUTE, periods[endPeriod].endMinutes) }.timeInMillis
    return TimedLesson(id, start, end)
}

@Composable
private fun TodayScreen(
    today: Calendar, courses: List<Course>, focus: FocusCourse?,
    pendingPickups: List<PickupCodeEntity>, onPickupOpen: (PickupCodeEntity) -> Unit,
    onPickupCopy: (String) -> Unit, onCourseClick: (Course) -> Unit,
    onImport: () -> Unit, onAdd: () -> Unit
) {
    val start = today.cloneCalendar().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val tomorrow = start.cloneCalendar().apply { add(Calendar.DAY_OF_YEAR, 1) }
    val timed = remember(courses) { courses.map { it.timedLesson() } }
    val state = timelineState(timed, today.timeInMillis, start.timeInMillis, tomorrow.timeInMillis)
    val byId = courses.associateBy { it.id }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AppSpace.page), verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("周" + weekDayLabels[currentDayIndex(today)], style = AppType.title, color = Ink)
                SectionLabel("${today.get(Calendar.MONTH) + 1}月${today.get(Calendar.DAY_OF_MONTH)}日 · 第 ${scheduleWeekNumber(mondayOfCurrentWeek(today))} 周")
            }
            AppChip(if (state.today.isEmpty()) "今天没课" else "${state.today.size} 节课")
        }
        if (state.state == DayState.EMPTY) {
            Text("还没有课程", style = AppType.sheetTitle, color = Ink)
            SectionLabel("导入课表截图，或手动添加第一门课程。")
            com.burgess.schedule.ui.components.PillButton("导入课表", onImport)
            com.burgess.schedule.ui.components.PillButton("手动添加", onAdd, com.burgess.schedule.ui.components.PillStyle.Secondary)
        }
        state.today.forEach { lesson ->
            key(lesson.id) {
                byId[lesson.id]?.let { course ->
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
                        Column(Modifier.width(AppSpace.timeline)) {
                            Text(SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(lesson.start)), style = AppType.caption, fontWeight = FontWeight.Medium, color = Ink)
                            Text(SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(lesson.end)), style = AppType.caption, color = MutedInk)
                        }
                        TimelineCourseCard(course, lesson, today.timeInMillis, state.focus?.id == lesson.id, Modifier.weight(1f), onCourseClick)
                    }
                }
            }
        }
        if (state.state == DayState.FINISHED) SectionLabel("今天的课都上完了")
        if (state.state == DayState.FREE || state.state == DayState.FINISHED) {
            state.next?.let { next ->
                val nextDate = Calendar.getInstance().apply { timeInMillis = next.start }
                val dayLabel = if (next.start < tomorrow.cloneCalendar().apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis) "明天" else "${nextDate.get(Calendar.MONTH) + 1}月${nextDate.get(Calendar.DAY_OF_MONTH)}日"
                if (state.state == DayState.FINISHED) SectionLabel(dayLabel)
                byId[next.id]?.let { course ->
                    TimelineCourseCard(course, next, today.timeInMillis, true, Modifier.fillMaxWidth(), onCourseClick, "$dayLabel ${SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(next.start))}", true)
                }
                val nextDayEnd = nextDate.cloneCalendar().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
                val rest = timed.filter { it.start >= next.start && it.start < nextDayEnd && it.id != next.id }.sortedBy { it.start }
                if (rest.isNotEmpty()) SectionLabel("${dayLabel}还有 ${rest.size} 节课")
                rest.forEach { lesson -> byId[lesson.id]?.let { TimelineCourseCard(it, lesson, today.timeInMillis, false, Modifier.fillMaxWidth(), onCourseClick) } }
            } ?: if (state.state != DayState.EMPTY) SectionLabel("暂时没有后续课程，可到周视图添加或导入。") else Unit
        }
        if (pendingPickups.isNotEmpty()) PickupBriefCard(pendingPickups, onPickupOpen, onPickupCopy)
    }
}

@Composable
private fun TimelineCourseCard(course: Course, lesson: TimedLesson, now: Long, highlighted: Boolean, modifier: Modifier, onClick: (Course) -> Unit, dateLabel: String = "", large: Boolean = false) {
    val active = now >= lesson.start && now < lesson.end
    val ended = now >= lesson.end
    val c = LocalAppColors.current
    val content: @Composable ColumnScope.() -> Unit = {
        if (highlighted) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.gridGap), verticalAlignment = Alignment.CenterVertically) {
                if (active) Box(Modifier.size(AppSpace.cellVertical).background(c.live, AppShape.pill))
                Text((if (active) "进行中" else "下一节") + " · " + (if (dateLabel.isEmpty()) "" else "$dateLabel · ") + countdown(if (active) lesson.end - now else lesson.start - now, active), style = AppType.caption, color = c.onInk)
            }
            Spacer(Modifier.height(AppSpace.gap))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.gridGap), verticalAlignment = Alignment.CenterVertically) {
            if (!highlighted) Box(Modifier.size(AppSpace.cellVertical).background(courseAccentFor(course).copy(alpha = if (ended) .5f else 1f), AppShape.pill))
            Text(course.name, modifier = Modifier.courseTitle(course.id), style = if (large) AppType.sheetTitle else AppType.cardTitle, color = if (highlighted) c.onInk else if (ended) c.mut else c.ink)
        }
        Text(periodRangeLabel(course) + " · " + displayLocation(course.room).ifBlank { "未填写" }, style = AppType.caption, color = if (highlighted) c.onInk else c.mut)
        if (active && highlighted) {
            var entered by remember(course.id) { mutableStateOf(false) }
            LaunchedEffect(course.id) { entered = true }
            val progress by animateFloatAsState(if (entered) ((now - lesson.start).toFloat() / (lesson.end - lesson.start)).coerceIn(0f, 1f) else 0f, tween(Motion.ProgressBarMs), label = "课程进度")
            Spacer(Modifier.height(AppSpace.gap))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(AppSpace.progress), color = c.onInk, trackColor = c.onInk.copy(alpha = .15f))
        }
    }
    CourseSource(course.id, modifier) {
        if (highlighted) InkCard(Modifier.fillMaxWidth(), onClick = { onClick(course) }, content = content)
        else com.burgess.schedule.ui.components.AppCard(Modifier.fillMaxWidth(), onClick = { onClick(course) }, content = content)
    }
}

@Preview @Composable private fun TodayLightPreview() { 课程表Theme(false) { TodayScreen(Calendar.getInstance(), emptyList(), null, emptyList(), {}, {}, {}, {}, {}) } }
@Preview @Composable private fun TodayDarkPreview() { 课程表Theme(true) { TodayScreen(Calendar.getInstance(), emptyList(), null, emptyList(), {}, {}, {}, {}, {}) } }

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
    val weekNumber = scheduleWeekNumber(selectedWeekStart)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = AppSpace.weekPage, vertical = AppSpace.page), verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("第 $weekNumber 周", style = AppType.title, color = Ink)
                SectionLabel(formatDateRange(selectedWeekStart))
            }
            com.burgess.schedule.ui.components.PillButton(if (editing) "完成" else "编辑", onToggleEditing, if (editing) com.burgess.schedule.ui.components.PillStyle.Primary else com.burgess.schedule.ui.components.PillStyle.Secondary, true)
            com.burgess.schedule.ui.components.PillButton("导入", { onImportClick(weekNumber) }, com.burgess.schedule.ui.components.PillStyle.Secondary, true)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.burgess.schedule.ui.components.CircleIconButton("‹", onPrevious)
            com.burgess.schedule.ui.components.CircleIconButton("›", onNext)
            if (weekOffset != 0) AppChip("回到本周", onToday)
            Spacer(Modifier.weight(1f))
            com.burgess.schedule.ui.components.PillButton("复制到下周", { onCopyNextWeek(weekNumber, courses) }, com.burgess.schedule.ui.components.PillStyle.Ghost, true)
        }
        if (courses.isEmpty()) SectionLabel(if (editing) "点击空白时段添加课程。" else "本周还没有课程，点击编辑或导入开始。")
        ScheduleGrid(today, courses, focus, weekOffset, editing, AppSpace.gridRow, { day, period -> onAddCourse(weekNumber, day, period) }, onEditCourse, onCourseClick)
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
    val shape = AppShape.card
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
private fun ScheduleGrid(today: Calendar, courses: List<Course>, focus: FocusCourse?, selectedWeekOffset: Int, editMode: Boolean, rowHeight: Dp, onAddCourse: (Int, Int) -> Unit, onEditCourse: (Course) -> Unit, onCourseClick: (Course) -> Unit) {
    val hasWeekend = courses.any { it.dayIndex >= 5 }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val fontScale = LocalConfiguration.current.fontScale.coerceAtLeast(1f)
    val height = AppSpace.gridRow * fontScale
    val weekStart = mondayOfCurrentWeek(today).apply { add(Calendar.WEEK_OF_YEAR, selectedWeekOffset) }
    LaunchedEffect(selectedWeekOffset, hasWeekend, scroll.maxValue) {
        if (scroll.maxValue != Int.MAX_VALUE) scroll.scrollTo(if (hasWeekend && selectedWeekOffset == 0 && currentDayIndex(today) >= 5) scroll.maxValue else 0)
    }
    LaunchedEffect(scroll.isScrollInProgress) {
        if (!scroll.isScrollInProgress && scroll.maxValue > 0 && scroll.maxValue != Int.MAX_VALUE) {
            val target = if (scroll.value > scroll.maxValue / 2) scroll.maxValue else 0
            if (scroll.value != target) scroll.animateScrollTo(target, tween(Motion.TabCrossfadeMs))
        }
    }
    if (hasWeekend) AppChip(if (scroll.value > scroll.maxValue / 2) "‹ 工作日" else "周末 ›") { scope.launch { scroll.animateScrollTo(if (scroll.value > scroll.maxValue / 2) 0 else scroll.maxValue, tween(Motion.TabCrossfadeMs)) } }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = (maxWidth - AppSpace.axis - AppSpace.gridGap * 5) / 5
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.gridGap)) {
            Column(Modifier.width(AppSpace.axis), verticalArrangement = Arrangement.spacedBy(AppSpace.gridGap)) {
                Spacer(Modifier.height(AppSpace.row))
                periods.indices.step(2).forEach { period ->
                    Box(Modifier.height(height)) { Text("%02d:%02d".format(periods[period].startMinutes / 60, periods[period].startMinutes % 60), style = AppType.micro, color = MutedInk) }
                }
            }
            Row(Modifier.horizontalScroll(scroll, enabled = hasWeekend), horizontalArrangement = Arrangement.spacedBy(AppSpace.gridGap)) {
                (0 until if (hasWeekend) 7 else 5).forEach { day ->
                    val date = weekStart.cloneCalendar().apply { add(Calendar.DAY_OF_YEAR, day) }
                    val isToday = selectedWeekOffset == 0 && currentDayIndex(today) == day
                    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(AppSpace.gridGap)) {
                        Column(Modifier.height(AppSpace.row).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("周${weekDayLabels[day]}", style = AppType.caption, color = MutedInk)
                            Box(Modifier.size(AppSpace.date).background(if (isToday) Ink else Color.Transparent, AppShape.pill), contentAlignment = Alignment.Center) { Text(date.get(Calendar.DAY_OF_MONTH).toString(), style = AppType.caption, color = if (isToday) LocalAppColors.current.onInk else Ink) }
                        }
                        var period = 0
                        while (period < periods.size) {
                            val current = period
                            val course = courses.firstOrNull { it.dayIndex == day && it.period / 2 == current / 2 }
                            val span = if (course == null) 1 else (course.endPeriod / 2 - current / 2 + 1).coerceAtLeast(1)
                            val slotHeight = height * span + AppSpace.gridGap * (span - 1)
                            key(day, current, course?.id) {
                                CourseSlot(course, focus?.course?.id == course?.id, focus?.isInProgress == true && focus.course.id == course?.id, editMode, width, slotHeight) {
                                    if (course != null) { if (editMode) onEditCourse(course) else onCourseClick(course) }
                                    else if (editMode) onAddCourse(day, current)
                                }
                            }
                            period += span * 2
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseSlot(course: Course?, isFocus: Boolean, isInProgress: Boolean, editMode: Boolean, width: Dp, height: Dp, onClick: () -> Unit) {
    val c = LocalAppColors.current
    val tint = course?.let { courseTint(courseAccentFor(it)) }
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, Motion.PressScale)
    CourseSource(course?.id ?: -(width.value.toLong() + height.value.toLong()), Modifier.width(width).height(height)) {
    Column(Modifier.width(width).height(height).graphicsLayer { scaleX = scale; scaleY = scale }.clip(AppShape.cell).background(tint?.first ?: c.ink.copy(alpha = .035f)).then(if (isInProgress) Modifier.border(AppSpace.line * 1.5f, c.ink, AppShape.cell) else Modifier).clickable(interaction, null, enabled = course != null || editMode, onClick = onClick).semantics {
        contentDescription = course?.let { "${it.name}，周${weekDayLabels[it.dayIndex]}，${periodTimeRange(it)}，${it.room.ifBlank { "未填写地点" }}" } ?: if (editMode) "添加课程" else "空闲时段"
    }.padding(horizontal = AppSpace.cellHorizontal, vertical = AppSpace.cellVertical), verticalArrangement = Arrangement.spacedBy(AppSpace.gridGap)) {
        if (course != null) {
            val label = course.shortName?.takeIf { it.isNotBlank() } ?: shortName(course.name)
            val latin = label.all { it.code < 128 }
            Text(label, modifier = Modifier.courseTitle(course.id), style = if (latin && label.length > 6) AppType.micro else AppType.cell, color = tint!!.second, maxLines = if (latin) 1 else 2, softWrap = !latin, overflow = TextOverflow.Ellipsis)
            Text(roomNumber(course.room).ifBlank { "未填写" }, style = AppType.micro, color = tint.second, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else if (editMode) Text("+", style = AppType.cardTitle, color = c.mut)
    }
    }
}

@Preview(widthDp = 360) @Composable private fun WeekLightPreview() { 课程表Theme(false) { ScheduleGrid(Calendar.getInstance(), emptyList(), null, 0, false, AppSpace.gridRow, { _, _ -> }, {}, {}) } }
@Preview(widthDp = 360, fontScale = 1.3f) @Composable private fun WeekDarkPreview() { 课程表Theme(true) { ScheduleGrid(Calendar.getInstance(), emptyList(), null, 0, true, AppSpace.gridRow, { _, _ -> }, {}, {}) } }

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
