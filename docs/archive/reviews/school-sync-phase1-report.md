# 学校课表自动同步：第一阶段技术验证报告

## 大白话总结

Debug 包的“更多 → 学校同步技术验证”现在可以由用户手动检查 Shizuku、申请授权并扫描 WebView 页面。扫描只请求 DevTools 的 `/json/list`，界面显示页面标题、target 类型和去除 query、fragment、userinfo 的 URL。临时桥只监听 `127.0.0.1`，有随机 token，单次连接完成后关闭。没有连接到 Android 设备，因此本次交付是可编译的验证工具，企业微信教务页尚未完成真机验证。

## 项目审查

- applicationId / namespace：`com.burgess.schedule`；Kotlin、Jetpack Compose、Material 3、Room。
- Gradle 9.5.0、AGP 9.3.1、Kotlin 2.2.10；minSdk 24、targetSdk 37。
- `ScheduleApp` 以状态管理“今日 / 周视图 / 更多”和子页；“更多”原有课程提醒、开发日志、关于开发者；无学校同步调试页或 Shizuku 依赖。
- 朋友项目实现文件未在当前工作区提供；按当前包名与导航重新实现，依据 [Shizuku 官方 UserService 文档](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md)。

## 修改文件

- `gradle/libs.versions.toml`：Shizuku API / provider 13.1.5。
- `app/build.gradle.kts`：依赖，以及 AIDL 的中文路径构建兼容处理。
- `app/src/main/AndroidManifest.xml`：Shizuku provider、包可见性、`INTERNET` 和网络安全配置。
- `app/src/main/res/xml/network_security_config.xml`：只允许 `127.0.0.1` 明文 HTTP，用于临时桥。
- `app/src/main/aidl/com/burgess/schedule/school/sync/debug/IUserService.aidl`：Binder 协议。
- `app/src/main/java/com/burgess/schedule/school/sync/debug/IUserService.java`：由 build-tools 36 的 AIDL 编译器生成的 Binder 接口。
- `app/src/main/java/com/burgess/schedule/school/sync/debug/ShizukuManager.kt`：检查、授权、绑定、验证 shell UID、解绑。
- `app/src/main/java/com/burgess/schedule/school/sync/debug/ShizukuUserService.kt`：shell UID、socket 枚举与桥的生命周期管理。
- `app/src/main/java/com/burgess/schedule/school/sync/debug/DevtoolsBridge.kt`：临时回环代理，只接受带 token 的 `/json/list` 请求。
- `app/src/main/java/com/burgess/schedule/school/sync/debug/CdpProbe.kt`：只读取 `/json/list` 并脱敏 URL。
- `app/src/main/java/com/burgess/schedule/school/sync/debug/SchoolSyncProbeScreen.kt`：仅 Debug 入口可达的技术验证页面。
- `app/src/main/java/com/burgess/schedule/ui/app/ScheduleApp.kt`、`app/src/main/java/com/burgess/schedule/ui/more/MoreScreen.kt`：接入 Debug 页面。
- `app/src/test/java/com/burgess/schedule/school/sync/debug/CdpProbeTest.kt`：URL 脱敏测试。
- 本报告。

## 测试结果与失败原因

- `:app:assembleDebug`：通过。
- 直接运行已编译的 JUnit 类：33 项通过，包含新增 URL 脱敏测试 2 项。
- `:app:verifyDebugUnitTests` 和 `:app:testDebugUnitTest`：本机失败；Gradle Test Worker 对所有测试类报 `ClassNotFoundException`，但相同的 `schedule-tests.jar` 中能找到这些类，直接 JUnit 运行成功。此问题不说明测试用例失败；Gradle 测试任务仍需单独修复。
- AIDL 原生构建：AGP 9.3.1 在项目中文路径上读取 AIDL `.d` 依赖文件时报 `MalformedInputException`。因此保留 `.aidl` 合同，同时检入由 build-tools 36 生成的 `IUserService.java` 并关闭 AGP AIDL 任务，以保证从当前路径正常编译。这是 §9 阶段报告中的实现偏差。
- `adb devices -l`：没有连接设备。Shizuku、UserService、WebView socket、bridge 与学校域名都没有真机结果。
- Logcat：没有设备，因此没有 Logcat 错误信息。代码不输出原始 DevTools 响应、URL 或 token。
- `video_review/` 是工作区原有未跟踪目录，本阶段未修改。

## 验收状态

| 项目 | 状态 |
|---|---|
| 项目正常编译 | 通过：Debug APK 构建成功 |
| 原课程表功能不受影响 | 静态核对：业务数据、OCR、通知、编辑均未修改；33 项 JVM 测试通过；真机回归待做 |
| 检测 Shizuku | 代码已实现，待设备验证 |
| 申请并获得授权 | 代码已实现，待用户在设备上操作验证 |
| UserService 绑定 | 代码已实现，待设备验证 |
| UserService 为 shell UID 2000 | 代码强制校验，待设备验证 |
| 发现 WebView DevTools socket | 代码已实现，待企业微信页面实测 |
| 临时 bridge 建立 CDP 连接 | 代码已实现，待设备验证 |
| 枚举 WebView 页面 | 代码已实现，待设备验证 |
| 识别 `testjwgl.sdbi.edu.cn` | 匹配逻辑已实现，待学校页面实测 |
| 不读取或输出 Cookie / Token / Session | 静态核对通过；仅调用 `/json/list`，URL 脱敏测试通过；真机 Logcat 待核 |

## 设计自查及偏差

- §2：Debug 页面使用现有 `SubPageScaffold`、中性色和列表组件；正式“更多”页只在 Debug 包多一个入口。没有增加彩色图标、大面积黑块或装饰动效。
- §5.3：详情转场相关文件和逻辑未修改，六条性质不受本阶段代码直接影响；未做真机视觉回归。
- §7.1：没有修改 Room、OCR、通知、编辑及课程页面的业务逻辑；构建可通过，工作在独立 `codex/school-sync-probe` 分支。
- §9：先审查并给计划，再分阶段实现；AIDL 生成文件检入是因中文路径和 AGP 依赖文件编码冲突，见上。
- §11：未扩展可选增强；用户点名的学校同步技术验证独立于该列表。

## 真机检查清单

1. 安装 Debug APK，打开“更多 → 学校同步技术验证 → 检查 Shizuku”：应显示安装、运行和授权状态。
2. 如未授权，点“申请授权”并在 Shizuku 弹窗同意：应显示“✓ 已授权”。
3. 在企业微信中打开“工作台 → 教务系统 → 我的课表”，回到本 App 点“扫描 WebView”：应显示 UID 2000、socket 个数、临时 bridge 连接状态和去参数 URL。
4. 如页面可通过 CDP 发现，应显示“✓ 已发现山东商务职业学院教务系统 WebView”。如没有出现，记录页面上的错误类型和脱敏 Logcat；不要绕过企业微信安全机制。
5. 回到今日页、周视图，检查既有查看、编辑与提醒入口；这些需要真机回归才可最终确认。
