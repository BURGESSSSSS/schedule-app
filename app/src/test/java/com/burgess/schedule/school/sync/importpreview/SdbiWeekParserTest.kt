package com.burgess.schedule.school.sync.importpreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdbiWeekParserTest {
    @Test
    fun parsesRangesAndDiscreteWeeks() {
        assertEquals((1..5).toList() + (7..19).toList(), SdbiWeekParser.parse("1-5,7-19周", 19).weeks)
        assertEquals(listOf(3, 7, 11, 15, 19), SdbiWeekParser.parse("3,7,11,15,19周", 19).weeks)
        assertEquals(listOf(8), SdbiWeekParser.parse("8周", 19).weeks)
        assertEquals((1..19).toList(), SdbiWeekParser.parse("1-19周", 19).weeks)
    }

    @Test
    fun acceptsWhitespaceChinesePunctuationAndDeduplicatesInOrder() {
        val result = SdbiWeekParser.parse("1, 3，5、3 周", 19)

        assertEquals(listOf(1, 3, 5), result.weeks)
        assertEquals(RuleParseStatus.SUCCESS, result.status)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun emptyAndIllegalTextAreFailures() {
        listOf("", "   ", "abc").forEach { value ->
            val result = SdbiWeekParser.parse(value, 19)
            assertEquals(RuleParseStatus.FAILURE, result.status)
            assertTrue(SchoolParseWarning.WEEK_PARSE_FAILED in result.warnings)
        }
        val partial = SdbiWeekParser.parse("1,,x", 19)
        assertEquals(listOf(1), partial.weeks)
        assertEquals(RuleParseStatus.WARNING, partial.status)
    }

    @Test
    fun preservesOutOfRangeWeeksAndReportsWarning() {
        val result = SdbiWeekParser.parse("0,18-20周", 19)

        assertEquals(listOf(0, 18, 19, 20), result.weeks)
        assertEquals(RuleParseStatus.WARNING, result.status)
        assertTrue(SchoolParseWarning.WEEK_OUT_OF_RANGE in result.warnings)
    }

    @Test
    fun malformedPartDoesNotDiscardOtherParsedWeeks() {
        val result = SdbiWeekParser.parse("2，bad，4周", 19)

        assertEquals(listOf(2, 4), result.weeks)
        assertEquals(RuleParseStatus.WARNING, result.status)
        assertTrue(SchoolParseWarning.WEEK_PARSE_FAILED in result.warnings)
    }
}
