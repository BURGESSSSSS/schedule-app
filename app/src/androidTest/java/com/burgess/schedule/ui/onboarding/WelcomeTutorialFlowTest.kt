package com.burgess.schedule.ui.onboarding

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.burgess.schedule.MainActivity
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WelcomeTutorialFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    @Before
    fun prepareFirstLaunch() {
        assertTrue(preferences.edit().remove("welcome_seen").commit())
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun closeActivity() { scenario?.close() }

    @Test
    fun welcomeRemainsUntilDismissedAndDoesNotReturnAfterRestart() {
        compose.onNodeWithText("欢迎使用课程表").assertIsDisplayed()
        capture("welcome")
        scenario!!.recreate()
        compose.onNodeWithText("欢迎使用课程表").assertIsDisplayed()
        compose.onNodeWithText(DEVELOPER_EMAIL).assertIsDisplayed().performClick()
        compose.onNodeWithText("邮箱已复制").assertIsDisplayed()
        assertFalse(preferences.getBoolean("welcome_seen", false))
        compose.onNodeWithText("先逛一逛").assertIsDisplayed().performClick()
        compose.onNodeWithText("欢迎使用课程表").assertDoesNotExist()
        assertTrue(preferences.getBoolean("welcome_seen", false))
        scenario!!.recreate()
        compose.onNodeWithText("欢迎使用课程表").assertDoesNotExist()
        scenario!!.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithText("欢迎使用课程表").assertDoesNotExist()
        compose.onNodeWithText("更多").performClick()
        compose.onNodeWithText("课表导入教程").performScrollTo().performClick()
        compose.onNodeWithText("01 · 开启无线调试").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun welcomeTutorialShortcutLeadsThroughGuideToSchoolImport() {
        compose.onNodeWithText("查看导入教程").assertIsDisplayed().performClick()
        capture("tutorial")
        compose.onNodeWithText("01 · 开启无线调试").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("04 · 获取整学期课程").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("开始导入").performScrollTo().performClick()
        compose.onNodeWithText("无线调试：未连接").assertIsDisplayed()
        assertTrue(preferences.getBoolean("welcome_seen", false))
    }

    @Test
    fun welcomeDirectImportShortcutAndBackKeepTutorialAvailableInMore() {
        compose.onNodeWithText("直接导入").assertIsDisplayed().performClick()
        compose.onNodeWithText("无线调试：未连接").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        }
        compose.onNodeWithText("课表导入教程").performScrollTo().assertIsDisplayed()
        capture("more")
        compose.onNodeWithText("课表导入教程").performClick()
        compose.onNodeWithText("01 · 开启无线调试").performScrollTo().assertIsDisplayed()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("Screenshot unavailable")
        val folder = File(context.getExternalFilesDir(null), "welcome-tutorial").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot.recycle()
    }
}
