package com.burgess.schedule.backup

import com.burgess.schedule.data.*
import org.json.JSONArray
import org.json.JSONObject

data class ScheduleBackup(val courses: List<CourseEntity>, val pickups: List<PickupCodeEntity>)

object BackupCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    fun encode(courses: List<CourseEntity>, pickups: List<PickupCodeEntity>): String = JSONObject().apply {
        put("format", "burgess-schedule"); put("version", 1)
        put("courses", JSONArray().apply { courses.forEach { c -> put(JSONObject().apply {
            put("id", c.id); put("name", c.name); put("teacher", c.teacher); put("room", c.room)
            put("dayIndex", c.dayIndex); put("period", c.period); put("endPeriod", c.endPeriod)
            put("weekNumber", c.weekNumber); put("colorValue", c.colorValue); put("shortName", c.shortName ?: JSONObject.NULL)
        }) } })
        put("pickups", JSONArray().apply { pickups.forEach { c -> put(JSONObject().apply {
            put("id", c.id); put("code", c.code); put("station", c.station); put("status", c.status)
            put("picked", c.picked); put("createdAt", c.createdAt)
        }) } })
    }.toString(2)

    fun decode(text: String): ScheduleBackup {
        require(text.toByteArray().size <= MAX_BYTES) { "备份超过 8MB，请选择本应用导出的备份" }
        val root = JSONObject(text)
        require(root.getString("format") == "burgess-schedule" && root.getInt("version") == 1) { "备份格式或版本不支持，请选择本应用导出的 JSON 文件" }
        val array = root.getJSONArray("courses")
        require(array.length() <= 10000) { "备份课程数超出限制" }
        val courses = (0 until array.length()).map { index ->
            val c = array.getJSONObject(index)
            CourseEntity(c.getLong("id"), c.getString("name"), c.getString("teacher"), c.getString("room"), c.getInt("dayIndex"), c.getInt("period"), c.getInt("endPeriod"), c.getInt("weekNumber"), c.getInt("colorValue"), if (c.isNull("shortName")) null else c.getString("shortName")).also {
                require(it.name.isNotBlank() && it.name.length <= 500 && it.dayIndex in 0..6 && it.period in 0..7 && it.endPeriod in it.period..7 && it.weekNumber >= 0) { "第 ${index + 1} 门课程内容无效，请检查备份文件" }
            }
        }
        val pickupArray = root.getJSONArray("pickups")
        require(pickupArray.length() <= 10000) { "取件码数量超出限制" }
        val pickups = (0 until pickupArray.length()).map { index ->
            val c = pickupArray.getJSONObject(index)
            PickupCodeEntity(c.getLong("id"), c.getString("code"), c.getString("station"), c.getString("status"), c.getBoolean("picked"), c.getLong("createdAt")).also { require(it.code.isNotBlank()) { "取件码不能为空" } }
        }
        require(courses.map { it.id }.distinct().size == courses.size) { "备份包含重复课程编号" }
        require(pickups.map { it.id }.distinct().size == pickups.size) { "备份包含重复取件码编号" }
        return ScheduleBackup(courses, pickups)
    }
}
