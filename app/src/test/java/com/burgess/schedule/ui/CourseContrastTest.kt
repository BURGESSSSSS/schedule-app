package com.burgess.schedule.ui
import com.burgess.schedule.ui.theme.*
import org.junit.Assert.assertTrue
import org.junit.Test
class CourseContrastTest {
    @Test fun everyHueIsReadableInFixedLightTheme() {
        CoursePalette.forEach { hue ->
            val (bg, fg) = courseTint(hue, LightAppColors)
            assertTrue("课程色对比度 ${contrast(bg, fg)}", contrast(bg, fg) >= 4.5f)
        }
        assertTrue("界面次要文字对比度 ${contrast(LightAppColors.card, LightAppColors.mut)}", contrast(LightAppColors.card, LightAppColors.mut) >= 4.5f)
    }
}
