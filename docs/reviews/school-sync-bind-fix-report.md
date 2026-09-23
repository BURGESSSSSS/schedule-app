# 学校同步第一阶段：UserService 绑定修复报告

## 大白话总结

上一个 Debug 包在申请 UserService 时漏填 Shizuku 必需的“进程名后缀”。Shizuku 在真正发起绑定前就会抛异常，而旧代码把这个异常藏起来，统一显示“绑定失败或超时”。本轮补上该参数，并加了独立的“验证 UserService / UID”按钮。这个按钮只绑定服务并调用一次 `getUid()`，不会扫描 WebView。诊断页会显示每个绑定阶段和已脱敏的异常。当前没有连接的 Android 设备，修复是否在真机上稳定成功仍需用户复测。

## 根因与证据

- 当前工程依赖 `dev.rikka.shizuku:api:13.1.5`。直接检查本地该版本 `api-13.1.5.jar` 的 `Shizuku.UserServiceArgs.forAdd()` 字节码，确认它对 `processName` 执行 `Objects.requireNonNull(..., "process name suffix must not be null")`。
- 旧 `ShizukuManager.kt` 构造 `UserServiceArgs` 时调用了 `daemon(false)`、`tag(...)`、`version(1)`，但没有调用 `processNameSuffix(...)`。因此 `Shizuku.bindUserService()` 在客户端同步抛异常，`onServiceConnected` 不会触发；旧 `catch (_: Exception)` 丢弃异常类型和消息。
- 已补上固定后缀 `processNameSuffix("school_sync_probe")`。本地字节码还确认该版本 `bindUserService()` 返回 `void`，不存在 Boolean 返回值可检查。
- `applicationId`、namespace 均为 `com.burgess.schedule`，Debug 未设 `applicationIdSuffix`；`ComponentName` 的包和类正确。UserService 为公开类、有无参构造、继承 AIDL Stub。Debug 不经过 R8。故没有证据把这些列为本次根因。

## 修改文件与原因

- `app/src/main/java/com/burgess/schedule/school/sync/debug/ShizukuManager.kt`：补必需的 `processNameSuffix`；区分绑定请求、回调、Binder、UID、超时和异常；只在 UID 为 2000 时标记已验证；超时或完成后的迟到回调不覆盖结果；解绑成功才显示“已释放”。
- `app/src/main/java/com/burgess/schedule/school/sync/debug/BindingDiagnostics.kt`：绑定状态和安全异常消息格式；去除 URL、query 及敏感字段值。
- `app/src/main/java/com/burgess/schedule/school/sync/debug/SchoolSyncProbeScreen.kt`：新增只调用 `getUid()` 的验证按钮，并显示 applicationId、namespace、ComponentName、Shizuku 状态、绑定回调、Binder、UID、异常类型及脱敏消息。
- `app/src/test/java/com/burgess/schedule/school/sync/debug/BindingDiagnosticsTest.kt`：验证 UID 2000 门槛和异常脱敏。
- 本报告。

## 测试与审查

- Sol：复核 Shizuku 13.1.5 API 字节码、根因、回调终态、UID 门槛和解绑真实性，未发现阻断问题。
- Luna：`:app:assembleDebug` 通过；直接运行已编译的 9 个 JUnit 类，**35 项通过、0 失败**；`git diff --check` 通过。
- `:app:verifyDebugUnitTests` 仍因本机 Gradle Test Worker 无法执行全部测试类而失败。相同编译输出的测试类用 JUnitCore 和完整运行依赖直接运行成功；这是测试任务的运行问题，不可写成 Gradle 测试通过。
- 静态检查：未修改课表数据库、OCR、提醒、编辑等业务；未添加第二阶段下载、解析、导入；未添加凭据读取。既有临时 bridge token 与 Binder interface token 不是登录凭据。
- `adb devices -l` 无设备，无法取得本轮 Logcat。必要错误信息将由 Debug 页以脱敏的“异常类型 / 异常消息”显示；本报告没有真机错误日志可保存。

## 设计自查与偏差

- §2：仅在已有 Debug 子页增加中性色诊断文字和一个小按钮，沿用现有组件；正式课程页面视觉不变。
- §5.3：详情转场代码未修改，六项性质没有受到本轮代码直接影响；真机视觉回归未做。
- §7.1 / §9：没有修改课程业务逻辑；在独立分支做最小修复，Debug APK 可编译，改动可回退。
- §11：未开发可选增强；也未继续第二阶段。
- 与 `DESIGN_BRIEF.md` 无新的设计偏差。前一阶段保留的 AIDL 中文路径构建处理未在本轮修改。

## 验收状态

| 项目 | 状态 |
|---|---|
| 根因定位 | **已定位**：缺少 `processNameSuffix`，本地依赖字节码直接证实 |
| 代码修复 | **已实现**：增加后缀与独立 UID 验证入口 |
| Debug 编译 | **通过** |
| JVM 自动测试 | 直接 JUnitCore 35/35 通过；Gradle Test Worker 任务仍失败 |
| 真机 Shizuku 运行与授权 | 用户上一轮报告已通过；新 APK 尚未复测 |
| 真机 UserService 绑定、UID 2000 | **待真机验证** |
| 连续 3 次绑定稳定性 | **待真机验证** |
| 本轮目标是否达成 | **尚不能判定达成**，需新 APK 的真机结果 |

## 真机重新测试

1. 安装 `app/build/outputs/apk/debug/app-debug.apk`，打开“更多 → 学校同步技术验证”。
2. 点“检查 Shizuku”，应仍显示“已运行 / 已授权”。
3. 连续点 3 次“验证 UserService / UID”，每次等待结果。期望显示“UserService：已绑定 / 已验证（本次）”、`UID: 2000`、`阶段：UID_VERIFIED`、`连接已释放：是`。此按钮不扫描 WebView。
4. 若有一次失败，只记录“阶段、异常类型、脱敏异常消息、UID”以及是否触发 `onServiceConnected`。不要提供页面 URL 或登录信息；本轮不尝试绕过企业微信。
