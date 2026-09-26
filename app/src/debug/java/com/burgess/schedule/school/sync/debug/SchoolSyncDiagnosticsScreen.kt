package com.burgess.schedule.school.sync.debug

import androidx.compose.runtime.Composable
import com.burgess.schedule.school.sync.localadb.LocalAdbSmokeScreen

@Composable
internal fun SchoolSyncDiagnosticsScreen(onBack: () -> Unit) {
    LocalAdbSmokeScreen(onBack)
}
