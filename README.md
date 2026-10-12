# 课程表

一个使用 Kotlin 和 Jetpack Compose 开发的 Android 课程表 App。

## 下载与安装

打开 [GitHub Releases](https://github.com/BURGESSSSSS/schedule-app/releases/latest)，在 Assets 中下载 `.apk` 文件，然后在 Android 手机上打开安装。`Source code` 是源码压缩包，不是安装包。

## 功能

- 今日课程、进行中的课程和下一节课
- 周视图、周次切换和复制到下周
- 手动添加、编辑、删除课程
- OCR 课表识别与导入前修改
- 课程提醒
- 课表备份与恢复
- 应用内开发日志

课程数据使用 Room 保存在本机。最低支持 Android 7.0（API 24）。

## 在本机运行

1. 安装 Android Studio，用它打开项目根目录。
2. 按项目配置安装 Android SDK Platform 37，等待 Gradle 同步完成。
3. 连接 Android 手机或启动模拟器，选择 `app`，点击 Run。

项目使用 Gradle Wrapper，版本以 `gradle/wrapper/gradle-wrapper.properties` 为准；Gradle 守护进程的 Java 版本由 `gradle/gradle-daemon-jvm.properties` 指定为 25。

在 Windows PowerShell 中构建调试版 APK：

```powershell
.\gradlew.bat :app:assembleDebug
```

生成文件位于 `app/build/outputs/apk/debug/app-debug.apk`。

运行项目现有的 JVM 单元测试：

```powershell
.\gradlew.bat :app:verifyDebugUnitTests
```

## 正式版 APK

正式版使用本机 `keystore.properties` 配置签名。签名配置和密钥不随源码上传；没有正式签名配置时，release 构建会生成未签名的 APK，需要签名后才能安装。

```powershell
.\gradlew.bat :app:assembleRelease
```

若要在 GitHub 提供安装包下载，可将已签名的 APK 添加到仓库的 Releases 页面。

## 项目文档

- [界面设计意见书](DESIGN_BRIEF.md)
- [项目范围记录](CURRENT_SCOPE.md)
- [开发日志](docs/DEVLOG.md)
- [设计改版进度](docs/REDESIGN_PROGRESS.md)
- [第三方资源与许可](app/src/main/assets/licenses/SOURCES.md)
- [上传源码与发布 APK 的步骤](docs/GITHUB_PUBLISHING.md)

文档保留了不同阶段的记录，当前功能和构建版本请以代码为准。所有界面改动遵循 `DESIGN_BRIEF.md`。

## 上传范围

仓库包含源码、Gradle 配置、数据库 schema 和项目文档。`.gitignore` 排除了本机 SDK 路径、签名密钥、构建产物、设备凭据、日志和动画分析录屏。
