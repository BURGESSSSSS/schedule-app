# Stage 6 实施报告

## 1. 大白话总结

More 现在只保留课程提醒、开发日志、关于开发者和底部版本；开发者邮箱直接显示在头像下方。开发日志仍保留原文，按最新版本在前显示。课程提醒继续使用原来的调度、开关、提前时间、权限提示和测试通知。产品固定浅色，已移除取件码入口/界面、今日简报卡、JSON 导入导出及用户主题/动画速度设置。

## 2. 改动文件

- 修改：`app/src/main/AndroidManifest.xml`、`app/src/main/java/com/burgess/schedule/ui/app/ScheduleApp.kt`、`app/src/main/java/com/burgess/schedule/ui/more/MoreScreen.kt`、`app/src/main/java/com/burgess/schedule/ui/theme/Color.kt`、`app/src/main/java/com/burgess/schedule/ui/theme/Motion.kt`、`app/src/main/java/com/burgess/schedule/ui/theme/Theme.kt`、`app/src/main/java/com/burgess/schedule/ui/today/TodayScreen.kt`、`app/src/test/java/com/burgess/schedule/ui/CourseContrastTest.kt`。
- 删除：`app/src/main/java/com/burgess/schedule/backup/BackupActions.kt`、`BackupCodec.kt`、`app/src/main/java/com/burgess/schedule/pickup/PickupWebViewActivity.kt`、`app/src/main/java/com/burgess/schedule/ui/components/DesignPreview.kt`、`app/src/main/java/com/burgess/schedule/ui/theme/Appearance.kt`、旧入口专属图标 `symbol_inventory_2.xml`、`symbol_palette.xml`、`symbol_speed.xml`，以及取件码专用 `app/src/main/res/xml/file_paths.xml` 和未再使用的 `network_security_config.xml`。

## 3. 自查

- §7.1 页面原则：More 图标为黑白灰 Material Symbols；课程色可读性测试保留并检查固定浅色下全部 `CoursePalette` 组合（`CourseContrastTest.kt:6`）。Today 仅去掉简报卡及相关参数，其余课程显示逻辑未改（`TodayScreen.kt`）。Today/Week 的时间、网格、课程编辑与导入业务未改；模拟器确认周视图、课程详情和 OCR 导入入口可打开。
- §7.1 Room 边界：无实体、DAO、Repository、ViewModel 业务或 schema/迁移改动。Room v6 的 `pickup_codes` 表继续由 `CourseDatabase`、`PickupCodeEntity.kt`、`PickupCodeDao.kt` 保留为惰性兼容 schema；产品不再查询或展示它。没有数据库迁移。
- §5.3 详情转场：`ui/morph/` 未改，现有单一进度、关闭后清理、双层内容、来源占位、顶层绘制和动画实现沿用。本轮模拟器打开了 Today/Week 详情，但未做逐帧动画复验。
- §7.1 可构建/回退：`assembleDebug` 和 `verifyDebugUnitTests` 成功；31 项测试通过，0 失败/错误/跳过。`git diff --check` 通过。按要求保持未提交状态，便于主控验收。

## 4. 与意见书的偏差

本轮冻结范围优先于 DESIGN_BRIEF §4.5 中已过时的取件码、主题选择和“点击复制邮箱”建议。More 仅保留课程提醒与两项关于入口；邮箱直接显示；界面固定浅色。开发日志原文保留并倒序显示。周视图中的“导入”仍用于 OCR 图片导入，未移除。

## 5. 模拟器检查与截图

已在 Android 模拟器上完成：More 结构；开发者页邮箱可见且未截断；开发日志最新版本置顶并默认展开；提醒页安全区、分组选项的对齐与留白、当前 20 分钟默认值说明、开关开/关、提前时间选项、通知权限提示/授权、测试通知及精确闹钟设置入口；系统深色下 More、Today、Week 和详情仍为浅色；OCR 导入入口；删除课程后点击撤销，原课程恢复。编辑保存实测：把周二 10:00 课程地点从空值改为 `RVR` 并保存，周视图课程节点与详情页均显示 `RVR`；再清空地点并保存，周视图和详情恢复为原来的“未填写”。课程最终值已复原。

未复验：OCR 选图后的识别与保存流程；详情转场逐帧性质。本轮只验证了 OCR 入口和详情页可用。

截图保存在被忽略的 `app/build/outputs/stage6/`：`more-dark-system.png`、`developer.png`、`reminder-safe.png`、`reminder-afterfix.png`、`test-notification.png`、`devlog.png`、`today-dark-system.png`、`week-dark-system.png`、`detail-dark-system.png`、`ocr-entry.png`、`editor.png`、`week-rvr.png`、`detail-rvr.png`、`week-room-restored.png`、`detail-room-restored.png`、`delete-undo.png`、`undo-restored.png`。`reminder-afterfix.png` 显示选项与行文字左边缘对齐、默认 20 分钟提示可见；`week-rvr.png` 与 `detail-rvr.png` 记录保存后的地点值，`week-room-restored.png` 与 `detail-room-restored.png` 记录还原后的状态。模拟器提醒偏好和通知权限已恢复为默认/拒绝状态，系统夜间模式已恢复为关闭。
