package com.burgess.schedule.ui
import com.burgess.schedule.ui.theme.*
import org.junit.Assert.assertTrue
import org.junit.Test
class CourseContrastTest {
    @Test fun everyHueIsReadableInBothThemes() {
        listOf(LightAppColors, DarkAppColors).forEach { theme ->
            CoursePalette.forEach { hue ->
                val (bg, fg) = courseTint(hue, theme)
                assertTrue("课程色对比度 ${contrast(bg, fg)}", contrast(bg, fg) >= 4.5f)
            }
            assertTrue(contrast(theme.card, theme.mut) >= 4.5f)
        }
    }
}
