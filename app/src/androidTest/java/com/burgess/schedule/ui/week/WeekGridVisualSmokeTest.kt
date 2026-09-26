package com.burgess.schedule.ui.week

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.Intent
import android.view.ViewTreeObserver
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.burgess.schedule.MainActivity
import com.burgess.schedule.ui.theme.课程表Theme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WeekGridVisualSmokeTest {
    @Test
    fun weekGridSandboxRendersAndCapturesScreenshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val launchIntent = instrumentation.targetContext.packageManager
            .getLaunchIntentForPackage(instrumentation.targetContext.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: error("Main activity intent unavailable")
        val activity = instrumentation.startActivitySync(launchIntent) as MainActivity
        val composed = CountDownLatch(1)
        val preDraw = CountDownLatch(1)
        lateinit var rootView: android.view.View
        instrumentation.runOnMainSync {
            activity.setContent {
                课程表Theme {
                    SideEffect { composed.countDown() }
                    WeekGridSandbox()
                }
            }
            rootView = activity.window.decorView
            rootView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (composed.count == 0L) {
                        rootView.viewTreeObserver.removeOnPreDrawListener(this)
                        preDraw.countDown()
                    }
                    return true
                }
            })
        }
        assertTrue("Week grid composition did not complete", composed.await(10, TimeUnit.SECONDS))
        assertTrue("Week grid did not reach its first pre-draw", preDraw.await(10, TimeUnit.SECONDS))

        lateinit var bitmap: Bitmap
        instrumentation.runOnMainSync {
            bitmap = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
            rootView.draw(Canvas(bitmap))
        }
        assertTrue("Expected a rendered week grid screenshot", bitmap.width > 0 && bitmap.height > 0)

        val output = File(
            instrumentation.targetContext.getExternalFilesDir(null),
            "week-view-preview.png"
        )
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
