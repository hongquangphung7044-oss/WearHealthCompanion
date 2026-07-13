# WearHealthCompanion — ECG 心电图

独立运行在 Wear OS 手表上的 ECG 心电图测量应用（不需要手机），通过 Samsung Health Sensor SDK 采集原始 ECG 波形，发送到 HeartVoice AI API 进行分析。

## 功能

- **ECG 测量**：Samsung SDK 采集 500Hz 原始 ECG 波形（30 秒）
- **AI 分析**：HeartVoice API 单导联 ECG 分析
  - 窦性心律 / 心动过速 / 心动过缓
  - 心房颤动 (AF) / 心房扑动
  - 室性早搏 / 房性早搏计数
  - QRS / PR / QT 间期测量
  - 信号质量评分
- **UI**：Jetpack Compose + Wear Material 3

## 目标设备

- Samsung Galaxy Watch 4 / 5 / 6 / 7 / Ultra（Wear OS 3+）
- ECG 需要 Samsung BioActive Sensor

## 测量方法

1. 手表戴紧手腕（背面电极接触皮肤）
2. 用另一只手食指**轻触**手表上方 Home 按键（不要按下去）
3. 保持 30 秒不动

## 配置清单

所有配置信息已写入项目，方便查找和维护。

### 1. HeartVoice API Key（已内置）

| 项目 | 值 |
|---|---|
| 平台 | https://www.heartvoice.com.cn/aiCloud/ |
| API Key | `aiecg_sk_ONGAJEzHVxKoZzOMRZVQ5yztNVMBH5Pi` |
| 注入方式 | `BuildConfig.HEARTVOICE_API_KEY`（编译时注入） |
| 配置位置 | [app/build.gradle.kts](app/build.gradle.kts#L21-L25) |
| CI Secret | `HEARTVOICE_API_KEY`（覆盖默认值） |

API Key 已作为默认值写入 `build.gradle.kts`，本地构建直接可用。CI 构建时通过环境变量覆盖。

### 2. Samsung Health Sensor SDK（CI Secret）

| 项目 | 值 |
|---|---|
| 下载地址 | https://developer.samsung.com/health/sensor/overview.html |
| 文件名 | `samsung-health-sensor-api.aar`（约 60KB） |
| 放置位置 | `app/libs/samsung-health-sensor-api.aar` |
| CI Secret | `SAMSUNG_SDK_B64_1` + `SAMSUNG_SDK_B64_2`（拆分，因单 Secret 限 48KB） |
| 解码逻辑 | [.github/workflows/build-apk.yml](.github/workflows/build-apk.yml#L37-L47) |

SDK 文件不提交到仓库（许可协议禁止再分发），CI 从 Secret 解码后放入 `app/libs/`。

### 3. 签名 Keystore（CI Secret）

| 项目 | 值 |
|---|---|
| Secret 名 | `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` |
| 作用 | Release APK 签名，保证覆盖安装 |
| 本地文件 | `.keystore/release.keystore`（不入仓库） |

### 4. GitHub Secrets 总览

| Secret 名 | 用途 |
|---|---|
| `KEYSTORE_BASE64` | 签名 keystore 文件（base64） |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 签名 key 别名 |
| `KEY_PASSWORD` | 签名 key 密码 |
| `SAMSUNG_SDK_B64_1` | Samsung SDK base64 前半段 |
| `SAMSUNG_SDK_B64_2` | Samsung SDK base64 后半段 |
| `HEARTVOICE_API_KEY` | HeartVoice API Key（覆盖代码内置值） |

## 构建

### CI 自动构建（推荐）

推送代码到 `main` 分支即可触发 GitHub Actions：
- 版本号：`versionCode = github.run_number`，`versionName = 1.0.{run_number}`
- 每次构建递增，支持覆盖安装
- APK 命名：`WearHealthCompanion-v{版本}-code{编号}-ecg.apk`
- Release 自动发布到 GitHub Releases

### 本地构建

```bash
# 需要将 samsung-health-sensor-api.aar 放入 app/libs/
./gradlew assembleRelease
```

## 技术栈

| 组件 | 版本 |
|---|---|
| Compose BOM | 2025.04.01 |
| Wear Compose Material 3 | 1.6.2 |
| Kotlin | 2.0.0 |
| AGP | 8.5.0 |
| compileSdk | 35 |
| minSdk | 33 (Wear OS 3+) |

## 代码结构

```
app/src/main/java/com/wearhealth/companion/
├── MainActivity.kt                  # 入口，Wear Material 3 主题
├── model/
│   └── EcgAnalysisResult.kt         # ECG 结果模型 + 采集状态 + 诊断标签
├── sensor/
│   └── EcgCollector.kt              # Samsung SDK ECG 采集
├── network/
│   └── HeartVoiceApiClient.kt       # HeartVoice API 调用
└── ui/
    ├── HealthViewModel.kt           # ECG 测量流程管理
    └── HealthMonitorScreen.kt       # Wear Material 3 UI
```

## 免责声明

本应用仅供健康参考，**不能用于医疗诊断**。如有不适请就医。
