package com.burgess.schedule.school.sync.importpreview

import com.burgess.schedule.school.sync.localadb.CoursePayloadSummary
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

internal object SdbiScheduleParser {
    fun parse(json: JSONObject, summary: CoursePayloadSummary): SchoolScheduleSnapshot {
        val parsedRules = mutableListOf<SchoolCourseRule>()
        val warnings = linkedSetOf<SchoolParseWarning>()
        var rawRuleCount = 0
        var malformedRuleCount = 0

        (1..7).forEach { weekday ->
            val array = json.opt("week$weekday") as? JSONArray ?: return@forEach
            rawRuleCount += array.length()
            for (index in 0 until array.length()) {
                val item = array.opt(index) as? JSONObject
                if (item == null) {
                    malformedRuleCount++
                    warnings += SchoolParseWarning.NON_OBJECT_RULE
                    continue
                }
                parsedRules += parseRule(item, weekday, summary.maximumWeek?.toIntOrNull())
            }
        }

        val uniqueRules = LinkedHashMap<String, SchoolCourseRule>()
        parsedRules.forEach { rule -> uniqueRules.putIfAbsent(rule.ruleKey, rule) }
        val unique = uniqueRules.values.toList()
        val startDate = parseIsoDate(summary.startDate)
        val endDate = parseIsoDate(summary.endDate)
        if ((!summary.startDate.isNullOrBlank() && startDate == null) ||
            (!summary.endDate.isNullOrBlank() && endDate == null)
        ) {
            warnings += SchoolParseWarning.DATE_PARSE_WARNING
        }
        val maxSection = summary.maximumPeriod?.toIntOrNull()

        return SchoolScheduleSnapshot(
            academicYear = summary.academicYear,
            semesterFlag = summary.term,
            currentWeek = summary.currentWeek,
            maxWeek = summary.maximumWeek?.toIntOrNull(),
            rawMaxWeek = summary.maximumWeek,
            weekStartDateText = summary.startDate,
            weekEndDateText = summary.endDate,
            weekStartDate = startDate,
            weekEndDate = endDate,
            maxSection = maxSection,
            rawMaxSection = summary.maximumPeriod,
            weekItemCounts = summary.weekItemCounts,
            courseRules = unique,
            rawRuleCount = rawRuleCount,
            malformedRuleCount = malformedRuleCount,
            exactDuplicateCount = parsedRules.size - unique.size,
            uniqueRuleCount = unique.size,
            warnings = warnings
        )
    }

    private fun parseRule(item: JSONObject, weekday: Int, maxWeek: Int?): SchoolCourseRule {
        val courseName = item.scalarString("kcmc")?.trim()?.takeIf(String::isNotEmpty)
        val teacher = item.scalarString("rkjs")?.trim()?.takeIf(String::isNotEmpty)
        val rawLocation = item.scalarString("skdd")
        val displayLocation = rawLocation?.trim()?.takeIf(String::isNotEmpty)
        val sectionText = item.scalarString("jcxx")
        val sectionCodeText = item.scalarString("jcdm")
        val section = SdbiSectionParser.parse(sectionText, sectionCodeText)
        val rawWeekText = item.scalarString("skzs")
        val week = SdbiWeekParser.parse(rawWeekText, maxWeek)
        val oddEvenText = item.scalarString("dsz")?.trim()?.takeIf(String::isNotEmpty)
        val warnings = linkedSetOf<SchoolParseWarning>()
        if (courseName == null) warnings += SchoolParseWarning.MISSING_COURSE_NAME
        if (teacher == null) warnings += SchoolParseWarning.MISSING_TEACHER
        if (displayLocation == null) warnings += SchoolParseWarning.MISSING_LOCATION
        warnings += section.warnings
        warnings += week.warnings
        if (hasExplicitOddEvenConflict(oddEvenText, week.weeks)) {
            warnings += SchoolParseWarning.ODD_EVEN_CONFLICT
        }

        val ruleKey = stableRuleKey(
            listOf(
                item.scalarString("kcdm").orEmpty(),
                item.scalarString("skbj").orEmpty(),
                courseName.orEmpty(),
                teacher.orEmpty(),
                weekday.toString(),
                sectionText.orEmpty(),
                sectionCodeText.orEmpty(),
                section.startSection?.toString().orEmpty(),
                section.endSection?.toString().orEmpty(),
                rawWeekText.orEmpty(),
                rawLocation.orEmpty(),
                item.scalarString("xq").orEmpty()
            )
        )
        return SchoolCourseRule(
            ruleKey = ruleKey,
            courseCode = item.scalarString("kcdm"),
            courseName = courseName,
            credits = item.scalarString("xf"),
            teacher = teacher,
            studentCount = item.scalarString("rs"),
            teachingClassCode = item.scalarString("skbj"),
            sectionText = sectionText,
            sectionCodeText = sectionCodeText,
            startSection = section.startSection,
            endSection = section.endSection,
            dayOfWeek = weekday,
            campus = item.scalarString("xq")?.trim()?.takeIf(String::isNotEmpty),
            rawLocation = rawLocation,
            displayLocation = displayLocation,
            rawWeekText = rawWeekText,
            weeks = week.weeks,
            weekParseStatus = week.status,
            oddEvenText = oddEvenText,
            teacherCode = item.scalarString("jsdm"),
            teachingClassName = item.scalarString("skbjmc"),
            courseOfferingCode = item.scalarString("kcyhdm"),
            dateText = item.scalarString("rq"),
            beginTimeText = item.scalarString("beginTime"),
            endTimeText = item.scalarString("endTime"),
            liveUrl = item.scalarString("liveUrl"),
            note = item.scalarString("bz"),
            sourceFieldNames = item.keys().asSequence().toSet(),
            warnings = warnings
        )
    }

    private fun hasExplicitOddEvenConflict(marker: String?, weeks: List<Int>): Boolean {
        val value = marker.orEmpty()
        val saysOdd = (value.contains("单") || value.contains("奇")) &&
            !value.contains("双") && !value.contains("偶")
        val saysEven = (value.contains("双") || value.contains("偶")) &&
            !value.contains("单") && !value.contains("奇")
        return (saysOdd && weeks.any { it % 2 == 0 }) ||
            (saysEven && weeks.any { it % 2 == 1 })
    }

    private fun stableRuleKey(fields: List<String>): String {
        val canonical = fields.joinToString(separator = "") { "${it.length}:$it;" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun parseIsoDate(value: String?): Date? {
        val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (!ISO_DATE.matches(raw)) return null
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val position = ParsePosition(0)
        val parsed = formatter.parse(raw, position) ?: return null
        return parsed.takeIf { position.index == raw.length && formatter.format(it) == raw }
    }

    private val ISO_DATE = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
}

private fun JSONObject.scalarString(name: String): String? = when (val value = opt(name)) {
    is String -> value
    is Number -> value.toString()
    else -> null
}
