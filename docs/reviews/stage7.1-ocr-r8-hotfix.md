# Stage 7.1｜Release OCR R8 热修

## 修复

新增 `app/src/release/keepRules/mlkit-registrars.keep`，只保留实现 `com.google.firebase.components.ComponentRegistrar` 的类的公开无参构造器。Release 继续使用 `optimization.enable = true`。AGP 9.3.1 将该 variant keepRules 文件加载进 R8；合并配置见 `app/build/outputs/mapping/release/configuration.txt`（规则位于第 1088 行附近）。

R8 产物核对通过：`usage.txt` 的 `CommonComponentRegistrar`、`TextRegistrar`、`VisionCommonRegistrar` 条目中不再列出被移除的公开无参构造器；`mapping.txt` 中三个类均保留 `void <init>():0:0 -> <init>`。

## Release OCR 验证

在隔离的 `Pixel_9_OCR_diag` AVD 安装本次正式签名 Release，使用与定位阶段相同的 `app/build/outputs/stage7/ocr-source.jpg`（SHA-256：`2bd748f7b84eac834bb150afc8546bf963301e22cc16ec850eed752377f5a3df`）。通过文件名选择原图后，Release 进入截图识别预览并识别到 8 个课程块；确认导入后，Week 页面显示 8 门课程，Today 页面显示周三的 2 门课。预览和保存证据在忽略目录 `app/build/outputs/stage71/ocr-state.png`、`week-saved.png`、`today-saved.png`，完整运行日志在 `logcat-final.txt`。日志中未出现三个 registrar 的 `NoSuchMethodException: <init>[]`。

Release AVD 使用 Play Store 系统镜像，`adb root` 被系统拒绝；Release 应用也不可 `run-as`。因此没有直接查询该 Release 安装的私有 Room 数据库；持久化以保存后的 Week/Today 页面作为验证证据。测试完成后，对隔离 AVD 执行 `pm clear com.burgess.schedule`，返回原先的空课程状态，并删除本轮传入的原图副本。

## 课程基线状态

本阶段没有安装或更改 `emulator-5554` 上的应用数据。按只读方式复制其数据库后，实际查到 8 行，ID 为 `1790137499069`、`1790137499071`–`1790137499077`，`PRAGMA integrity_check` 返回 `ok`。只读快照在 `app/build/outputs/stage71/main-device-db-readonly.db`；SHA-256 为 `71DC6DD33769D4667AB00439A1EF0FC394078DD1B4E73AFAF740F289381308FE`，采集于 2026-09-23 14:11:54 +08:00。该状态与 Stage 7 报告记录的原 7 条基线不一致。本阶段没有覆盖或恢复 `emulator-5554`，因此不能声称其已恢复为 7 条；Stage 7.1 的测试数据仅在隔离 AVD 中导入并清除。

## 构建与签名

`assembleRelease` 构建成功。随后执行 `assembleDebug verifyDebugUnitTests assembleRelease --console=plain`，构建成功；三个任务均为 `UP-TO-DATE`（Debug JVM 测试此前 31 项通过）。`git diff --check` 通过。

正式 APK：`app/build/outputs/apk/release/app-release.apk`，SHA-256：`689D72428264D1A98BB2A7DB8CD4BFEE7DA37E02E910F5889D8B5E5102D11F5D`。`apksigner verify --verbose --print-certs` 通过，签名方案为 v2；证书 SHA-256：`27ce11cbc45b436624de3b8d2b41d3e7cc8f7676f2f33a5c6942bf2099a18256`。
