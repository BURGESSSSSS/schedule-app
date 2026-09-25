package com.burgess.schedule.school.sync.importpreview

import com.burgess.schedule.data.SchoolSchedule

internal object SchoolImportMapper {
    fun map(snapshot: SchoolScheduleSnapshot): List<SchoolImportCandidate> =
        snapshot.courseRules.flatMap { rule -> mapRule(rule, snapshot.maxWeek) }

    fun mapRule(rule: SchoolCourseRule, maxWeek: Int?): List<SchoolImportCandidate> {
        val weeks: List<Int?> = rule.weeks.map { it }.ifEmpty { listOf(null) }
        return weeks.map { weekNumber ->
            val warnings = rule.warnings.toMutableSet()
            val sectionIsValid = rule.startSection != null &&
                rule.endSection != null &&
                rule.startSection >= 1 &&
                rule.endSection >= rule.startSection &&
                rule.endSection <= SchoolSchedule.PERIOD_COUNT &&
                SchoolParseWarning.SECTION_MISMATCH !in rule.warnings
            if (rule.startSection != null && rule.endSection != null &&
                rule.endSection > SchoolSchedule.PERIOD_COUNT
            ) {
                warnings += SchoolParseWarning.SECTION_OUT_OF_APP_RANGE
            }
            if (weekNumber != null && (weekNumber < 1 || (maxWeek != null && weekNumber > maxWeek))) {
                warnings += SchoolParseWarning.WEEK_OUT_OF_RANGE
            }
            val weekIsValid = weekNumber != null &&
                weekNumber >= 1 &&
                (maxWeek == null || weekNumber <= maxWeek) &&
                rule.weekParseStatus == RuleParseStatus.SUCCESS
            val importable = !rule.courseName.isNullOrBlank() && sectionIsValid && weekIsValid
            if (importable) warnings += SchoolParseWarning.IMPORT_DEFAULT
            SchoolImportCandidate(
                id = null,
                name = rule.courseName.orEmpty(),
                teacher = rule.teacher.orEmpty(),
                room = rule.displayLocation.orEmpty(),
                dayIndex = (rule.dayOfWeek - 1).coerceIn(0, 6),
                period = rule.startSection?.minus(1),
                endPeriod = rule.endSection?.minus(1),
                weekNumber = weekNumber,
                colorValue = null,
                shortName = null,
                sourceRuleKey = rule.ruleKey,
                warnings = warnings,
                defaults = setOf(FieldImportDefault.DATABASE_ID, FieldImportDefault.COLOR, FieldImportDefault.SHORT_NAME),
                importable = importable
            )
        }
    }

    fun canMapRule(rule: SchoolCourseRule, maxWeek: Int?): Boolean {
        if (rule.courseName.isNullOrBlank()) return false
        if (rule.startSection == null || rule.endSection == null ||
            rule.startSection < 1 || rule.endSection < rule.startSection ||
            rule.endSection > SchoolSchedule.PERIOD_COUNT ||
            SchoolParseWarning.SECTION_MISMATCH in rule.warnings
        ) return false
        if (rule.weeks.isEmpty() || rule.weekParseStatus != RuleParseStatus.SUCCESS) return false
        return rule.weeks.all { it >= 1 && (maxWeek == null || it <= maxWeek) }
    }
}
