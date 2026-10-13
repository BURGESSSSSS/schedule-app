# 课程表

一个使用 Kotlin 和 Jetpack Compose 开发、为山东商务职业学院学子定制的 Android 课程表 App。当前正式版为 **1.3.7**。

## 下载与安装

打开 [GitHub Releases](https://github.com/BURGESSSSSS/schedule-app/releases/latest)，在 Assets 中下载 `.apk` 文件，然后在 Android 手机上打开安装。`Source code` 是源码压缩包，不是安装包。

## 功能

- 今日课程、进行中的课程和下一节课
- 周视图、周次切换和复制到下周
- 手动添加、编辑、删除课程
- OCR 课表识别与导入前修改
- 企业微信教务课表整学期导入
- 首次使用引导与课表导入教程
- 课程提醒
- 课表备份与恢复
- 应用内开发日志

课程数据使用 Room 保存在本机。最低支持 Android 7.0（API 24）；学校课表导入需要 Android 11 或以上及无线调试功能。

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
.\gradlew.bat :app:verifyDebugUnitTests --no-configuration-cache
```

## 正式版 APK

正式版使用本机 `keystore.properties` 配置签名。签名配置和密钥不随源码上传；没有正式签名配置时，release 构建会生成未签名的 APK，需要签名后才能安装。

```powershell
.\gradlew.bat :app:assembleRelease --no-configuration-cache
```

正式签名 APK 和 SHA-256 校验文件通过 [GitHub Releases](https://github.com/BURGESSSSSS/schedule-app/releases/latest) 提供。本机最终包另存于 `dist/`，不纳入源码仓库。

## 目录结构

- `app/`：源码、业务测试、资源和数据库 schema
- `gradle/`：Gradle Wrapper 与依赖配置
- `docs/`：当前说明、开发日志、发布记录
- `docs/archive/`：历史设计资料、阶段报告及验收截图
- `dist/`：本机最终安装包与校验文件（不上传到源码）

## 项目文档

- [界面设计意见书](DESIGN_BRIEF.md)
- [项目范围记录](CURRENT_SCOPE.md)
- [开发日志](docs/DEVLOG.md)
- [收尾与发布记录](docs/FINAL_RELEASE.md)
- [历史开发资料](docs/archive/README.md)
- [第三方资源与许可](app/src/main/assets/licenses/SOURCES.md)
- [上传源码与发布 APK 的步骤](docs/GITHUB_PUBLISHING.md)

历史资料保留各阶段当时的状态，当前范围以 `CURRENT_SCOPE.md` 与代码为准。所有界面改动遵循 `DESIGN_BRIEF.md`。

## 上传范围

仓库包含源码、Gradle 配置、数据库 schema 和项目文档。`.gitignore` 排除了本机 SDK 路径、签名密钥、构建产物、设备凭据、日志和动画分析录屏。
