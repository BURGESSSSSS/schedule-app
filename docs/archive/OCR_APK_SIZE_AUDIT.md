# Release APK OCR 包体审计

审计对象：`app/build/outputs/apk/release/app-release.apk`，Release 1.3.2（versionCode 11）。本轮只读取 APK 与构建依赖；没有改动 OCR 引擎、模型、解析流程或精度相关配置。

## 结论

- OCR 使用 **Google ML Kit Chinese Text Recognition 16.0.1**。代码通过 `ChineseTextRecognizerOptions` 建立识别器。
- OCR 模型随 APK 本地打包，支持离线识别。APK 中有 25 个 `mlkit-google-ocr-models` 资产，以及四个 ABI 的 `libmlkit_google_ocr_pipeline.so`。
- OCR 相关压缩载荷为 **42,957,802 bytes（42.96 MB / 40.96 MiB）**，约占 57,921,397-byte APK 的 **74.17%**。对应未压缩载荷为 43,523,970 bytes（43.52 MB / 41.51 MiB）。
- OCR 资产包含 Hani（中文）和 Latn（拉丁字符）识别模型，以及文本检测、版面/行分割和语言识别模型。没有发现 SHA-256 完全相同的 OCR 资产；也没有证据确认某个语言模型未使用。
- APK 含 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 四种 ABI。四份 ML Kit native pipeline 是平台构建，不是可直接删除的重复文件。
- 没有发现可在不审核引用关系的前提下安全删除的 OCR 模型或资源，因此本轮只提交审计文档。

## 审计方法

用 `aapt dump badging` 核对包名、版本、SDK 与 native ABI；用 `aapt2 dump resources` 确认资源映射；逐项读取 APK ZIP entry 的压缩/未压缩大小；对 OCR 资产计算 SHA-256；用 Gradle `dependencyInsight` 查看 Release 运行时 ML Kit 依赖。字节数中的“压缩”指 APK ZIP entry 的 `compressed size`；整包文件还包含 ZIP/签名等结构开销。

依赖链中解析到：

- `com.google.mlkit:text-recognition-chinese:16.0.1`
- `com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1`
- `com.google.android.gms:play-services-mlkit-text-recognition-common:19.1.0`
- `com.google.mlkit:text-recognition-bundled-common:17.0.0`

打包资产和 native pipeline 已直接出现在 APK 内，因此首次识别不依赖从网络下载模型。OCR 入口位于 `app/src/main/java/com/burgess/schedule/importer/OcrScheduleParser.kt`。

## 包体分类

| 分类 | APK 压缩载荷 | 未压缩载荷 | 说明 |
|---|---:|---:|---|
| OCR 模型与 pipeline | 42,957,802 B / 42.96 MB | 43,523,970 B / 43.52 MB | 25 个模型/配置资产 + 4 个 ABI pipeline |
| 其他 `.so` | 8,327,836 B / 8.33 MB | 8,327,836 B / 8.33 MB | Conscrypt、SPAKE2、AndroidX graphics |
| 图片及 XML 资源 | 2,379,078 B / 2.38 MB | 2,427,644 B / 2.43 MB | `res/` 项；其中 `res/65.png` 映射到 `drawable/icon_adaptive_art`，仍被应用图标引用 |
| 字体 | 598,713 B / 0.60 MB | 1,248,684 B / 1.25 MB | Inter 字体资源 |
| 非 OCR assets | 14,629 B / 0.01 MB | 24,741 B / 0.02 MB | 5 项 |
| 其他 | 3,468,390 B / 3.47 MB | 5,641,102 B / 5.64 MB | DEX、资源表、元数据及其他条目 |

APK 文件本身为 **57,921,397 bytes（57.92 MB / 55.22 MiB）**。分类按 ZIP entry 归类，载荷总和与整包字节数的差额来自 ZIP、签名等容器开销。

## OCR 模型文件

以下均位于 `assets/mlkit-google-ocr-models/`。SHA-256 检查未发现重复文件。

| 子目录 | 文件 |
|---|---|
| `aksara/` | `aksara_page_layout_analysis_rpn_gcn.binarypb`、`aksara_page_layout_analysis_ti_rpn_gcn.binarypb` |
| `gocr/.../Hani_ctc/optical/` | `assets.extra/LabelMap.pb`、`conv_model.fb`、`lstm_model.fb`；同目录父级 `Hani_ctc_cpu.binarypb` |
| `gocr/.../Latn_ctc/optical/` | `assets.extra/LabelMap.pb`、`conv_model.fb`、`lstm_model.fb`；同目录父级 `Latn_ctc_cpu.binarypb` |
| `gocr/.../line_recognition_legacy_mobile/` | `tflite_langid.tflite` |
| `gocr/layout/` | `line_clustering_custom_ops/model.tflite`、`line_splitting_custom_ops/model.tflite` |
| `taser/detector/` | `region_proposal_text_detector_tflite_vertical_mbv2_v1.bincfg`、`rpn_text_detector_mobile_space_to_depth_quantized_mbv2_v1.tflite` |
| `taser/` | `rpn_text_detection_tflite_mobile_mbv2.binarypb` |
| `taser/segmenter/` | `tflite_script_detector_0.3.bincfg`、`tflite_script_detector_0.3.conv_model`、`tflite_script_detector_0.3.lstm_model` |
| 根目录 | `taser_tflite_gocrchinese_and_latin_mbv2_aksara_layout_gcn_mobile_engine.binarypb`、`...engine_ti.binarypb`、`...recognizer.binarypb`、`...runner.binarypb`、`...runner_ti.binarypb` |

模型资产共 25 项，压缩后 **1,924,142 B（1.92 MB / 1.84 MiB）**，未压缩 **2,490,310 B（2.49 MB / 2.37 MiB）**。另外四个 `libmlkit_google_ocr_pipeline.so` 合计 **41,033,660 B（41.03 MB / 39.15 MiB）**。中文与拉丁模型不是重复项；应用的课表内容含英文课程名、数字和教室代码，不能在没有识别率测试的情况下认定 Latn 可删。

按 APK 中语言专属资产计算，移除 Latn 文件最多只涉及 **214,218 B（0.21 MB）** 压缩载荷；移除 Hani 文件最多只涉及 **651,783 B（0.65 MB）**。这两个上限不含可能的共享运行时模型变化；单独删除它们可能让拉丁课程名/教室代码或中文课程名识别退化，不能据此推断整个 pipeline 会变小。

## ABI native libraries

每个 ABI 当前都包含四个 `.so`：`libandroidx.graphics.path.so`、`libconscrypt_jni.so`、`libmlkit_google_ocr_pipeline.so`、`libspake2.so`。

| ABI | `.so` 个数 | 压缩载荷 |
|---|---:|---:|
| `arm64-v8a` | 4 | 13,206,424 B / 13.21 MB |
| `armeabi-v7a` | 4 | 8,102,108 B / 8.10 MB |
| `x86` | 4 | 13,879,516 B / 13.88 MB |
| `x86_64` | 4 | 14,173,448 B / 14.17 MB |

全部 `.so` 合计 49,361,496 B。为单一设备裁掉其他 ABI 不会改变该设备上的 OCR 精度，但会让对应 ABI 设备无法安装/运行该 APK；当前设备兼容范围未授权收窄。

## APK 压缩载荷 Top 30

按 APK ZIP entry 的压缩字节数降序。`res/65.png` 已确认是 adaptive icon art；它不是无引用资源。

| # | 压缩 B | 未压缩 B | APK entry |
|---:|---:|---:|---|
| 1 | 11,626,128 | 11,626,128 | `lib/x86_64/libmlkit_google_ocr_pipeline.so` |
| 2 | 11,561,048 | 11,561,048 | `lib/x86/libmlkit_google_ocr_pipeline.so` |
| 3 | 11,064,544 | 11,064,544 | `lib/arm64-v8a/libmlkit_google_ocr_pipeline.so` |
| 4 | 6,781,940 | 6,781,940 | `lib/armeabi-v7a/libmlkit_google_ocr_pipeline.so` |
| 5 | 2,501,432 | 2,501,432 | `lib/x86_64/libconscrypt_jni.so` |
| 6 | 2,267,028 | 2,267,028 | `lib/x86/libconscrypt_jni.so` |
| 7 | 2,103,592 | 2,103,592 | `lib/arm64-v8a/libconscrypt_jni.so` |
| 8 | 2,001,797 | 2,001,797 | `res/65.png` |
| 9 | 1,868,314 | 3,865,420 | `classes.dex` |
| 10 | 1,284,028 | 1,284,028 | `lib/armeabi-v7a/libconscrypt_jni.so` |
| 11 | 749,257 | 752,652 | `org/bouncycastle/pqc/crypto/picnic/lowmcL5.bin.properties` |
| 12 | 599,499 | 891,872 | `assets/mlkit-google-ocr-models/.../Hani_ctc/optical/lstm_model.fb` |
| 13 | 348,178 | 350,101 | `org/bouncycastle/pqc/crypto/picnic/lowmcL3.bin.properties` |
| 14 | 340,760 | 340,760 | `assets/mlkit-google-ocr-models/taser/detector/rpn_text_detector_mobile_space_to_depth_quantized_mbv2_v1.tflite` |
| 15 | 333,380 | 333,380 | `resources.arsc` |
| 16 | 315,520 | 315,520 | `assets/mlkit-google-ocr-models/.../tflite_langid.tflite` |
| 17 | 201,310 | 419,744 | `res/XS.ttf` |
| 18 | 200,452 | 417,300 | `res/OZ.ttf` |
| 19 | 196,951 | 411,640 | `res/dW.ttf` |
| 20 | 181,609 | 309,568 | `assets/mlkit-google-ocr-models/.../Latn_ctc/optical/lstm_model.fb` |
| 21 | 129,612 | 129,612 | `assets/mlkit-google-ocr-models/gocr/layout/line_clustering_custom_ops/model.tflite` |
| 22 | 119,566 | 161,448 | `assets/mlkit-google-ocr-models/taser/segmenter/tflite_script_detector_0.3.lstm_model` |
| 23 | 117,380 | 118,306 | `org/bouncycastle/pqc/crypto/picnic/lowmcL1.bin.properties` |
| 24 | 114,384 | 114,384 | `assets/mlkit-google-ocr-models/gocr/layout/line_splitting_custom_ops/model.tflite` |
| 25 | 102,168 | 102,168 | `res/XM.jpg` |
| 26 | 42,156 | 42,156 | `lib/x86/libspake2.so` |
| 27 | 39,712 | 39,712 | `res/-6.webp` |
| 28 | 39,712 | 39,712 | `res/sK.webp` |
| 29 | 35,128 | 35,128 | `lib/x86_64/libspake2.so` |
| 30 | 30,829 | 40,848 | `assets/mlkit-google-ocr-models/.../Latn_ctc/optical/conv_model.fb` |

Top 30 中的 `libmlkit_google_ocr_pipeline.so` 属于 OCR native pipeline；Conscrypt/SPAKE2 和 Picnic 参数项是非 OCR 依赖载荷。本轮没有触碰这些依赖，避免影响其他功能。

## 优化评估

| 候选 | 对 OCR 精度的影响 | 兼容性/运行风险 | 本轮结论 |
|---|---|---|---|
| Android App Bundle 或按 ABI 输出 split APK | 无 | 会改变交付产物；手动分发需要选对 ABI | 可另行评估。单一 ABI 包只保留其 ABI native libraries，可减少通用 APK 中的其他架构副本 |
| 移除确定未引用的资源 | 无 | 需要源文件/资源表/代码引用三方核实 | 本轮未确认可安全删除项；`res/65.png` 正在被图标引用 |
| 合并完全相同的普通资源 | 无 | 编译产物相同不代表资源 ID 可删除；要检查 AAPT 引用并改资源别名 | APK 中有 13 组相同 `res/` 内容，重复 entry 理论上约 91,100 B；未做资源 ID 级安全证明，因此不改 |
| 移除当前不支持的 ABI | 无 | 目标 ABI 设备会失去安装/运行能力 | 需先确定正式支持设备范围 |
| 移除 Latn / Hani 语言专属文件 | 可能损失英文/数字或中文识别 | 按现有资产计，压缩载荷上限分别约 214 KB / 652 KB；共享 pipeline 仍占约 41.03 MB | REVIEW；先用固定课表样本跑字段准确率对比，本轮不执行 |
| 量化或替换 slim 模型 | 可能降低低清、混排、细小教室编号识别 | 仓库/APK 没有候选替代模型，预计节省量目前无法可靠估算 | REVIEW；拿到候选模型后先测 APK 差值与识别准确率，本轮不执行 |
| 首次使用时下载模型 | 模型权重本身可不变 | 当前没有对照 unbundled APK；节省量未知，且首次离线识别会失败并增加网络/下载错误路径 | REVIEW；需要网络与离线首用测试，本轮不执行 |

### 后续精度变更的验证基准

任何模型、字典或首次下载调整，都应先用固定且脱敏的中文课表截图集对比课程名、星期、节次、地点和教室代码的字段准确率，并覆盖中英混排、数字、换行及低分辨率样例；必须达到总控批准的准确率门槛后再决定。当前没有在本审计中运行新的 OCR 准确率基准，因为模型未改动。
