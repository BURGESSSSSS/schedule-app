package com.burgess.schedule.school.sync.importpreview

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal sealed interface SemesterSessionLoadResult {
    data object Empty : SemesterSessionLoadResult
    data class Loaded(val session: SemesterCollectionSession) : SemesterSessionLoadResult
    data class Invalid(val errorType: String) : SemesterSessionLoadResult
}

/** Stores only the sanitized, structured Debug collection state; response bodies and request metadata are never accepted. */
internal class SemesterCollectionStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, SESSION_DIRECTORY)
    private val atomicFile = AtomicFile(File(directory, SESSION_FILE))

    suspend fun load(): SemesterSessionLoadResult = withContext(Dispatchers.IO) {
        if (!atomicFile.baseFile.exists()) return@withContext SemesterSessionLoadResult.Empty
        try {
            val encoded = atomicFile.openRead().use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_SESSION_BYTES) throw IllegalArgumentException("Session is too large")
                bytes.toString(Charsets.UTF_8)
            }
            SemesterSessionLoadResult.Loaded(SemesterCollectionCodec.decode(encoded))
        } catch (exception: Exception) {
            SemesterSessionLoadResult.Invalid(exception.javaClass.simpleName.ifBlank { "InvalidSession" })
        }
    }

    suspend fun save(session: SemesterCollectionSession) = withContext(Dispatchers.IO) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create App-private Phase 6B directory")
        }
        val bytes = SemesterCollectionCodec.encode(session).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_SESSION_BYTES) { "Structured collection session exceeded its size limit" }
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(bytes)
            output.fd.sync()
            atomicFile.finishWrite(output)
            output = null
        } catch (exception: Exception) {
            output?.let(atomicFile::failWrite)
            throw exception
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        atomicFile.delete()
    }

    private companion object {
        const val SESSION_DIRECTORY = "phase6b-semester"
        const val SESSION_FILE = "collection-session.json"
        const val MAX_SESSION_BYTES = 4 * 1024 * 1024
    }
}

internal object SemesterCollectionCodec {
    private const val VERSION = 1
    private const val MAX_SNAPSHOTS = SemesterCollectionSession.MAX_SEMESTER_WEEKS
    private const val MAX_RULES_PER_SNAPSHOT = 2_000

    fun encode(session: SemesterCollectionSession): String {
        val root = JSONObject()
            .put("version", VERSION)
            .putNullable("academicYear", session.academicYear)
            .putNullable("semesterFlag", session.semesterFlag)
            .putNullable("maxWeek", session.maxWeek)
            .put("capturedWeeks", session.capturedWeeks.sorted().toIntJsonArray())
            .put("startedAtEpochMs", session.startedAtEpochMs)
            .putNullable("lastCaptureAtEpochMs", session.lastCaptureAtEpochMs)
            .putNullable("lastCapturedWeek", session.lastCapturedWeek)
            .put("backgroundCdpSurvived", session.backgroundCdpSurvived)
            .put("sessionState", session.sessionState.name)
            .put("warnings", session.warnings.map { it.name }.sorted().toStringJsonArray())

        val snapshots = JSONObject()
        session.snapshotsByWeek.toSortedMap().forEach { (week, snapshot) ->
            snapshots.put(week.toString(), snapshotToJson(snapshot.withoutNonPreviewFields()))
        }
        root.put("snapshotsByWeek", snapshots)

        val recaptures = JSONObject()
        session.recaptureCountByWeek.toSortedMap().forEach { (week, count) ->
            recaptures.put(week.toString(), count.coerceAtLeast(0))
        }
        root.put("recaptureCountByWeek", recaptures)

        val sources = JSONObject()
        session.sourceWeeksByRuleKey.toSortedMap().forEach { (ruleKey, weeks) ->
            sources.put(ruleKey, weeks.sorted().toIntJsonArray())
        }
        root.put("sourceWeeksByRuleKey", sources)
        return root.toString()
    }

    fun decode(encoded: String): SemesterCollectionSession {
        val root = JSONObject(encoded)
        require(root.optInt("version", -1) == VERSION) { "Unsupported collection session version" }
        val snapshotObject = root.optJSONObject("snapshotsByWeek") ?: JSONObject()
        require(snapshotObject.length() <= MAX_SNAPSHOTS) { "Too many stored snapshots" }
        val snapshots = linkedMapOf<Int, SchoolScheduleSnapshot>()
        val keys = snapshotObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val week = key.toIntOrNull() ?: throw JSONException("Invalid week key")
            require(week in 1..MAX_SNAPSHOTS) { "Stored week is outside bounds" }
            val snapshot = snapshotFromJson(snapshotObject.optJSONObject(key) ?: throw JSONException("Invalid snapshot"))
            require(snapshot.currentWeek?.toIntOrNull() == week) { "Snapshot week mismatch" }
            snapshots[week] = snapshot
        }

        val capturedWeeks = root.optJSONArray("capturedWeeks").intSet()
        require(capturedWeeks == snapshots.keys) { "Stored coverage does not match snapshots" }
        val maxWeek = root.optNullableInt("maxWeek")
        require(maxWeek == null || maxWeek in 1..SemesterCollectionSession.MAX_SEMESTER_WEEKS) {
            "Stored maximum week is invalid"
        }
        require(capturedWeeks.all { maxWeek != null && it <= maxWeek }) { "Stored coverage exceeds maximum week" }

        val academicYear = root.optNullableString("academicYear")
        val semesterFlag = root.optNullableString("semesterFlag")
        val rules = linkedMapOf<String, SchoolCourseRule>()
        val sourceWeeks = linkedMapOf<String, MutableSet<Int>>()
        snapshots.toSortedMap().forEach { (week, snapshot) ->
            snapshot.courseRules.forEach { rule ->
                rules.putIfAbsent(rule.ruleKey, rule)
                sourceWeeks.getOrPut(rule.ruleKey) { linkedSetOf() }.add(week)
            }
        }
        val persistedSources = root.optJSONObject("sourceWeeksByRuleKey")
        if (persistedSources != null) {
            val sourceKeys = persistedSources.keys()
            while (sourceKeys.hasNext()) {
                val key = sourceKeys.next()
                require(persistedSources.optJSONArray(key).intSet() == sourceWeeks[key].orEmpty()) {
                    "Stored rule sources do not match snapshots"
                }
            }
            require(persistedSources.length() == sourceWeeks.size) { "Stored rule sources are incomplete" }
        }

        val sessionState = runCatching {
            SemesterCollectionState.valueOf(root.optString("sessionState"))
        }.getOrElse { throw JSONException("Invalid session state") }
        val warnings = root.optJSONArray("warnings").stringSet().mapNotNull { name ->
            runCatching { SemesterCollectionWarning.valueOf(name) }.getOrNull()
        }.toSet()
        val recaptures = linkedMapOf<Int, Int>()
        root.optJSONObject("recaptureCountByWeek")?.let { values ->
            val recaptureKeys = values.keys()
            while (recaptureKeys.hasNext()) {
                val week = recaptureKeys.next().toIntOrNull() ?: continue
                if (week in 1..MAX_SNAPSHOTS) recaptures[week] = values.optInt(week.toString()).coerceAtLeast(0)
            }
        }
        val resolvedMaxWeek = maxWeek ?: snapshots.values.firstOrNull()?.maxWeek
        val ruleWarnings = if (resolvedMaxWeek != null) {
            calculateRuleCoverageWarnings(
                maxWeek = resolvedMaxWeek,
                capturedWeeks = capturedWeeks,
                snapshotsByWeek = snapshots,
                rulesByKey = rules,
                sourceWeeksByRuleKey = sourceWeeks.mapValues { it.value.toSet() }
            )
        } else {
            emptyMap()
        }
        val session = SemesterCollectionSession(
            academicYear = academicYear,
            semesterFlag = semesterFlag,
            maxWeek = maxWeek,
            capturedWeeks = capturedWeeks,
            snapshotsByWeek = snapshots,
            rulesByKey = rules,
            sourceWeeksByRuleKey = sourceWeeks.mapValues { it.value.toSet() },
            ruleWarningsByKey = ruleWarnings,
            warnings = warnings,
            recaptureCountByWeek = recaptures,
            startedAtEpochMs = root.optLong("startedAtEpochMs", 0L),
            lastCaptureAtEpochMs = root.optNullableLong("lastCaptureAtEpochMs"),
            lastCapturedWeek = root.optNullableInt("lastCapturedWeek"),
            backgroundCdpSurvived = root.optBoolean("backgroundCdpSurvived", false),
            sessionState = sessionState
        )
        return if (session.sessionState == SemesterCollectionState.SEMESTER_COLLECTION_RUNNING) {
            session.markCdpDisconnected()
        } else {
            session
        }
    }

    private fun snapshotToJson(snapshot: SchoolScheduleSnapshot): JSONObject = JSONObject()
        .putNullable("academicYear", snapshot.academicYear)
        .putNullable("semesterFlag", snapshot.semesterFlag)
        .putNullable("currentWeek", snapshot.currentWeek)
        .putNullable("maxWeek", snapshot.maxWeek)
        .putNullable("rawMaxWeek", snapshot.rawMaxWeek)
        .putNullable("weekStartDateText", snapshot.weekStartDateText)
        .putNullable("weekEndDateText", snapshot.weekEndDateText)
        .putNullable("maxSection", snapshot.maxSection)
        .putNullable("rawMaxSection", snapshot.rawMaxSection)
        .put("weekItemCounts", JSONObject().also { counts ->
            snapshot.weekItemCounts.forEach { (day, count) -> counts.put(day.toString(), count.coerceAtLeast(0)) }
        })
        .put("courseRules", JSONArray().also { rules -> snapshot.courseRules.forEach { rules.put(ruleToJson(it)) } })
        .put("rawRuleCount", snapshot.rawRuleCount.coerceAtLeast(0))
        .put("malformedRuleCount", snapshot.malformedRuleCount.coerceAtLeast(0))
        .put("exactDuplicateCount", snapshot.exactDuplicateCount.coerceAtLeast(0))
        .put("uniqueRuleCount", snapshot.uniqueRuleCount.coerceAtLeast(0))
        .put("warnings", snapshot.warnings.map { it.name }.sorted().toStringJsonArray())

    private fun snapshotFromJson(json: JSONObject): SchoolScheduleSnapshot {
        val rulesJson = json.optJSONArray("courseRules") ?: JSONArray()
        require(rulesJson.length() <= MAX_RULES_PER_SNAPSHOT) { "Too many stored rules" }
        val rules = (0 until rulesJson.length()).map { index ->
            ruleFromJson(rulesJson.optJSONObject(index) ?: throw JSONException("Invalid stored rule"))
        }
        val countObject = json.optJSONObject("weekItemCounts") ?: JSONObject()
        val counts = linkedMapOf<Int, Int>()
        val countKeys = countObject.keys()
        while (countKeys.hasNext()) {
            val day = countKeys.next().toIntOrNull() ?: continue
            if (day in 1..7) counts[day] = countObject.optInt(day.toString()).coerceAtLeast(0)
        }
        val warnings = json.optJSONArray("warnings").stringSet().mapNotNull { name ->
            runCatching { SchoolParseWarning.valueOf(name) }.getOrNull()
        }.toSet()
        return SchoolScheduleSnapshot(
            academicYear = json.optNullableString("academicYear"),
            semesterFlag = json.optNullableString("semesterFlag"),
            currentWeek = json.optNullableString("currentWeek"),
            maxWeek = json.optNullableInt("maxWeek"),
            rawMaxWeek = json.optNullableString("rawMaxWeek"),
            weekStartDateText = json.optNullableString("weekStartDateText"),
            weekEndDateText = json.optNullableString("weekEndDateText"),
            weekStartDate = null,
            weekEndDate = null,
            maxSection = json.optNullableInt("maxSection"),
            rawMaxSection = json.optNullableString("rawMaxSection"),
            weekItemCounts = counts,
            courseRules = rules,
            rawRuleCount = json.optInt("rawRuleCount").coerceAtLeast(0),
            malformedRuleCount = json.optInt("malformedRuleCount").coerceAtLeast(0),
            exactDuplicateCount = json.optInt("exactDuplicateCount").coerceAtLeast(0),
            uniqueRuleCount = json.optInt("uniqueRuleCount", rules.size).coerceAtLeast(0),
            warnings = warnings
        )
    }

    private fun ruleToJson(rule: SchoolCourseRule): JSONObject = JSONObject()
        .put("ruleKey", rule.ruleKey)
        .putNullable("courseCode", rule.courseCode)
        .putNullable("courseName", rule.courseName)
        .putNullable("credits", rule.credits)
        .putNullable("teacher", rule.teacher)
        .putNullable("teachingClassCode", rule.teachingClassCode)
        .putNullable("sectionText", rule.sectionText)
        .putNullable("sectionCodeText", rule.sectionCodeText)
        .putNullable("startSection", rule.startSection)
        .putNullable("endSection", rule.endSection)
        .put("dayOfWeek", rule.dayOfWeek)
        .putNullable("campus", rule.campus)
        .putNullable("displayLocation", rule.displayLocation)
        .putNullable("rawWeekText", rule.rawWeekText)
        .put("weeks", rule.weeks.distinct().sorted().toIntJsonArray())
        .put("weekParseStatus", rule.weekParseStatus.name)
        .putNullable("oddEvenText", rule.oddEvenText)
        .put("warnings", rule.warnings.map { it.name }.sorted().toStringJsonArray())

    private fun ruleFromJson(json: JSONObject): SchoolCourseRule {
        val status = runCatching { RuleParseStatus.valueOf(json.optString("weekParseStatus")) }
            .getOrElse { throw JSONException("Invalid rule status") }
        val warnings = json.optJSONArray("warnings").stringSet().mapNotNull { name ->
            runCatching { SchoolParseWarning.valueOf(name) }.getOrNull()
        }.toSet()
        val weeks = json.optJSONArray("weeks").intSet().filter { it in 1..SemesterCollectionSession.MAX_SEMESTER_WEEKS }
        val location = json.optNullableString("displayLocation")
        return SchoolCourseRule(
            ruleKey = json.optString("ruleKey").takeIf(String::isNotBlank)
                ?: throw JSONException("Missing rule identity"),
            courseCode = json.optNullableString("courseCode"),
            courseName = json.optNullableString("courseName"),
            credits = json.optNullableString("credits"),
            teacher = json.optNullableString("teacher"),
            studentCount = null,
            teachingClassCode = json.optNullableString("teachingClassCode"),
            sectionText = json.optNullableString("sectionText"),
            sectionCodeText = json.optNullableString("sectionCodeText"),
            startSection = json.optNullableInt("startSection"),
            endSection = json.optNullableInt("endSection"),
            dayOfWeek = json.optInt("dayOfWeek").coerceIn(1, 7),
            campus = json.optNullableString("campus"),
            rawLocation = location,
            displayLocation = location,
            rawWeekText = json.optNullableString("rawWeekText"),
            weeks = weeks,
            weekParseStatus = status,
            oddEvenText = json.optNullableString("oddEvenText"),
            teacherCode = null,
            teachingClassName = null,
            courseOfferingCode = null,
            dateText = null,
            beginTimeText = null,
            endTimeText = null,
            liveUrl = null,
            note = null,
            sourceFieldNames = emptySet(),
            warnings = warnings
        )
    }

    private fun List<Int>.toIntJsonArray() = JSONArray().also { array -> forEach(array::put) }
    private fun List<String>.toStringJsonArray() = JSONArray().also { array -> forEach(array::put) }

    private fun JSONArray?.intSet(): Set<Int> {
        if (this == null) return emptySet()
        return buildSet {
            for (index in 0 until length()) optInt(index, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.let(::add)
        }
    }

    private fun JSONArray?.stringSet(): Set<String> {
        if (this == null) return emptySet()
        return buildSet {
            for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    private fun JSONObject.optNullableString(key: String): String? =
        opt(key).takeUnless { it == null || it === JSONObject.NULL }?.toString()

    private fun JSONObject.optNullableInt(key: String): Int? =
        opt(key).takeUnless { it == null || it === JSONObject.NULL }
            ?.let { (it as? Number)?.toInt() ?: it.toString().toIntOrNull() }

    private fun JSONObject.optNullableLong(key: String): Long? =
        opt(key).takeUnless { it == null || it === JSONObject.NULL }
            ?.let { (it as? Number)?.toLong() ?: it.toString().toLongOrNull() }
}
