package com.burgess.schedule.school.sync.importpreview

internal object SdbiWeekParser {
    fun parse(rawText: String?, maxWeek: Int?): WeekParseResult {
        val raw = rawText.orEmpty()
        val compact = raw.replace(Regex("\\s+"), "").trim()
        if (compact.isBlank()) {
            return WeekParseResult(raw, emptyList(), RuleParseStatus.FAILURE, setOf(SchoolParseWarning.WEEK_PARSE_FAILED))
        }

        val normalized = compact
            .replace('，', ',')
            .replace('、', ',')
            .replace('－', '-')
            .replace('–', '-')
            .replace('—', '-')
        val parsedWeeks = sortedSetOf<Int>()
        var invalidPart = false
        normalized.split(',').forEach { part ->
            val token = part.replace("周", "")
            val match = RANGE_OR_SINGLE.matchEntire(token)
            if (match == null) {
                invalidPart = true
            } else {
                val start = match.groupValues[1].toIntOrNull()
                val end = match.groupValues[2].takeIf(String::isNotEmpty)?.toIntOrNull() ?: start
                if (start == null || end == null || end < start || end - start > MAX_RANGE_SPAN) {
                    invalidPart = true
                } else {
                    (start..end).forEach(parsedWeeks::add)
                }
            }
        }

        if (parsedWeeks.isEmpty()) {
            return WeekParseResult(raw, emptyList(), RuleParseStatus.FAILURE, setOf(SchoolParseWarning.WEEK_PARSE_FAILED))
        }

        val warnings = linkedSetOf<SchoolParseWarning>()
        if (invalidPart) warnings += SchoolParseWarning.WEEK_PARSE_FAILED
        if (parsedWeeks.any { it < 1 || (maxWeek != null && it > maxWeek) }) {
            warnings += SchoolParseWarning.WEEK_OUT_OF_RANGE
        }
        val status = if (warnings.isEmpty()) RuleParseStatus.SUCCESS else RuleParseStatus.WARNING
        return WeekParseResult(raw, parsedWeeks.toList(), status, warnings)
    }

    private val RANGE_OR_SINGLE = Regex("^([0-9]+)(?:-([0-9]+))?$")
    private const val MAX_RANGE_SPAN = 500
}

internal data class WeekParseResult(
    val rawText: String,
    val weeks: List<Int>,
    val status: RuleParseStatus,
    val warnings: Set<SchoolParseWarning>
)
