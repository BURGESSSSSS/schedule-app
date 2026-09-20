package com.burgess.schedule.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.room.withTransaction
import com.burgess.schedule.data.CourseDatabase
import com.burgess.schedule.notifications.ReminderScheduler
import com.burgess.schedule.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun BackupActions(onMessage: suspend (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember { CourseDatabase.getInstance(context) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                val text = BackupCodec.encode(database.courseDao().getAll(), database.pickupCodeDao().observeAll().first())
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    ?: error("无法写入文件，请选择其他位置")
            } }
            onMessage(if (result.isSuccess) "备份已导出" else "导出失败：${result.exceptionOrNull()?.message}")
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        require(buffer.size() + count <= BackupCodec.MAX_BYTES) { "文件超过 8MB，请选择有效备份" }
                        buffer.write(chunk, 0, count)
                    }
                    buffer.toByteArray()
                } ?: error("无法读取文件，请重新选择")
                val backup = BackupCodec.decode(bytes.toString(Charsets.UTF_8))
                database.withTransaction {
                    database.courseDao().insertAll(backup.courses)
                    database.pickupCodeDao().insertAll(backup.pickups)
                }
            } }
            if (result.isSuccess) ReminderScheduler.rescheduleFromDatabase(context)
            onMessage(if (result.isSuccess) "备份已导入，同编号记录已更新" else "导入失败：${result.exceptionOrNull()?.message}")
        }
    }
    SectionLabel("数据")
    ListGroup {
        ListRow("导出备份", "数据保存在本机，备份后可迁移到新手机", { export.launch("课程表备份.json") })
        ListRow("导入备份", "合并 JSON 备份，同编号记录将更新", { import.launch(arrayOf("application/json")) })
    }
}
