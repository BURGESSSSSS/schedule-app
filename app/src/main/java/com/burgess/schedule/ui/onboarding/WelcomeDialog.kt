package com.burgess.schedule.ui.onboarding

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.burgess.schedule.ui.components.PillButton
import com.burgess.schedule.ui.components.PillStyle
import com.burgess.schedule.ui.components.pressAction
import com.burgess.schedule.ui.theme.AppShape
import com.burgess.schedule.ui.theme.AppSpace
import com.burgess.schedule.ui.theme.AppType
import com.burgess.schedule.ui.theme.LocalAppColors
import com.burgess.schedule.ui.theme.课程表Theme

internal const val DEVELOPER_EMAIL = "suyu20651@gmail.com"

@Composable
internal fun WelcomeDialog(
    onDismiss: () -> Unit,
    onViewTutorial: () -> Unit,
    onCopyEmail: () -> Unit
) {
    val colors = LocalAppColors.current
    val introductionScroll = rememberScrollState()
    var emailCopied by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.apply {
                setDimAmount(colors.scrim.alpha)
                decorView.elevation = 0f
            }
            (view.parent as? View)?.elevation = 0f
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(AppSpace.page), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.widthIn(max = AppSpace.dialogMaxWidth).fillMaxWidth()
                    .heightIn(max = maxHeight),
                shape = AppShape.sheet,
                color = colors.card
            ) {
                Column(
                    Modifier.padding(AppSpace.hero),
                    verticalArrangement = Arrangement.spacedBy(AppSpace.hero)
                ) {
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(introductionScroll),
                        verticalArrangement = Arrangement.spacedBy(AppSpace.gap)
                    ) {
                        Text("欢迎使用", style = AppType.sheetTitle, color = colors.ink)
                        Text(
                            "本项目基于 Kotlin 原生 Android 技术架构开发，为山东商务职业学院学子深度定制。",
                            style = AppType.body, color = colors.ink
                        )
                        Text(
                            "针对学校教务系统，打造了基于无线调试的课表导入功能，可从企业微信中的教务课表读取并导入整学期课程，减少手动录入的麻烦。",
                            style = AppType.body, color = colors.mut
                        )
                        Text("首次导入需要完成一些准备步骤，建议先阅读导入教程。", style = AppType.body, color = colors.mut)
                    }
                    if (introductionScroll.maxValue > 0) {
                        Text("上滑查看完整介绍", style = AppType.caption, color = colors.mut)
                    }
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = AppSpace.touch)
                                .pressAction(onViewTutorial, button = true)
                                .clip(AppShape.pill).background(colors.ink)
                                .semantics { role = Role.Button }
                                .padding(horizontal = AppSpace.card),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("查看导入教程", style = AppType.caption, color = colors.onInk)
                        }
                        PillButton("先逛一逛", onDismiss, PillStyle.Ghost)
                    }
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "使用过程中遇到问题，或有任何改进建议，欢迎通过邮箱联系开发者：",
                            style = AppType.caption, color = colors.mut
                        )
                        Text(
                            DEVELOPER_EMAIL,
                            modifier = Modifier.fillMaxWidth().heightIn(min = AppSpace.touch)
                                .clickable(role = Role.Button) { onCopyEmail(); emailCopied = true }
                                .padding(vertical = AppSpace.gap),
                            style = AppType.caption, color = colors.mut
                        )
                        Text(
                            if (emailCopied) "邮箱已复制" else "点击邮箱即可复制",
                            style = AppType.caption, color = colors.mut
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun WelcomePreview() {
    课程表Theme { WelcomeDialog({}, {}, {}) }
}
