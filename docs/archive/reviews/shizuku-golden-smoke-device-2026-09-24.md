# Golden UserService 真机 Smoke Test（2026-09-24）

## 范围与环境

- 分支：`codex/shizuku-golden-smoke`；被测代码提交：`b8ee9155fb0754b45f92c96d0d40ec7a832d26d4`。
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`；SHA-256：`E7CB3F21343010C9ADFF2A81B822B8A50E600240A5071B798FF9EF7BF11DCA12`。
- 设备：红米 K80 Ultra；Android 16（API 36）；HyperOS `OS3.0`。
- 已安装 Shizuku：`13.6.0.r1086.2650830c`；课程表 App：`1.3.2`，targetSdk 37。
- 经 USB ADB 使用 `install -r -t` 保留数据安装上述 APK，仅打开课程表 App 的“学校同步技术验证”，检查 Shizuku 后点击一次“验证 Golden UserService”。没有打开企业微信、点击 WebView 扫描、安装或修改 Shizuku。

## 结果

| 项目 | 结果 |
| --- | --- |
| ADB 连接 | 已验证，设备在线 |
| Shizuku 运行和授权 | 页面显示已运行、已授权 |
| Golden bind 请求 | Shizuku 服务端收到；记录 `version=22`、`daemon=false` 和 Golden 服务类名 |
| UserService 进程 | 已启动，但在我方类初始化之前退出 |
| `onServiceConnected` | 未触发 |
| `ping()` / `getUid()` | 未执行；页面未取得 `pong` 或 UID |
| UID 2000 验收 | **未通过** |

页面保持“Golden UserService：未验证 / onServiceConnected：否 / ping：未取得 / UID：未取得”。测试后退出验证页，触发页面解绑清理。

## 必要的脱敏 Logcat 摘录

以下仅保留启动链路和异常，省略服务记录 ID、APK 安装路径、无关日志及任何页面数据：

```text
08:42:59.826 I UserServiceManager: New service record com.burgess.schedule:com.burgess.schedule.school.sync.golden.ShizukuUserService; version=22, daemon=false, apk=[REDACTED]
08:42:59.827 V UserServiceManager: Starting process for service record ...
08:43:00.051 D AndroidRuntime: Calling main entry moe.shizuku.starter.ServiceStarter
08:43:00.061 I ShizukuServiceStarter: starting service com.burgess.schedule/com.burgess.schedule.school.sync.golden.ShizukuUserService...
08:43:00.431 W ShizukuServiceStarter: unable to start service ...
08:43:00.431 W ShizukuServiceStarter: java.lang.reflect.InvocationTargetException
08:43:00.431 W ShizukuServiceStarter: Caused by: java.lang.NullPointerException: Attempt to invoke virtual method 'boolean java.lang.Object.equals(java.lang.Object)' on a null object reference
08:43:00.431 W ShizukuServiceStarter: at android.app.LoadedApk.makeApplicationInner(LoadedApk.java:1605)
08:43:00.431 W ShizukuServiceStarter: at android.app.LoadedApk.makeApplication(LoadedApk.java:1495)
08:43:00.431 I com.burgess.schedule:user_service: System.exit called, status: 1
08:43:29.841 W UserServiceRecord: Service record ... is not started in 30000 ms
```

没有 `GoldenUserService` 的类初始化、构造、`ping()` 或 `getUid()` 日志。与 `school-sync-userservice-startup-report.md` 中 Candidate 真机测试相比，失败点仍为 `LoadedApk.makeApplicationInner(LoadedApk.java:1605)`，发生在我方 UserService 类执行之前。此次 Golden 参数和最小服务结构未改变该机当前环境的启动结果。

朋友源码压缩包缺少 Gradle 文件及自定义 Application 源码；朋友成功测试时的 HyperOS、Shizuku Manager 和 Shizuku API 依赖版本仍未知。**不能由本次失败推断同机型不兼容，也不能据此认定必须修改 Shizuku。** 下一步应先核实这些版本差异或取得朋友成功运行时的脱敏启动日志，再决定后续实验。
