package com.burgess.schedule.school.sync.importpreview

internal object SdbiSectionParser {
    fun parse(sectionText: String?, sectionCodeText: String?): SectionParseResult {
        val raw = sectionText.orEmpty()
        val compact = raw.trim()
        val match = SECTION_RANGE.matchEntire(compact)
        if (match == null) {
            return SectionParseResult(
                rawText = raw,
                startSection = null,
                endSection = null,
                status = RuleParseStatus.FAILURE,
                warnings = setOf(SchoolParseWarning.SECTION_PARSE_FAILED)
            )
        }
        val start = match.groupValues[1].toIntOrNull()
        val end = match.groupValues[2].takeIf(String::isNotEmpty)?.toIntOrNull() ?: start
        if (start == null || end == null || start < 1 || end < start) {
            return SectionParseResult(
                rawText = raw,
                startSection = null,
                endSection = null,
                status = RuleParseStatus.FAILURE,
                warnings = setOf(SchoolParseWarning.SECTION_PARSE_FAILED)
            )
        }

        val warnings = linkedSetOf<SchoolParseWarning>()
        val codes = sectionCodeText.orEmpty().trim()
        if (codes.isNotEmpty()) {
            val pieces = codes.split(',', '，', '、').map(String::trim)
            val parsedCodes = pieces.mapNotNull { it.toIntOrNull() }
            val expected = (start..end).toList()
            if (parsedCodes.size != pieces.size || parsedCodes != expected) {
                warnings += SchoolParseWarning.SECTION_MISMATCH
            }
        }
        return SectionParseResult(
            rawText = raw,
            startSection = start,
            endSection = end,
            status = if (warnings.isEmpty()) RuleParseStatus.SUCCESS else RuleParseStatus.WARNING,
            warnings = warnings
        )
    }

    private val SECTION_RANGE = Regex("^\\s*([0-9]+)(?:\\s*[-－–—]\\s*([0-9]+))?\\s*$")
}

internal data class SectionParseResult(
    val rawText: String,
    val startSection: Int?,
    val endSection: Int?,
    val status: RuleParseStatus,
    val warnings: Set<SchoolParseWarning>
)
