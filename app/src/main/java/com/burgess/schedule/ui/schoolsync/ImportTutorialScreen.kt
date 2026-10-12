package com.burgess.schedule.ui.schoolsync

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.burgess.schedule.ui.components.AppCard
import com.burgess.schedule.ui.components.PillButton
import com.burgess.schedule.ui.components.SectionLabel
import com.burgess.schedule.ui.components.SubPageScaffold
import com.burgess.schedule.ui.theme.AppSpace
import com.burgess.schedule.ui.theme.AppType
import com.burgess.schedule.ui.theme.LocalAppColors
import com.burgess.schedule.ui.theme.课程表Theme

@Composable
internal fun ImportTutorialScreen(onBack: () -> Unit, onStartImport: () -> Unit) {
    BackHandler(onBack = onBack)
    val colors = LocalAppColors.current
    SubPageScaffold("课表导入教程", onBack) {
        Text("把企业微信里的学校课表，带进「课程表」。", style = AppType.body, color = colors.ink)
        Text(
            "首次使用需要连接无线调试、打开学校课表，并逐周获取课程。跟着下面的步骤操作，最后确认导入即可。",
            style = AppType.body, color = colors.mut
        )
        Spacer(Modifier.height(AppSpace.gap))
        SectionLabel("开始之前")
        Text(
            "请连接 Wi-Fi，并确认你能在企业微信中正常打开「我的课表」。此导入方式需要 Android 11 或以上，且手机提供「无线调试」功能。不同品牌手机的设置名称和位置可能略有不同。",
            style = AppType.body, color = colors.ink
        )
        Text("操作入口：课程表 → 更多 → 学校课表同步。", style = AppType.body, color = colors.ink)
        Spacer(Modifier.height(AppSpace.gap))

        TutorialStep(
            "01 · 开启无线调试",
            listOf(
                "打开手机「设置 → 关于手机」，找到「版本号」，连续点击并按系统提示开启开发者选项。",
                "返回设置，找到「开发者选项 → 无线调试」，打开开关。如果系统询问是否允许在当前网络上进行无线调试，请按提示允许。"
            ),
            "找不到入口时，可以在手机设置中搜索“开发者选项”或“无线调试”。"
        )
        TutorialStep(
            "02 · 配对并连接手机",
            listOf(
                "在系统的无线调试页面，点击「使用配对码配对」。建议用分屏同时打开系统设置和课程表，保持配对窗口打开。",
                "在课程表中，将配对窗口里「IP 地址和端口」冒号右侧的数字填入「系统显示的配对端口」，再填写「6 位配对码」，点击「连接无线调试」。",
                "例如，显示 192.168.1.8:37123 时，端口只填写 37123。",
                "配对成功后，返回系统的无线调试主页面，将这里的端口填入 App 的「系统显示的连接端口」，点击「验证连接」。看到「无线调试：已连接」即可继续。"
            ),
            "配对端口和连接端口来自两个不同页面，请分别填写，不要直接沿用。"
        )
        TutorialStep(
            "03 · 准备企业微信课表",
            listOf(
                "在课程表中点击「复制调试地址」，将复制的地址放到企业微信内打开。确认执行成功后，再进入「工作台 → 教务系统 → 我的课表」。",
                "保持「我的课表」页面打开，切回课程表，点击「检测课表页面」。看到「已找到“我的课表”」，说明准备完成。"
            ),
            "调试地址需要在企业微信内打开。"
        )
        TutorialStep(
            "04 · 获取整学期课程",
            listOf(
                "点击「开始采集」，然后切回企业微信。在「我的课表」中依次切换这个学期的各个周次，每次等待该周课表加载完成，再切换下一周。",
                "当前周也建议切到其他周后再切回来，确保重新加载。你可以随时回到课程表查看「已获取」和「还需」的周次，补齐尚未获取的部分。",
                "没有课程的周次也需要加载一次。请以 App 显示的进度为准，直到看到「整学期课表已获取完成」。"
            ),
            "采集期间请保持网络连接，避免关闭或清理这两个 App。"
        )
        TutorialStep(
            "05 · 检查并确认导入",
            listOf(
                "点击「查看课程预览」，检查课程名称、上课周次、节次和地点。下方的「导入计划」会显示新增、更新以及没有变化的课程数量。",
                "确认无误后，点击「导入课表」，再在弹窗中确认。学校课表导入会新增或更新对应课程，原有本地课程会保留。"
            )
        )
        TutorialStep(
            "06 · 开始使用",
            listOf(
                "看到「课表同步完成」，导入就完成了。点击「查看今日课程」查看当天安排，或点击「查看周视图」浏览每周课表。",
                "导入完成后，可以关闭系统的无线调试开关。学校课表后续有调整时，可再次进入「学校课表同步」获取并检查课程。"
            )
        )
        Spacer(Modifier.height(AppSpace.gap))
        SectionLabel("遇到问题时")
        TutorialHelp("配对失败", "重新打开「使用配对码配对」，填写当前显示的配对端口和配对码。")
        TutorialHelp("连接失败", "确认 Wi-Fi 和无线调试已开启，重新检查无线调试主页面上的连接端口。")
        TutorialHelp("找不到课表页面", "确认调试地址是在企业微信内打开的，并且「我的课表」仍然打开，再重新检测。")
        TutorialHelp("采集中断", "重新连接无线调试、检测课表页面，然后点击「重新连接并继续」，补齐剩余周次。已成功保存的进度可以继续使用。")
        TutorialHelp("无法导入", "查看页面提示，确认所有周次都已获取，并检查是否存在课程冲突。")
        Spacer(Modifier.height(AppSpace.gap))
        PillButton("开始导入", onStartImport)
        Text("教程随时可在「更多 → 课表导入教程」中查看。", style = AppType.caption, color = colors.mut)
        Spacer(Modifier.height(AppSpace.page))
    }
}

@Composable
private fun TutorialStep(title: String, paragraphs: List<String>, tip: String? = null) {
    val colors = LocalAppColors.current
    AppCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
            Text(title, style = AppType.cardTitle, color = colors.ink)
            paragraphs.forEach { Text(it, style = AppType.body, color = colors.ink) }
            tip?.let { Text(it, style = AppType.caption, color = colors.mut) }
        }
    }
}

@Composable
private fun TutorialHelp(title: String, answer: String) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpace.gridGap)) {
        Text(title, style = AppType.cardTitle, color = LocalAppColors.current.ink)
        Text(answer, style = AppType.body, color = LocalAppColors.current.mut)
    }
}

@Preview
@Composable
private fun ImportTutorialPreview() {
    课程表Theme { ImportTutorialScreen({}, {}) }
}
