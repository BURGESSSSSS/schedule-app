package com.burgess.schedule.school.sync.localadb

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.burgess.schedule.ui.components.SubPageScaffold
import com.burgess.schedule.ui.theme.AppType
import com.burgess.schedule.ui.theme.LocalAppColors

@Composable
internal fun LocalAdbSmokeScreen(onBack: () -> Unit) {
    SubPageScaffold("Local ADB 最小验证", onBack) {
        Text("此实验页面只包含在 Debug 版本中。", style = AppType.body, color = LocalAppColors.current.mut)
    }
}
