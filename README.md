# WearHealthCompanion — Wear OS 独立 ECG 心电图应用

独立运行在 Wear OS 手表上的 ECG 心电图测量应用（**不需要手机**），通过 Samsung Health Sensor SDK 采集原始 ECG 波形，发送到 HeartVoice AI API 进行云端分析。

> 本文档面向维护者与 AI 助手，记录了架构、关键算法、历史踩坑点，方便快速审查 bug。
> **修改代码前请先阅读「关键设计决策与历史踩坑」章节**，避免回退已修复的问题。

---

## 功能

- **ECG 测量**：Samsung SDK 采集 500Hz 原始 ECG 波形（30 秒有效采集）
- **预热激活**：电极接触后先做 5 秒信号稳定期（丢弃数据），避免建立期噪声污染
- **AI 分析**：HeartVoice API 单导联 ECG 分析
  - 窦性心律 / 心动过速 / 心动过缓
  - 心房颤动 (AF) / 心房扑动
  - 室性早搏 / 房性早搏计数
  - QRS / PR / QT / QTc 间期
  - 信号质量评分（sqGrade, 0~1）
- **本地心率范围**：R-R 间期算法计算测量期间的最低/最高心率（API 只返回平均心率）
- **交互式波形图**：拖动平移 + 双指缩放（1x~10x），专业 ECG 网格背景
- **历史记录**：本地存储最近 50 条，点击查看详情、长按删除
- **测量常亮**：测量期间屏幕保持常亮，防止熄屏退出

---

## 目标设备

- Samsung Galaxy Watch 4 / 5 / 6 / 7 / Ultra（Wear OS 3+）
- ECG 需要 Samsung BioActive Sensor

---

## 测量方法

1. 手表戴紧手腕（背面电极接触皮肤）
2. 用另一只手食指**轻触**手表上方 Home 按键（**不要按下去**）
3. 等待「连接中」→「预热激活 5s」→「采集中 30s」完成
4. 全程保持手指不动

### 采集阶段时序（重要）

```
连接中(等待接触, 最多30s)
   ↓ 检测到 leadOff != 5
预热激活(5s 倒计时, 数据丢弃)
   ↓ 清空 ecgData
采集中(30s 倒计时, 正式记录)
   ↓
本地预检(数据量≥10s + RMS≥10)
   ↓ 通过
AI 分析中(调用 HeartVoice API)
   ↓
完成(显示结果 + 保存历史)
```

> **为什么需要预热激活**：电极刚接触时信号有建立过程，前几秒数据不稳定。丢弃这 5 秒数据能显著提升缩略图质量和分析准确率（类似 geminiman 项目的激活预热设计）。

---

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
| **额度** | **免费 100 次，一次性，不刷新**（务必节省） |

> API Key 已作为默认值写入 `build.gradle.kts`，本地构建直接可用。CI 构建时通过环境变量覆盖。
> **免费额度总共 100 次且不刷新**，因此本地预检（`localSignalQualityCheck`）必须严格过滤无效数据。

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

---

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

> 注意：本地无 Samsung SDK aar 时无法完整编译（ECG 采集类依赖 SDK 类）。

---

## 技术栈

| 组件 | 版本 |
|---|---|
| Compose BOM | 2025.04.01 |
| Wear Compose Material 3 | 1.6.2 |
| Kotlin | 2.0.0 |
| AGP | 8.6.0 |
| compileSdk | 35 |
| minSdk | 33 (Wear OS 3+) |

---

## 代码结构与文件职责

```
app/src/main/java/com/wearhealth/companion/
├── MainActivity.kt                    # 入口 + 测量期间屏幕常亮控制
├── model/
│   └── EcgAnalysisResult.kt           # 数据模型 + 状态机 + 算法（核心）
├── sensor/
│   └── EcgCollector.kt                # Samsung SDK 采集器（核心）
├── network/
│   └── HeartVoiceApiClient.kt         # HeartVoice API 调用
├── data/
│   └── EcgHistoryRepository.kt        # 历史记录本地存储
└── ui/
    ├── HealthViewModel.kt             # 测量流程编排
    ├── HealthMonitorScreen.kt         # Wear Material 3 主 UI
    └── InteractiveEcgChart.kt         # 可交互 ECG 波形组件
```

### 各文件关键职责

#### [EcgAnalysisResult.kt](app/src/main/java/com/wearhealth/companion/model/EcgAnalysisResult.kt)
- `EcgAnalysisResult` data class：API 响应 + 本地补充字段（min/maxHeartRate、ecgSamples）
- `EcgCollectionState` sealed class：**采集状态机**（见下文）
- `diagnosisLabelToText()` / `isDiagnosisSerious()` / `diagnosisSummary()`：诊断标签中文化
- `hasDiagnosisConflict()`：WPW 诊断与参数矛盾检测
- `computeMinMaxHeartRate()`：本地 R 波检测算法（带通滤波 + 自适应阈值 + 形态验证）
- `localSignalQualityCheck()`：**本地预检**（只检查数据量 + RMS，不做心跳检测）

#### [EcgCollector.kt](app/src/main/java/com/wearhealth/companion/sensor/EcgCollector.kt)
- Samsung Health Sensor SDK 封装
- 采集流程：连接 → 等待接触 → **5 秒预热激活** → 30 秒采集
- `leadOff` 接触检测：`!= 5` 即视为接触（避免中间值误判）
- `onError` 回调：**只记日志不中断**（部分 TrackerError 可恢复）
- `fullyReleaseService()`：彻底释放 SDK 资源（解决第二次连不上）
- 采集循环**不检测接触断开**（leadOff 波动会误判，见历史踩坑）

#### [HeartVoiceApiClient.kt](app/src/main/java/com/wearhealth/companion/network/HeartVoiceApiClient.kt)
- POST `/api/v1/basic/ecg/1-lead/analyze`
- `adcGain=1000.0`（因为 EcgCollector 把 mV 放大 1000 倍存为整数）
- `adcZero=0.0`
- 解析 `sqGrade` / `diagnosis` / `avgHr` / `avgQrs` 等字段

#### [EcgHistoryRepository.kt](app/src/main/java/com/wearhealth/companion/data/EcgHistoryRepository.kt)
- SharedPreferences + JSON 数组存储
- 最多保留 50 条
- `HistoryItem` data class：含降采样波形（约 300-400 点）

#### [HealthViewModel.kt](app/src/main/java/com/wearhealth/companion/ui/HealthViewModel.kt)
- `startEcgMeasurement()`：编排完整流程
  1. 采集 → 2. 本地预检 → 3. API 分析 → 4. WPW 过滤 → 5. 计算 min/max 心率 → 6. 保存历史
- `filterWpwIfConflict()`：WPW 误判过滤（PR 正常 + QRS 正常 → 移除 WPW）
- `downsample()`：降采样到 400 点用于结果显示

#### [HealthMonitorScreen.kt](app/src/main/java/com/wearhealth/companion/ui/HealthMonitorScreen.kt)
- Wear Material 3 ScalingLazyColumn
- 状态机各分支的 UI 渲染（Connecting / Preheating / Collecting / Analyzing / Done / Error）
- 历史列表（长按删除）+ 历史详情页
- **注意**：底部按钮 `when(state)` 必须穷尽所有 sealed 分支（曾因漏 Preheating 导致编译失败）

#### [InteractiveEcgChart.kt](app/src/main/java/com/wearhealth/companion/ui/InteractiveEcgChart.kt)
- `detectTransformGestures`：拖动平移 + 双指缩放
- scale 限制 1x~10x
- `interactive=false` 用于历史列表缩略图（静态）
- 专业 ECG 网格背景

#### [MainActivity.kt](app/src/main/java/com/wearhealth/companion/MainActivity.kt)
- `setupKeepScreenOnDuringMeasurement()`：测量期间（Connecting/Preheating/Collecting/Analyzing）保持常亮

---

## 采集状态机（EcgCollectionState）

```
sealed class EcgCollectionState {
    object Idle                                          // 空闲
    object Connecting                                    // 连接传感器/等待电极接触
    data class Preheating(val countdownSec: Int)         // 预热激活（5s 倒计时）
    data class Collecting(val samplesCollected: Int, val countdownSec: Int)  // 正式采集
    object Analyzing                                     // API 分析中
    data class Done(val result: EcgAnalysisResult)       // 完成
    data class Error(val message: String)                // 错误
}
```

**修改 sealed class 时务必全局搜索 `when(state)` / `is EcgCollectionState.`**，补全所有分支，否则 Kotlin 编译失败（历史教训）。

---

## 关键算法说明

### 1. 本地预检 `localSignalQualityCheck()`

**只做最基本的检查，不做心跳检测**（心跳检测交给 API 的 sqGrade）：
- 数据量 ≥ 10 秒（`ecgData.size >= sampleRateHz * 10`）
- 信号 RMS ≥ 10（`Math.sqrt(variance) >= 10.0`）

> 历史教训：曾用 `computeMinMaxHeartRate()` 做预检心跳检测，但本地 R 波算法在噪声大时会失败返回 (0,0)，导致大量有效测量被误拒（"信号完全没有心跳波动特征"）。现已彻底移除心跳检测。

### 2. 本地 R 波检测 `computeMinMaxHeartRate()`

用于计算 min/max 心率（仅显示用，检测失败时 UI 不显示心率范围，不阻塞测量）：
1. 带通滤波：1 秒窗口去基线漂移 + 5 点移动平均去高频噪声
2. 自适应阈值 = 信号 RMS × 2
3. R 峰形态验证：局部最大 + 斜率检查（R 波是尖窄峰）
4. 不应期 333ms（限制最高 180bpm）
5. R-R 间期 → 瞬时心率，合理范围 40-150 bpm
6. 异常值剔除：偏离中位数 ±30% 丢弃

### 3. WPW 误判过滤 `filterWpwIfConflict()`

HeartVoice 基础版对复杂心律失常准确率较低，单导联短数据易误判 WPW：
- 若 API 返回 WPW 但 PR(120-200ms) 和 QRS(80-120ms) 都正常 → 移除 WPW 诊断
- 典型 WPW 三联征：PR<120ms + QRS>120ms + delta 波
- 若移除后无其他诊断 → 标记为 SN（窦性心律）

### 4. 数据格式

- ECG 数据存储为 `mV × 1000` 的整数（避免浮点精度问题）
- 因此 API 调用时 `adcGain = 1000.0`
- 采样率 500Hz（Samsung SDK 固定）

---

## 关键设计决策与历史踩坑（修改前必读）

> 以下每条都是**曾经踩过的坑**，对应代码改动是有意为之，**不要回退**。

### 1. 采集中不检测接触断开
- **现象**：测量中途提示「接触断开超过 5 秒」
- **原因**：Samsung SDK 的 `leadOff` 值在测量过程中会短暂波动到 5，即使手指稳定
- **决策**：采集循环只跑 30 秒倒计时，**完全不检测 leadOff 断开**。数据质量交给本地预检 + API sqGrade 判断
- **代码位置**：`EcgCollector.startEcgCollection()` 的采集循环

### 2. SDK `onError` 不中断采集
- **现象**：测量中途提示「采集失败」
- **原因**：SDK 的 `onError` 回调设置了 Error 状态，中断了采集
- **决策**：`onError` 只记日志警告，**不设置 Error 状态**。部分 TrackerError 是可恢复的
- **代码位置**：`EcgCollector.setupEcgTracker()` 的 `onError` 实现

### 3. 预热激活阶段（5 秒）
- **现象**：缩略图前半段是直线/噪声
- **原因**：电极刚接触时信号有建立过程
- **决策**：检测到接触后，先做 5 秒预热激活（丢弃数据 + 倒计时），再开始正式 30 秒采集
- **代码位置**：`EcgCollector.startEcgCollection()` 第 5 步

### 4. 首次接触清空预热噪声
- **现象**：缩略图显示直线
- **原因**：预热期（等待接触）的噪声数据混入了 ecgData
- **决策**：listener 检测到首次接触时 `ecgData.clear()`，丢弃预热噪声
- **代码位置**：`EcgCollector.setupEcgTracker()` 的 `onDataReceived`

### 5. `fullyReleaseService()` 彻底释放
- **现象**：测完一次后第二次连不上传感器
- **原因**：SDK 资源未彻底释放
- **决策**：每次新连接前调用 `fullyReleaseService()`（unsetEventListener + disconnectService + 置 null + delay 300ms）
- **代码位置**：`EcgCollector.fullyReleaseService()`

### 6. 预检不做心跳检测
- **现象**：「信号完全没有心跳波动特征」误报
- **原因**：本地 R 波算法在噪声大时返回 (0,0)，触发预检失败
- **决策**：预检只检查数据量 + RMS，心跳/波形质量交给 API
- **代码位置**：`EcgAnalysisResult.localSignalQualityCheck()`

### 7. `leadOff` 接触判定用 `!= 5`
- **现象**：接触检测误判
- **原因**：Samsung SDK 的 leadOff 有中间值（0=接触，5=未接触，但存在 1-4）
- **决策**：只要 `leadOff != 5` 即视为接触，避免中间值误判

### 8. 实时波形 250 点
- **现象**：波形太密或太稀
- **决策**：实时波形显示最近 250 个采样点（500Hz × 0.5 秒），刚好显示一个心跳周期。曾尝试 1000/500/150 点，250 是最佳平衡

### 9. 修改 sealed class 必须补全 when 分支
- **现象**：构建 #25 编译失败
- **原因**：新增 `Preheating` 状态后，`HealthMonitorScreen.kt` 的 `when(state)` 没补分支
- **决策**：修改 `EcgCollectionState` 后，全局搜索 `when(state)` / `is EcgCollectionState.` 补全所有分支

---

## 免责声明

本应用仅供健康参考，**不能用于医疗诊断**。如有不适请就医。
