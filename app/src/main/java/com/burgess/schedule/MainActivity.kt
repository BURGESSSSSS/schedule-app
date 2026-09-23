package com.burgess.schedule

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.burgess.schedule.ui.theme.*

class MainActivity : ComponentActivity() {
    private var sharedImageUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedImageUri = intent.sharedImageUri()
        // 界面固定浅色，系统栏图标始终用深色，避免深色系统的白图标落在白底上。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        setContent {
            课程表Theme {
                ScheduleApp(
                    sharedImageUri = sharedImageUri,
                    onSharedImageHandled = { sharedImageUri = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedImageUri = intent.sharedImageUri()
    }
}


private fun Intent.sharedImageUri(): Uri? {
    if (action != Intent.ACTION_SEND || type?.startsWith("image/") != true) {
        return null
    }
    return getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        ?: clipData?.getItemAt(0)?.uri
}
