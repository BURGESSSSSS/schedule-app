package com.burgess.schedule.school.sync.debug

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.burgess.schedule.ui.components.AppCard
import com.burgess.schedule.ui.components.PillButton
import com.burgess.schedule.ui.components.PillStyle
import com.burgess.schedule.ui.components.SectionLabel
import com.burgess.schedule.ui.components.SubPageScaffold
import com.burgess.schedule.ui.theme.AppSpace
import com.burgess.schedule.ui.theme.AppType
import com.burgess.schedule.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SchoolSyncProbeScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current.applicationContext
    val manager = remember(context) { ShizukuManager(context) }
    val diagnostic by manager.diagnostics.collectAsState()
    val scope = rememberCoroutineScope()
    val colors = LocalAppColors.current
    var state by remember { mutableStateOf<ShizukuState?>(null) }
    var result by remember { mutableStateOf<ScanResult?>(null) }
    var failure by remember { mutableStateOf<ProbeError?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        failure = null
        scope.launch {
            try { action() }
            catch (e: ProbeException) { failure = e.kind }
            catch (_: Exception) { failure = ProbeError.UNEXPECTED }
            finally { busy = false }
        }
    }

    SubPageScaffold("学校同步技术验证", onBack) {
        SectionLabel("仅在点击扫描时读取本机 WebView 页面标题和去参数 URL")
        AppCard(Modifier.fillMaxWidth()) {
            Text("Shizuku：${when {
                state == null -> "未检查"
                state!!.running -> "✓ 已运行"
                state!!.installed -> "× 已安装，未运行"
                else -> "× 未安装"
            }}", style = AppType.body, color = colors.ink)
            Text("授权：${when (state?.authorized) { true -> "✓ 已授权"; false -> "× 未授权"; null -> "未检查" }}", style = AppType.body, color = colors.ink)
            Text("UserService：${if (diagnostic.verified) "✓ 已绑定 / 已验证（本次）" else "未验证"}", style = AppType.body, color = colors.ink)
            Text("UID: ${diagnostic.uid?.toString() ?: "未取得"}${if (diagnostic.uid != null && !diagnostic.verified) "（非 shell UID）" else ""}", style = AppType.caption, color = colors.mut)
            Text("WebView sockets：发现 ${result?.socketCount ?: 0} 个", style = AppType.body, color = colors.ink)
            Text("CDP bridge：${when (result?.bridgeConnected) { true -> "✓ 本次连接成功，已关闭"; false -> "未连接"; null -> "未验证" }}", style = AppType.body, color = colors.ink)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
            PillButton("检查 Shizuku", { run { state = withContext(Dispatchers.IO) { manager.check() } } }, PillStyle.Secondary, small = true)
            PillButton("申请授权", { run { manager.requestPermission(); state = withContext(Dispatchers.IO) { manager.check() } } }, PillStyle.Secondary, small = true)
        }
        PillButton("验证 UserService / UID", { run {
            state = withContext(Dispatchers.IO) { manager.check() }
            manager.verifyUserService()
        } }, PillStyle.Secondary, small = true)
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
            PillButton("扫描 WebView", { run {
                state = withContext(Dispatchers.IO) { manager.check() }
                result = null
                result = CdpProbe.scan(manager)
            } }, small = true)
            PillButton("清空结果", { if (!busy) { result = null; failure = null } }, PillStyle.Ghost, small = true)
        }
        if (busy) SectionLabel("正在执行，请稍候…")
        failure?.let { Text("错误：${it.displayName()}", style = AppType.caption, color = colors.danger) }
        SectionLabel("绑定诊断（不含页面数据）")
        AppCard(Modifier.fillMaxWidth()) {
            Text("阶段：${diagnostic.stage}", style = AppType.body, color = colors.ink)
            Text("实际 applicationId：${diagnostic.applicationId}", style = AppType.caption, color = colors.mut)
            Text("namespace：${diagnostic.namespace}", style = AppType.caption, color = colors.mut)
            Text("Service class：${diagnostic.serviceClass}", style = AppType.caption, color = colors.mut)
            Text("ComponentName：${diagnostic.componentName}", style = AppType.caption, color = colors.mut)
            Text("Shizuku binder 存活：${diagnostic.shizukuBinderAlive.asCheckText()}", style = AppType.caption, color = colors.mut)
            Text("Shizuku permission：${diagnostic.permissionGranted.asCheckText()}", style = AppType.caption, color = colors.mut)
            Text("bindUserService 已发起：${diagnostic.bindRequested.asCheckText()}", style = AppType.caption, color = colors.mut)
            Text("onServiceConnected 已触发：${diagnostic.serviceConnected.asCheckText()}", style = AppType.caption, color = colors.mut)
            Text("连接时 binder 有效：${diagnostic.binderValid.asCheckText()}", style = AppType.caption, color = colors.mut)
            Text("UID 查询成功：${diagnostic.uidQuerySucceeded.asCheckText()}", style = AppType.caption, color = colors.mut)
            Text("UserService UID：${diagnostic.uid?.toString() ?: "未取得"}", style = AppType.caption, color = colors.mut)
            Text("连接已释放：${diagnostic.released.asCheckText()}", style = AppType.caption, color = colors.mut)
            diagnostic.exceptionType?.let { Text("异常类型：$it", style = AppType.caption, color = colors.danger) }
            diagnostic.exceptionMessage?.let { Text("异常消息：$it", style = AppType.caption, color = colors.danger) }
        }
        result?.errors?.forEach { Text("扫描提示：${it.displayName()}", style = AppType.caption, color = colors.mut) }
        if (result?.pages?.any { it.schoolMatch } == true) {
            Text("✓ 已发现山东商务职业学院教务系统 WebView", style = AppType.cardTitle, color = colors.ink)
        }
        if (!result?.pages.isNullOrEmpty()) {
            SectionLabel("页面（URL 已去除 query 和 fragment）")
            AppCard(Modifier.fillMaxWidth()) {
                result?.pages?.forEachIndexed { index, page ->
                    if (index > 0) HorizontalDivider(color = colors.line)
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpace.gridGap)) {
                        Text(page.title.ifBlank { "无标题" }, style = AppType.body, color = colors.ink)
                        Text(page.safeUrl, style = AppType.caption, color = colors.mut)
                        SectionLabel("Target: ${page.type.ifBlank { "unknown" }}")
                    }
                }
            }
        }
    }
}

private fun Boolean?.asCheckText(): String = when (this) { true -> "是"; false -> "否"; null -> "未检查" }

private fun ProbeError.displayName(): String = when (this) {
    ProbeError.NOT_INSTALLED -> "未安装 Shizuku"
    ProbeError.NOT_RUNNING -> "Shizuku 未运行"
    ProbeError.PERMISSION_DENIED -> "未获得 Shizuku 授权"
    ProbeError.PERMISSION_TIMEOUT -> "等待 Shizuku 授权超时"
    ProbeError.UNSUPPORTED_VERSION -> "Shizuku 版本不支持 UserService"
    ProbeError.BIND_FAILED -> "UserService 绑定失败或超时"
    ProbeError.NOT_SHELL -> "UserService 未在 shell UID 下运行"
    ProbeError.SOCKET_SCAN_FAILED -> "无法读取 WebView socket 列表"
    ProbeError.BRIDGE_FAILED -> "临时本机 bridge 连接失败"
    ProbeError.CDP_FAILED -> "DevTools 页面列表读取失败"
    ProbeError.UNEXPECTED -> "发生未预期的本机错误"
}
