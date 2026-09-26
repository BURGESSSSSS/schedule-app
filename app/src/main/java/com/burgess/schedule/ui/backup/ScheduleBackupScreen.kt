package com.burgess.schedule.ui.backup

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.burgess.schedule.BuildConfig
import com.burgess.schedule.backup.ScheduleBackupCodec
import com.burgess.schedule.backup.ScheduleBackupImportMode
import com.burgess.schedule.backup.ScheduleBackupImportResult
import com.burgess.schedule.backup.ScheduleBackupParseResult
import com.burgess.schedule.backup.ScheduleBackupRepository
import com.burgess.schedule.data.CourseDatabase
import com.burgess.schedule.ui.components.AppCard
import com.burgess.schedule.ui.components.PillButton
import com.burgess.schedule.ui.components.PillStyle
import com.burgess.schedule.ui.components.SectionLabel
import com.burgess.schedule.ui.components.SubPageScaffold
import com.burgess.schedule.ui.theme.AppType
import com.burgess.schedule.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_BACKUP_BYTES = 8 * 1024 * 1024

@Composable
internal fun ScheduleBackupScreen(
    database: CourseDatabase,
    onBack: () -> Unit,
    onViewToday: () -> Unit,
    onViewWeek: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val repository = remember(database) { ScheduleBackupRepository(database) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ScheduleBackupParseResult.Valid?>(null) }
    var currentCourseCount by remember { mutableStateOf(0) }
    var showRestoreConfirmation by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf<ScheduleBackupImportResult?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                error = null
                completed = null
                try {
                    withContext(Dispatchers.IO) {
                        val exportedAt = nowIsoTimestamp()
                        val json = repository.export(BuildConfig.VERSION_NAME, exportedAt)
                        writeUtf8(context.contentResolver, uri, json)
                    }
                    val count = repository.courseCount()
                    error = "课程表备份已导出 · $count 门课程 · 文件版本 1"
                } catch (_: Exception) {
                    error = "导出失败，请检查保存位置后重试。"
                } finally {
                    busy = false
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                error = null
                preview = null
                completed = null
                try {
                    val text = withContext(Dispatchers.IO) {
                        readUtf8(context.contentResolver, uri)
                    }
                    when (val parsed = ScheduleBackupCodec.parse(text)) {
                        is ScheduleBackupParseResult.Valid -> {
                            preview = parsed
                            currentCourseCount = repository.courseCount()
                            if (!parsed.canImport) {
                                error = buildString {
                                    if (parsed.invalidCourseCount > 0) append("有 ${parsed.invalidCourseCount} 条课程记录格式错误。")
                                    if (parsed.duplicateSchoolIdentityGroups > 0) {
                                        append("有 ${parsed.duplicateSchoolIdentityGroups} 组重复的学校课程来源标识。")
                                    }
                                    append("为避免误改课程，已阻止导入。")
                                }
                            }
                        }
                        is ScheduleBackupParseResult.Invalid -> error = parsed.reason
                        is ScheduleBackupParseResult.UnsupportedNewerVersion -> {
                            error = "该备份由更新版本的软件创建，当前版本暂不支持。"
                        }
                    }
                } catch (_: BackupFileTooLargeException) {
                    error = "备份文件超过 8 MB，无法导入。"
                } catch (_: Exception) {
                    error = "无法读取备份文件，请确认文件可访问且未损坏。"
                } finally {
                    busy = false
                }
            }
        }
    }

    fun runImport(mode: ScheduleBackupImportMode) {
        val backup = preview?.backup ?: return
        if (preview?.canImport != true || busy) return
        scope.launch {
            busy = true
            error = null
            try {
                completed = repository.importBackup(backup, mode)
                preview = null
            } catch (_: Exception) {
                error = if (mode == ScheduleBackupImportMode.RESTORE) {
                    "恢复失败，原课程表已保留。请重试或检查备份文件。"
                } else {
                    "合并导入失败，课程表未完成修改。请重试。"
                }
            } finally {
                busy = false
                showRestoreConfirmation = false
            }
        }
    }

    SubPageScaffold("备份与恢复", onBack) {
        Text("仅备份本机课程表数据，不包含学校原始数据或连接信息。", style = AppType.body, color = LocalAppColors.current.mut)

        AppCard(Modifier.fillMaxWidth()) {
            Text("导出课程表", style = AppType.cardTitle, color = LocalAppColors.current.ink)
            Text("保存为 JSON 文件，可存到本机或云盘。", style = AppType.caption, color = LocalAppColors.current.mut)
            PillButton("选择保存位置", {
                if (!busy) {
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
                    exportLauncher.launch("课程表备份_$date.schedule.json")
                }
            }, PillStyle.Primary)
        }

        AppCard(Modifier.fillMaxWidth()) {
            Text("导入课程表", style = AppType.cardTitle, color = LocalAppColors.current.ink)
            Text("先预览备份，再选择合并或恢复。默认推荐合并导入。", style = AppType.caption, color = LocalAppColors.current.mut)
            PillButton("选择备份文件", {
                if (!busy) importLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
            }, PillStyle.Secondary)
        }

        if (busy) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text("正在处理课程表…", style = AppType.caption, color = LocalAppColors.current.mut)
        }

        error?.let { Text(it, style = AppType.caption, color = LocalAppColors.current.mut) }

        preview?.let { parsed ->
            AppCard(Modifier.fillMaxWidth()) {
                Text("备份预览", style = AppType.cardTitle, color = LocalAppColors.current.ink)
                SectionLabel("备份日期：${parsed.backup.exportedAt}")
                SectionLabel("备份版本：${parsed.backup.schemaVersion} · 创建应用 ${parsed.backup.appVersion}")
                SectionLabel("课程数量：${parsed.backup.courses.size + parsed.invalidCourseCount}")
                SectionLabel("当前本地课程：$currentCourseCount")
                if (parsed.invalidCourseCount > 0 || parsed.duplicateSchoolIdentityGroups > 0) {
                    SectionLabel("无效课程记录：${parsed.invalidCourseCount} · 重复学校来源组：${parsed.duplicateSchoolIdentityGroups}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(
                        "合并导入",
                        { runImport(ScheduleBackupImportMode.MERGE) },
                        PillStyle.Primary
                    )
                    PillButton(
                        "恢复备份",
                        { if (parsed.canImport && !busy) showRestoreConfirmation = true },
                        PillStyle.Secondary,
                        danger = true
                    )
                }
            }
        }

        completed?.let { result ->
            AppCard(Modifier.fillMaxWidth()) {
                Text("课程表导入完成", style = AppType.cardTitle, color = LocalAppColors.current.ink)
                if (result.mode == ScheduleBackupImportMode.RESTORE) {
                    SectionLabel("恢复课程：${result.restored} 门")
                } else {
                    SectionLabel("新增：${result.inserted} · 更新：${result.updated} · 去重：${result.deduplicated}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("查看今日", onViewToday, PillStyle.Primary, small = true)
                    PillButton("查看周视图", onViewWeek, PillStyle.Secondary, small = true)
                }
            }
        }
    }

    if (showRestoreConfirmation) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirmation = false },
            title = { Text("确认恢复备份") },
            text = { Text("恢复备份会替换当前课程表。建议先导出当前课程表作为备份。") },
            confirmButton = {
                TextButton(onClick = { runImport(ScheduleBackupImportMode.RESTORE) }) {
                    Text("确认恢复", color = LocalAppColors.current.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirmation = false }) { Text("取消") }
            }
        )
    }
}

private suspend fun writeUtf8(resolver: ContentResolver, uri: Uri, text: String) {
    val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("Unable to open backup destination")
    output.use { it.write(text.toByteArray(StandardCharsets.UTF_8)) }
}

private suspend fun readUtf8(resolver: ContentResolver, uri: Uri): String {
    val input = resolver.openInputStream(uri) ?: throw IOException("Unable to open backup source")
    return input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_BACKUP_BYTES) throw BackupFileTooLargeException()
            output.write(buffer, 0, count)
        }
        output.toString(StandardCharsets.UTF_8.name())
    }
}

private fun nowIsoTimestamp(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }.format(Date())

private class BackupFileTooLargeException : IOException()
