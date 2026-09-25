package com.burgess.schedule.school.sync.importpreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdbiSectionParserTest {
    @Test
    fun parsesSupportedRangesAndSingleSection() {
        listOf("1-2", "3-4", "5-6", "7-8", "9-10").forEachIndexed { index, text ->
            val parsed = SdbiSectionParser.parse(text, null)
            assertEquals(index * 2 + 1, parsed.startSection)
            assertEquals(index * 2 + 2, parsed.endSection)
            assertEquals(RuleParseStatus.SUCCESS, parsed.status)
        }
        val single = SdbiSectionParser.parse("1", null)
        assertEquals(1, single.startSection)
        assertEquals(1, single.endSection)
    }

    @Test
    fun checksJcdmAgainstSectionText() {
        assertTrue(SdbiSectionParser.parse("1-2", "01,02").warnings.isEmpty())
        assertTrue(SdbiSectionParser.parse("3-4", "03，04").warnings.isEmpty())

        val mismatch = SdbiSectionParser.parse("1-2", "03,04")
        assertEquals(1, mismatch.startSection)
        assertTrue(SchoolParseWarning.SECTION_MISMATCH in mismatch.warnings)
    }

    @Test
    fun emptyInvalidAndCommaSeparatedTextFailWithoutRepair() {
        listOf("", "abc", "1,2", "2-1").forEach { text ->
            val parsed = SdbiSectionParser.parse(text, null)
            assertEquals(RuleParseStatus.FAILURE, parsed.status)
            assertTrue(SchoolParseWarning.SECTION_PARSE_FAILED in parsed.warnings)
        }
    }
}
