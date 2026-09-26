package com.burgess.schedule.backup

import com.burgess.schedule.data.CourseEntity
import com.burgess.schedule.data.CourseSourceType
import com.burgess.schedule.ui.model.courseAccentFor
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

internal const val SCHEDULE_BACKUP_FORMAT = "burgess-course-schedule"
internal const val SCHEDULE_BACKUP_SCHEMA_VERSION = 1

internal data class ScheduleBackup(
    val schemaVersion: Int,
    val appVersion: String,
    val exportedAt: String,
    val courses: List<ScheduleBackupCourse>
)

/** Stable file DTO: Room's primary key is deliberately not part of the file format. */
internal data class ScheduleBackupCourse(
    val name: String,
    val teacher: String,
    val room: String,
    val dayIndex: Int,
    val period: Int,
    val endPeriod: Int,
    val weekNumber: Int,
    val colorValue: Int,
    val shortName: String?,
    val sourceType: String,
    val sourceSemesterKey: String?,
    val externalKey: String?
) {
    fun toEntity(id: Long): CourseEntity = CourseEntity(
        id = id,
        name = name,
        teacher = teacher,
        room = room,
        dayIndex = dayIndex,
        period = period,
        endPeriod = endPeriod,
        weekNumber = weekNumber,
        colorValue = colorValue.takeIf { it != 0 } ?: courseAccentFor(id).toArgb(),
        shortName = shortName,
        sourceType = sourceType,
        sourceSemesterKey = sourceSemesterKey,
        externalKey = externalKey
    )

    internal fun schoolIdentity(): Pair<String, String>? =
        if (sourceType == CourseSourceType.SDBI) {
            sourceSemesterKey?.let { semester -> externalKey?.let { semester to it } }
        } else {
            null
        }

    internal fun nonSchoolFingerprint(): String = listOf(
        normalize(name),
        normalize(teacher),
        normalize(room),
        dayIndex.toString(),
        period.toString(),
        endPeriod.toString(),
        weekNumber.toString()
    ).joinToString("|")

    private fun normalize(value: String): String = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
}

internal sealed interface ScheduleBackupParseResult {
    data class Valid(
        val backup: ScheduleBackup,
        val invalidCourseCount: Int,
        val duplicateSchoolIdentityGroups: Int
    ) : ScheduleBackupParseResult {
        val canImport: Boolean
            get() = invalidCourseCount == 0 && duplicateSchoolIdentityGroups == 0
    }

    data class Invalid(val reason: String) : ScheduleBackupParseResult
    data class UnsupportedNewerVersion(val schemaVersion: Int) : ScheduleBackupParseResult
}

internal object ScheduleBackupCodec {
    fun encode(courses: List<CourseEntity>, appVersion: String, exportedAt: String): String {
        val root = JSONObject()
            .put("format", SCHEDULE_BACKUP_FORMAT)
            .put("schemaVersion", SCHEDULE_BACKUP_SCHEMA_VERSION)
            .put("appVersion", appVersion)
            .put("exportedAt", exportedAt)
            .put("courses", JSONArray().apply {
                courses.forEach { put(it.toBackupCourse().toJson()) }
            })
        return root.toString(2)
    }

    fun parse(json: String): ScheduleBackupParseResult {
        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            return ScheduleBackupParseResult.Invalid("文件不是有效的 JSON，或内容已损坏。")
        } catch (_: RuntimeException) {
            return ScheduleBackupParseResult.Invalid("无法读取备份文件。")
        }

        return try {
            if (root.requiredString("format") != SCHEDULE_BACKUP_FORMAT) {
                return ScheduleBackupParseResult.Invalid("这不是课程表 App 的备份文件。")
            }
            val schemaVersion = root.requiredInt("schemaVersion")
            if (schemaVersion > SCHEDULE_BACKUP_SCHEMA_VERSION) {
                return ScheduleBackupParseResult.UnsupportedNewerVersion(schemaVersion)
            }
            if (schemaVersion != SCHEDULE_BACKUP_SCHEMA_VERSION) {
                return ScheduleBackupParseResult.Invalid("该备份版本暂不支持。")
            }

            val appVersion = root.requiredString("appVersion")
            val exportedAt = root.requiredString("exportedAt")
            val array = root.opt("courses") as? JSONArray
                ?: return ScheduleBackupParseResult.Invalid("备份中的课程列表格式错误。")

            var invalidCount = 0
            val courses = buildList {
                for (index in 0 until array.length()) {
                    try {
                        add((array.opt(index) as? JSONObject
                            ?: throw BackupFieldException("课程记录格式错误。"))
                            .toBackupCourse())
                    } catch (_: BackupFieldException) {
                        invalidCount += 1
                    } catch (_: RuntimeException) {
                        invalidCount += 1
                    }
                }
            }
            val duplicateGroups = courses
                .mapNotNull(ScheduleBackupCourse::schoolIdentity)
                .groupingBy { it }
                .eachCount()
                .count { (_, count) -> count > 1 }

            ScheduleBackupParseResult.Valid(
                backup = ScheduleBackup(schemaVersion, appVersion, exportedAt, courses),
                invalidCourseCount = invalidCount,
                duplicateSchoolIdentityGroups = duplicateGroups
            )
        } catch (exception: BackupFieldException) {
            ScheduleBackupParseResult.Invalid(exception.message ?: "备份结构错误。")
        } catch (_: RuntimeException) {
            ScheduleBackupParseResult.Invalid("备份结构错误，无法读取。")
        }
    }

    private fun CourseEntity.toBackupCourse() = ScheduleBackupCourse(
        name = name,
        teacher = teacher,
        room = room,
        dayIndex = dayIndex,
        period = period,
        endPeriod = endPeriod,
        weekNumber = weekNumber,
        colorValue = colorValue.takeIf { it != 0 } ?: courseAccentFor(id).toArgb(),
        shortName = shortName,
        sourceType = sourceType,
        sourceSemesterKey = sourceSemesterKey,
        externalKey = externalKey
    )

    private fun ScheduleBackupCourse.toJson() = JSONObject()
        .put("name", name)
        .put("teacher", teacher)
        .put("room", room)
        .put("dayIndex", dayIndex)
        .put("period", period)
        .put("endPeriod", endPeriod)
        .put("weekNumber", weekNumber)
        .put("colorValue", colorValue)
        .putNullable("shortName", shortName)
        .put("sourceType", sourceType)
        .putNullable("sourceSemesterKey", sourceSemesterKey)
        .putNullable("externalKey", externalKey)

    private fun JSONObject.toBackupCourse(): ScheduleBackupCourse {
        val name = requiredString("name").trim()
        val teacher = requiredString("teacher")
        val room = requiredString("room")
        val dayIndex = requiredInt("dayIndex")
        val period = requiredInt("period")
        val endPeriod = requiredInt("endPeriod")
        val weekNumber = requiredInt("weekNumber")
        val colorValue = requiredInt("colorValue")
        val shortName = nullableString("shortName")
        val sourceType = requiredString("sourceType")
        val sourceSemesterKey = nullableString("sourceSemesterKey")
        val externalKey = nullableString("externalKey")

        if (name.isBlank()) throw BackupFieldException("课程名称不能为空。")
        if (dayIndex !in 0..6) throw BackupFieldException("星期超出支持范围。")
        if (period !in 0..13 || endPeriod !in period..13) {
            throw BackupFieldException("课程节次超出支持范围。")
        }
        if (weekNumber !in 0..200) throw BackupFieldException("课程周次超出支持范围。")
        if (sourceType != CourseSourceType.NON_SDBI && sourceType != CourseSourceType.SDBI) {
            throw BackupFieldException("课程来源类型不受支持。")
        }
        if (sourceType == CourseSourceType.SDBI &&
            (sourceSemesterKey.isNullOrBlank() || externalKey.isNullOrBlank())
        ) {
            throw BackupFieldException("学校课程缺少同步来源标识。")
        }

        return ScheduleBackupCourse(
            name, teacher, room, dayIndex, period, endPeriod, weekNumber,
            colorValue, shortName, sourceType, sourceSemesterKey, externalKey
        )
    }

    private fun JSONObject.requiredString(name: String): String =
        opt(name) as? String ?: throw BackupFieldException("字段 $name 类型错误。")

    private fun JSONObject.nullableString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return opt(name) as? String ?: throw BackupFieldException("字段 $name 类型错误。")
    }

    private fun JSONObject.requiredInt(name: String): Int {
        val number = opt(name) as? Number ?: throw BackupFieldException("字段 $name 类型错误。")
        val value = number.toDouble()
        if (!value.isFinite() || value % 1.0 != 0.0 || value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
            throw BackupFieldException("字段 $name 超出支持范围。")
        }
        return value.toInt()
    }

    private fun JSONObject.putNullable(name: String, value: String?): JSONObject =
        put(name, value ?: JSONObject.NULL)

    private class BackupFieldException(message: String) : IllegalArgumentException(message)
}
