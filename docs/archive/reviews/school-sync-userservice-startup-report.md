# 学校同步第一阶段：UserService 启动失败真机诊断

## 本轮结果

已经在红米 K80 Ultra 真机抓到导致绑定超时的启动异常。Shizuku 收到了绑定请求，也启动了服务进程，但它在创建 Android `Application` 时崩溃；我们的 `ShizukuUserService` 类尚未开始初始化，因此没有机会返回 Binder 或 UID。新 Debug APK 只增加安全的启动日志并更新 UserService 诊断版本，真机复测仍超时。本阶段 **没有达到 UID 2000 验收标准**。

## 环境与实现核对

- 真机：Redmi K80 Ultra，Xiaomi HyperOS 3.0，Android 16（API 36），MediaTek `mt6991`。
- 已安装 Shizuku：`13.6.0.r1086.2650830c`；App：`com.burgess.schedule`，Debug 包版本 `1.3.2`。
- App 已显示 Shizuku 运行、授权通过、Shizuku Binder 存活；真机 Logcat 证实服务端接受了 `version=1` 和 `version=2` 的绑定请求。
- 原 `ShizukuUserService` 是公开顶层 Kotlin 类，继承 `IUserService.Stub()`，有公开无参构造器，因而实现 `IBinder`；不是普通 `android.app.Service`。构造器没有引用 Application、Compose、DI 或 Context。此形式符合 [Shizuku UserService 官方说明](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md)及[官方示例](https://github.com/RikkaApps/Shizuku-API/blob/master/demo/src/main/java/rikka/shizuku/demo/service/UserService.java)。
- `ComponentName` 的包名和类名匹配实际 applicationId；Debug 没有 `applicationIdSuffix`，未启用 R8。APK DEX 包含原名的 `ShizukuUserService`、`IUserService` 和 `IUserService$Stub`。AIDL 转换仅在连接回调后执行，因此不是本次回调缺失的原因。
- `UserServiceArgs` 已具有非空 `processNameSuffix`、tag、version、`daemon(false)`。`ServiceConnection` 处理连接、断开、绑定死亡和空绑定。20 秒客户端超时未改动。

## 真机根因证据

在旧 APK 上清空 Logcat 后点击一次“验证 UserService / UID”；再安装新 APK（`adb install -r` 成功，保留数据），清空 Logcat 并重复同一操作。两次结果相同：App 20 秒超时，Shizuku 服务端 30 秒后确认进程未启动。第二次必要日志摘录如下；已省略服务记录 ID、APK 安装路径与无关日志：

```text
I UserServiceManager: New service record com.burgess.schedule:school_webview_probe: version=2, daemon=false
I ShizukuServiceStarter: starting service com.burgess.schedule/...ShizukuUserService
W ShizukuServiceStarter: unable to start service com.burgess.schedule/...ShizukuUserService
W ShizukuServiceStarter: java.lang.reflect.InvocationTargetException
W ShizukuServiceStarter: Caused by: java.lang.NullPointerException
W ShizukuServiceStarter:   at android.app.LoadedApk.makeApplicationInner(LoadedApk.java:1605)
W ShizukuServiceStarter:   at android.app.LoadedApk.makeApplication(LoadedApk.java:1495)
W UserServiceRecord: Service record [redacted] is not started in 30000 ms
```

新 APK 的 DEX 含 `CLASS_INITIALIZED`、`CONSTRUCTOR_ENTERED`、`BINDER_STUB_READY`、`CONSTRUCTOR_COMPLETED` 和 `GET_UID` 标记；本次 Logcat **没有任何这些标记**。结合堆栈，失败发生在 Shizuku 服务端通过反射创建 App `Application` 的阶段，早于加载/实例化我方 UserService。Shizuku 上游的[联发科设备 UserService 启动修复 PR #299](https://github.com/RikkaApps/Shizuku-API/pull/299)描述了相同的 `makeApplication` 失败路径，并提供服务端回退方案；截至本轮检查，该 PR 仍为 Open。当前证据支持“已安装 Shizuku 13.6.0 的服务端启动流程在本机崩溃”，不支持把我方 Stub、构造器或客户端超时判为根因。

## 修改文件与原因

| 文件 | 修改 | 原因 |
| --- | --- | --- |
| `app/src/main/java/com/burgess/schedule/school/sync/debug/ShizukuUserService.kt` | 仅记录类初始化、构造、Binder Stub 与 `getUid()` 的阶段、PID、UID | 判断服务是否执行到我方代码；不记录页面、URL 或凭据 |
| `app/src/main/java/com/burgess/schedule/school/sync/debug/ShizukuManager.kt` | UserServiceArgs `version(1)` 改为 `version(2)` | 安装含新标记的 APK 后确保新服务记录被使用；不改变绑定模型或超时 |
| 本报告 | 保存脱敏错误、测试与结论 | 供后续 Shizuku 兼容性复测 |

未修改正式页面、OCR、课程表数据库、通知、编辑、CDP 或课表业务；未开发第二阶段功能。

## Luna 机械验证与 Sol 真机复核

| 项目 | 结果 |
| --- | --- |
| clean Debug build（修改前基线） | 通过，`clean assembleDebug` |
| 最终 Debug build | 通过，`assembleDebug` |
| 现有 JVM 测试 | 使用完整运行时 classpath 直接运行 JUnitCore：35 项通过、0 失败 |
| 项目自定义 `verifyDebugUnitTests` 任务 | 失败：Gradle Test Worker 无法执行测试类；不能写作此任务通过。直接 JUnit 的初次依赖缺失已修正后复跑通过 |
| APK 类/标记与 manifest | 通过：DEX 含原名服务类、AIDL Stub 和 5 个新标记；实际 applicationId 与 Shizuku provider authority 匹配 |
| 修改范围、敏感内容静态检查 | 通过：只改绑定诊断相关文件；新增日志不读取 Cookie、Token、Session、页面内容或 URL |
| 真机 Shizuku 运行、授权、绑定请求 | 已验证：App 状态与 Shizuku 服务端记录一致 |
| 真机 UserService 类初始化、连接回调、UID 2000 | **失败**：服务端先在 `makeApplicationInner` 崩溃；没有我方启动标记、回调或 UID |

新 APK：`app/build/outputs/apk/debug/app-debug.apk`

SHA-256：`0663328ACE54B81E202F3B067A0C07BABB895C415F33E69ED19B914D53CC5B9D`

## 后续真机重测条件

当前安装的 Shizuku 13.6.0 仍会在我方类运行之前崩溃，重复调整 App 的 AIDL、构造器、UI 或客户端等待时间无法修复这一堆栈。需要先有能在本机完成 UserService 启动的 Shizuku 服务端版本。届时使用本 APK 点击一次“验证 UserService / UID”，应依次在 Logcat 看到 `CLASS_INITIALIZED`、`CONSTRUCTOR_COMPLETED`、`GET_UID uid=2000`，页面应显示连接成功、UID 2000。直到真机实际出现这些证据，绑定验收继续标为未通过。

本轮未替换、降级或修改用户安装的 Shizuku，也未继续开发第二阶段。
