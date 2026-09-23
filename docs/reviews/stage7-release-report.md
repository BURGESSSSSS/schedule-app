# Stage 7 发布加固与最终回归

基线为 `4822d97`（`redesign/design-brief`）。代码/构建完成，人工回归待用户确认；用户未跟踪的 `video_review/` 未触碰。版本号、Room schema、课程实体/DAO 与迁移 SQL 均未改。

## 发布与构建

`app/build.gradle.kts` 现在只在完整配置正式签名时签 release；标准 Android debug key 会给出 warning 并生成 unsigned APK，属性缺失时报错。按用户授权，在 `C:\Users\Burgess\.keystores\course-schedule-release.jks` 生成 `course-schedule-release`，RSA-4096、SHA256withRSA、有效期 10,000 天。Release APK 为 `app/build/outputs/apk/release/app-release.apk`。密钥和根目录 `keystore.properties` ACL 限制为当前用户；properties 已忽略且未跟踪。不要提交或公开这两项；另存安全离线备份，丢失后无法为后续版本使用同一签名升级。Release APK 经 `apksigner` v1/v2/v3 验证通过。发布证书 SHA-256：`27ce11cbc45b436624de3b8d2b41d3e7cc8f7676f2f33a5c6942bf2099a18256`；与 debug APK 的证书不同。此前 debug 签名安装包不能直接覆盖升级到该 release 签名包。

测试 runtime 输出改为 Gradle `layout.buildDirectory` provider，移除 `D:/CourseScheduleProjectCache/test-runtime` 和 `D:/Temp`；将 AAR 提取任务改为声明输入/输出的 task，修复 configuration-cache 捕获 `Configuration` 的问题，未升级 Gradle。最终 `assembleDebug verifyDebugUnitTests` 和 `assembleRelease` 均成功；31 项 JVM 测试此前全部通过，本次收尾时为 UP-TO-DATE。`git diff --check` 通过。

## Room 迁移与模拟器数据

静态核对完整的 1→2→3→4→5→6 迁移链，没有 destructive fallback。新增隔离 androidTest：随机独立数据库从 v5 打开为 Room v6，核对旧 courses 与 pickup 行保留、`shortName` 新列可读写，并在 finally 清理测试库。Gradle 的 `connectedDebugAndroidTest` 被外部 Maven DNS 阻断（protobuf 依赖无法解析）；改为安装 APK 后通过 `adb shell am instrument` 直接运行，2/2 测试通过。

Stage 6 截图显示 7 格，但其后数据库实际少了 ID `1789976384765`（信息安全产品配置与应用，周一）且把 ID `1789976384767` 的空教室写成字面值“未填写教室”。这与 Stage 6 删除/撤销和清空地点截图、报告结论不一致，属于 QA 数据残留。保留原始 6 行 DB/WAL/SHM 快照后，只回滚这两项；恢复库与 Stage5D 七行快照逐字段完全一致。最终设备主库 SHA-256 为 `87eb2bd559a7b27e130ec24e9805afe2f61051ef9db222a3251f4669ddbeacc4`，Room v6 identity hash 与 `schemas/6.json` 相同，7 个原 ID 全在，`PRAGMA integrity_check=ok`，pickup 表 0 行。

Stage 7 用唯一临时课程复验删除撤销：预先定位按钮后，单次 adb shell 删除并等待 800ms 点击撤销；快照确认临时 ID `1790137039977` 回来，七条原行保持。随后在 UI 中删除临时课，最终库再次逐字段等于七行基线。早先 150ms 的尝试点得过早，Snackbar 操作尚未就绪，不能算通过；纠正时序后的受控复验通过。Stage 6 原撤销验证应按本次证据更正。

## OCR 原图回归

原课表图片复制到忽略目录 `app/build/outputs/stage7/ocr-source.jpg`，SHA-256 `2bd748f7b84eac834bb150afc8546bf963301e22cc16ec850eed752377f5a3df`，尺寸 1280×2772。识别预览找到 8 个课程块，位于第 4 周：周一第 1–2、3–4、5–6 节；周三第 1–2、5–6 节；周四第 1–2 节；周五第 3–4、7–8 节。保存后 Room v6 数据库为 8 行且完整性通过，Week 与 Today 均显示新数据。识别存在可见文字误差，例如第 5 条“大学体育(三)三”多出一个“三”；第二条教室含“教數学”字形误识。没有改 OCR 算法，也不把识别准确率记为通过。随后按 Stage5D 快照精确恢复七行并完成上面的 ID、字段、schema 与完整性核验。

截图/快照位于忽略的 `app/build/outputs/stage7/`：`ocr-preview.png`、`ocr-saved-week.png`、`ocr-saved-today.png`、`device-db-after-ocr-import/` 和恢复后 DB 快照。

## 备份、静态与 UI 回归

Manifest 设 `allowBackup=false`；Android 12+ extraction rules 对 cloud backup 与 device transfer 显式排除 `root`、`external`、`device_root`、`device_file`、`device_database`、`device_sharedpref`；旧版 `backup_rules.xml` 也排除全部 app 数据。这落实“数据仅保存在本机”：系统自动备份、自动恢复及设备间迁移将不可用。Android 文档提醒部分 OEM 即使 `allowBackup=false` 仍可能允许 D2D，因此保留显式 transfer exclusions：[Android 12 行为变更](https://developer.android.com/about/versions/12/behavior-changes-12)、[Auto Backup](https://developer.android.com/identity/data/autobackup)。

360dp、字号 1.3 下留存 Today、Week、课程详情、More 的代表性截图；已复原模拟器到 1080×2424、font scale 1.0、gesture navigation、夜间模式关闭。Week 课程简称在窄格多行显示，当前截图未见新溢出。Stage 6 已有提醒、编辑、复制、系统深色下固定浅色的既有回归截图；Stage 7 未重复这些完整交互。静态入口核对未发现 JSON 导入导出、取件码、主题选择或动画速度的正式入口；More 中取件码文字仅在历史开发日志数据里。`MorphSandbox.kt` 仍有“关闭动画”预览文案，但仓库搜索没有任何调用点，属于不可达沙盒代码。

以下项目未由本阶段验证，需用户在目标设备人工检查后再作最终封板判断：真机 Predictive Back 手势取消、三键导航、提醒页在 360dp/1.3 字号下布局、详情 Morph 逐帧性质。阶段范围内不改 Morph 或 OCR 算法，也不新增功能。