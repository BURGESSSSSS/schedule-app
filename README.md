# 课程表计划

这是一个面向 Android 的本地课程表应用，目标是让用户可以快速查看每周课程、知道下一节课，并在上课前收到通知提醒。

## 当前状态

- 已完成：Android Studio、SDK、模拟器配置
- 已完成：Jetpack Compose 周视图课程表界面
- 已完成：本地 Room 数据库，以及添加、编辑、删除课程
- 已完成：当前正在上的课或下一节课的高亮显示
- 已完成：企业微信截图分享入口和中文 OCR 课程草稿导入
- 已完成：通知权限、测试通知、按周提醒和设备重启后恢复
- 已完成：Debug APK 编译验证
- 未完成：CSV 文件导入、学期开始日期设置、H5 链接直接导入
- 当前阶段：可运行的功能原型，等待真机验证

## 第一版目标

第一版先实现以下功能：

1. 以周视图显示课程表
2. 添加、编辑、删除课程
3. 根据学期开始日期计算当前周
4. 高亮当前正在上的课或下一节课
5. 在上课前发送本地通知
6. 通过 CSV 文件导入课程
7. 从企业微信分享课表截图，识别并导入课程草稿
7. 所有数据保存在手机本地，不需要登录和服务器

Excel/XLSX 导入、桌面小组件、云同步和课程分享先放到后续版本。

## 推荐技术

- Kotlin
- Jetpack Compose
- Material 3
- ViewModel
- Room
- DataStore
- WorkManager
- Android Storage Access Framework

## 开发前需要安装

1. Android Studio 稳定版
2. Android SDK Platform 和 Build Tools
3. 一个 Android 模拟器，或一台开启 USB 调试的 Android 手机

不需要单独安装 Gradle。创建项目后，Android Studio 会使用项目自己的 Gradle 配置。

## 下一步

1. 在模拟器或真实手机上验证企业微信截图识别结果并修正识别规则
2. 接入 Android 文件选择器，完成 CSV 导入和格式校验
3. 增加学期开始日期设置，按周次和单双周显示课程
4. 在真实手机上验证通知权限、省电策略和后台行为
5. 如果 H5 支持在外部浏览器登录，再评估 App 内 WebView 导入

## 产品原则

- 不做闹钟页面，不提供响铃、贪睡和闹钟列表
- 通知是课程提醒，不是闹钟功能
- 首先保证打开速度和查看课程的体验
- 提醒先采用非精确后台调度，允许系统为了省电而延迟

## 本机空间说明

- Android Studio：`D:\android`
- Android SDK：`D:\AndroidSdk`
- Gradle 用户缓存：`D:\GradleHome`
- Gradle 临时目录：`D:\Temp`
- 项目构建缓存：`D:\CourseScheduleProjectCache`

Android 模块的临时构建产物仍使用项目目录下的标准 `app/build` 路径，这是为了兼容 Room/KSP 的源码生成规则；大型 Gradle 缓存和临时文件已经放在 D 盘。
