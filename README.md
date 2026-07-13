# WearHealthCompanion — Wear OS ECG 心电图应用 + 手机同步器

多模块项目：**手表端**独立采集 ECG 并调用 AI 分析，**手机端**通过 Wearable Data Layer 同步数据、查看详情、配置 API Key、导出 PDF 报告。

> 本文档面向维护者与 AI 助手，记录了架构、关键算法、历史踩坑点，方便快速审查 bug。
> **修改代码前请先阅读「关键设计决策与历史踩坑」章节**，避免回退已修复的问题。

---

## 模块概览

| 模块 | 说明 | applicationId | minSdk |
|---|---|---|---|
| `:app` | Wear OS 手表端 ECG 应用 | `com.wearhealth.companion` | 33 (Wear OS 3+) |
| `:mobile` | Android 手机端同步器 | `com.wearhealth.companion.mobile` | 26 (Android 8.0+) |
| `:shared` | 共享数据模型 + Data Layer 协议 | (library) | 26 |

---

## 功能

### 手表端（:app）
- **ECG 测量**：Samsung SDK 采集 500Hz 原始 ECG 波形（30 秒有效采集）
- **预热激活**：电极接触后先做 5 秒信号稳定期（丢弃数据），避免建立期噪声污染
- **AI 分析**：HeartVoice API 单导联 ECG 分析
  - 窦性心律 / 心动过速 / 心动过缓
  - 心房颤动 (AF) / 心房扑动
  - 室性早搏 / 房性早搏计数
  - QRS / PR / QT / QTc 间期
  - 信号质量评分（sqGrade, 0~1）
- **本地心率范围**：R-R 间期算法计算测量期间的最低/最高心率
- **交互式波形图**：拖动平移 + 双指缩放（1x~10x），专业 ECG 网格背景
- **历史记录**：本地存储最近 50 条，点击查看详情、长按删除
- **测量常亮**：测量期间屏幕保持常亮，防止熄屏退出
- **API Key 配置**：支持手机端推送配置 + 手表键盘输入
- **同步到手机**：测量详情中"传送到手机"按钮，已传送标记"已传送 ✓"
- **自动同步**：手机连接时自动推送未传送数据

### 手机端（:mobile）
- **数据接收**：通过 Wearable Data Layer 自动接收手表测量数据
- **本地存储**：Room 数据库存储所有接收到的测量记录（断联后仍可查看）
- **详细查看**：大屏显示完整 ECG 波形（可交互缩放）+ 所有参数
- **API Key 配置**：输入 API Key → 推送到手表保存
- **请求同步**：主动请求手表发送未传送数据
- **PDF 导出**：完整心电图报告（含波形图、诊断、参数表格、免责声明）
- **Material Design 3**：动态取色 + Material 3 组件

---

## 目标设备

- **手表**：Samsung Galaxy Watch 4 / 5 / 6 / 7 / Ultra（Wear OS 3+），需 Samsung BioActive Sensor
- **手机**：Android 8.0+（API 26+），需安装 Google Play 服务

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
完成(显示结果 + 保存历史, 含完整原始波形)
```

> **为什么需要预热激活**：电极刚接触时信号有建立过程，前几秒数据不稳定。丢弃这 5 秒数据能显著提升缩略图质量和分析准确率。

---

## Wearable Data Layer 通信协议

手表与手机之间使用 **Google Wearable Data Layer API** 通信（不直接操作蓝牙）。

### 通信路径

| 方向 | 路径 | 方式 | 内容 |
|---|---|---|---|
| 手表 → 手机 | `/ecg_measurement/{timestamp}` | DataItem (DataMap) | 完整测量数据 + 原始波形 |
| 手机 → 手表 | `/api_key` | DataItem (DataMap) | API Key 字符串 |
| 手机 → 手表 | `/sync_request` | MessageClient | 请求同步未传送数据 |

### 数据传输格式

ECG 测量数据通过 `DataMap` 传输：
- 标量字段（timestamp, diagnosis, heartRate 等）直接放入 DataMap
- **原始波形**用 `EcgBinaryCodec` 编码为 `ByteArray`（二进制 int 数组，15000 点 ≈ 60KB）
- 二进制格式比 JSON 文本小约 40%

### 数据流

```
手表采集完成 → 保存到本地历史(rawEcgData)
     ↓ 用户点击"传送到手机" / 手机请求同步
     ↓ PutDataMapRequest + MeasurementSerializer.toDataMap()
     ↓ Wearable.getDataClient().putDataItem()
     ↓ (自动通过蓝牙传输)
手机 PhoneWearableListenerService.onDataChanged()
     ↓ MeasurementSerializer.fromDataMap()
     ↓ 存入 Room 数据库
     ↓ Flow 自动推送 UI 更新
```

---

## 配置清单

### 1. HeartVoice API Key

| 项目 | 值 |
|---|---|
| 平台 | https://www.heartvoice.com.cn/aiCloud/ |
| API Key | `aiecg_sk_ONGAJEzHVxKoZzOMRZVQ5yztNVMBH5Pi` |
| **存储方式** | **SharedPreferences**（ApiKeyManager），不再硬编码到 BuildConfig |
| Fallback | `BuildConfig.HEARTVOICE_API_KEY`（编译时注入，作为默认值） |
| 配置方式 | ① 手机端配置推送 ② 手表键盘输入 ③ 编译时 BuildConfig fallback |
| CI Secret | `HEARTVOICE_API_KEY`（覆盖 build.gradle.kts 默认值） |
| **额度** | **免费 100 次，一次性，不刷新**（务必节省） |

> API Key 优先级：SharedPreferences（用户配置）> BuildConfig（编译时默认值）
> 手机端配置后通过 Data Layer 推送到手表，存入 SharedPreferences。

### 2. Samsung Health Sensor SDK（仅手表端）

| 项目 | 值 |
|---|---|
| 下载地址 | https://developer.samsung.com/health/sensor/overview.html |
| 文件名 | `samsung-health-sensor-api.aar`（约 60KB） |
| 放置位置 | `app/libs/samsung-health-sensor-api.aar` |
| CI Secret | `SAMSUNG_SDK_B64_1` + `SAMSUNG_SDK_B64_2`（拆分，因单 Secret 限 48KB） |

SDK 文件不提交到仓库（许可协议禁止再分发），CI 从 Secret 解码后放入 `app/libs/`。

### 3. 签名 Keystore（CI Secret）

| 项目 | 值 |
|---|---|
| Secret 名 | `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` |
| 作用 | Release APK 签名，手表 + 手机共用同一 keystore |
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

推送代码到 `main` 分支即可触发 GitHub Actions，同时构建手表 + 手机 APK：
- 版本号：`versionCode = github.run_number`，`versionName = 1.0.{run_number}`
- 手表 APK：`WearHealthCompanion-watch-v{版本}-code{编号}-ecg.apk`
- 手机 APK：`WearHealthCompanion-mobile-v{版本}-code{编号}.apk`
- 两个 APK 同时发布到 GitHub Releases

### 本地构建

```bash
# 需要将 samsung-health-sensor-api.aar 放入 app/libs/
./gradlew :app:assembleRelease :mobile:assembleRelease
```

> 注意：手表端无 Samsung SDK aar 时无法完整编译（ECG 采集类依赖 SDK 类）。手机端不依赖 Samsung SDK。

---

## 技术栈

| 组件 | 版本 | 使用模块 |
|---|---|---|
| Compose BOM | 2025.04.01 | app + mobile |
| Wear Compose Material 3 | 1.6.2 | app |
| Material 3 | (BOM 管理) | mobile |
| Kotlin | 2.0.0 | 全部 |
| AGP | 8.6.0 | 全部 |
| KSP | 2.0.0-1.0.21 | mobile (Room) |
| Room | 2.6.1 | mobile |
| Play Services Wearable | 18.2.0 | app + mobile + shared |
| compileSdk | 35 | 全部 |
| Navigation Compose | 2.7.7 | mobile |

---

## 代码结构与文件职责

### 项目结构

```
/workspace/
├── settings.gradle.kts                # include(":app", ":shared", ":mobile")
├── build.gradle.kts                   # 根构建文件
├── gradle/libs.versions.toml          # 版本目录
├── .github/workflows/build-apk.yml    # CI/CD（构建手表+手机 APK）
│
├── shared/                            # 共享模块
│   └── src/main/java/com/wearhealth/companion/shared/
│       ├── DataLayerPaths.kt          # Data Layer 路径 + 键名常量
│       ├── EcgMeasurementTransfer.kt  # 传输数据模型 + EcgBinaryCodec
│       └── MeasurementSerializer.kt   # DataMap + JSON 序列化
│
├── app/                               # 手表端模块
│   └── src/main/java/com/wearhealth/companion/
│       ├── MainActivity.kt            # 入口 + 屏幕常亮
│       ├── model/EcgAnalysisResult.kt # 数据模型 + 状态机 + 算法
│       ├── sensor/EcgCollector.kt     # Samsung SDK 采集器
│       ├── network/HeartVoiceApiClient.kt  # API 调用
│       ├── data/
│       │   ├── ApiKeyManager.kt       # API Key SharedPreferences 存储
│       │   └── EcgHistoryRepository.kt# 历史记录（含 rawEcgData + syncedToPhone）
│       ├── service/
│       │   └── WatchWearableListenerService.kt  # 接收 API Key + 响应同步请求
│       └── ui/
│           ├── HealthViewModel.kt     # 测量流程 + 同步逻辑
│           ├── HealthMonitorScreen.kt # Wear Material 3 主 UI
│           └── InteractiveEcgChart.kt # 交互式 ECG 波形
│
└── mobile/                            # 手机端模块
    └── src/main/java/com/wearhealth/companion/mobile/
        ├── MainActivity.kt            # 入口 + Navigation Compose
        ├── data/
        │   ├── EcgMeasurementEntity.kt# Room Entity
        │   ├── EcgMeasurementDao.kt   # Room DAO
        │   ├── AppDatabase.kt         # Room Database
        │   └── MeasurementRepository.kt # Repository
        ├── service/
        │   ├── PhoneWearableListenerService.kt # 接收手表数据
        │   └── DataLayerManager.kt    # 发送 API Key + 请求同步
        ├── ui/
        │   ├── MobileViewModel.kt     # ViewModel
        │   ├── HistoryListScreen.kt   # 历史列表页
        │   ├── MeasurementDetailScreen.kt # 详情页（含波形 + PDF 导出）
        │   ├── SettingsScreen.kt      # API Key 配置页
        │   ├── MobileEcgChart.kt      # 手机端交互式 ECG 波形
        │   └── DiagnosisLabels.kt     # 诊断标签中文化
        └── pdf/
            └── PdfExporter.kt         # PDF 报告生成
```

### :shared 模块文件职责

#### [DataLayerPaths.kt](shared/src/main/java/com/wearhealth/companion/shared/DataLayerPaths.kt)
- 所有 Data Layer 路径常量（`PATH_ECG_MEASUREMENT`, `PATH_API_KEY`, `PATH_SYNC_REQUEST`）
- DataMap 键名常量（`KEY_TIMESTAMP`, `KEY_RAW_ECG` 等）

#### [EcgMeasurementTransfer.kt](shared/src/main/java/com/wearhealth/companion/shared/EcgMeasurementTransfer.kt)
- `EcgMeasurementTransfer` data class：手表→手机传输的完整数据模型
- `EcgBinaryCodec`：`List<Int>` ↔ `ByteArray` 转换（波形二进制编码，节省 40% 空间）

#### [MeasurementSerializer.kt](shared/src/main/java/com/wearhealth/companion/shared/MeasurementSerializer.kt)
- `toDataMap()` / `fromDataMap()`：DataMap 序列化（用于 Wearable Data Layer 传输）
- `toJson()` / `fromJson()`：JSON 序列化（用于 Room 存储）

### 手表端（:app）新增/修改文件

#### [ApiKeyManager.kt](app/src/main/java/com/wearhealth/companion/data/ApiKeyManager.kt)
- SharedPreferences 存储 API Key（不再硬编码到 BuildConfig）
- `getApiKey()`：优先读 SharedPreferences，fallback 到 BuildConfig
- `saveApiKey(key)`：保存到 SharedPreferences
- `refreshTrigger`：StateFlow，用于通知 UI 刷新（Service 接收到新 Key 时触发）

#### [EcgHistoryRepository.kt](app/src/main/java/com/wearhealth/companion/data/EcgHistoryRepository.kt)
- `HistoryItem` 新增 `rawEcgData: List<Int>`（完整原始波形，用于传送到手机）
- `HistoryItem` 新增 `syncedToPhone: Boolean`（同步状态标记）
- `getUnsynced()`：获取未同步到手机的记录
- `markSynced(timestamp)`：标记为已同步

#### [WatchWearableListenerService.kt](app/src/main/java/com/wearhealth/companion/service/WatchWearableListenerService.kt)
- `onDataChanged()`：接收手机推送的 API Key，存入 ApiKeyManager
- `onMessageReceived()`：响应手机的 `/sync_request`，自动推送所有未传送数据
- 独立 CoroutineScope，App 不在前台也能完成同步

#### [HealthViewModel.kt](app/src/main/java/com/wearhealth/companion/ui/HealthViewModel.kt)
- 使用 ApiKeyManager 获取 API Key（支持动态更新）
- `syncToPhone(item)`：通过 PutDataMapRequest 发送测量数据到手机
- `syncAllUnsynced()`：批量同步所有未传送记录
- `saveApiKey(key)`：保存 API Key 并重建 API 客户端

#### [HealthMonitorScreen.kt](app/src/main/java/com/wearhealth/companion/ui/HealthMonitorScreen.kt)
- 历史详情：已传送显示"已传送 ✓"（灰色），未传送显示"传送到手机"按钮
- 历史列表：每条记录显示同步状态
- API Key 输入界面（当未配置时显示）

### 手机端（:mobile）文件职责

#### [PhoneWearableListenerService.kt](mobile/src/main/java/com/wearhealth/companion/mobile/service/PhoneWearableListenerService.kt)
- `onDataChanged()`：接收手表的 `/ecg_measurement` 数据
- 用 `MeasurementSerializer.fromDataMap()` 反序列化
- 存入 Room 数据库，发送广播通知 UI

#### [DataLayerManager.kt](mobile/src/main/java/com/wearhealth/companion/mobile/service/DataLayerManager.kt)
- `sendApiKeyToWatch(apiKey)`：PutDataMapRequest 发送 API Key
- `requestSyncFromWatch()`：MessageClient 发送同步请求
- `getConnectedNodes()`：查询已连接的手表

#### [EcgMeasurementEntity.kt](mobile/src/main/java/com/wearhealth/companion/mobile/data/EcgMeasurementEntity.kt)
- Room Entity，主键 = timestamp
- `rawEcgBytes` / `downsampledEcgBytes`：二进制波形存储
- `rawDataJson`：MeasurementSerializer.toJson() 的元数据

#### [PdfExporter.kt](mobile/src/main/java/com/wearhealth/companion/mobile/pdf/PdfExporter.kt)
- 使用 Android `PdfDocument` API（不依赖第三方库）
- A4 页面：标题 + 时间 + 诊断表格 + 参数表格 + ECG 波形图（Canvas 绘制）+ 免责声明
- 波形带 ECG 网格背景，去基线 + 自适应缩放

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
- 原始波形 15000 点通过 `EcgBinaryCodec` 编码为 60KB ByteArray 传输

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

### 10. 使用 Wearable Data Layer API 而非直接蓝牙
- **现象**：直接操作 BluetoothSocket/RFCOMM 在 Wear OS 上复杂且不稳定
- **原因**：参考 Authenticator Pro 等 Wear OS 开源项目，它们使用 Data Layer API
- **决策**：使用 `DataClient`（putDataItem）+ `MessageClient`（sendMessage）+ `WearableListenerService`
- **优势**：底层自动处理蓝牙连接，蓝牙不可用时通过 Google Cloud 路由（端到端加密）

### 11. 波形二进制编码传输
- **现象**：15000 个整数用 JSON 文本约 83KB，接近 DataItem 限制
- **决策**：用 `EcgBinaryCodec` 将 `List<Int>` 编码为 `ByteArray`（4 字节/int，60KB），节省 40% 空间
- **代码位置**：`EcgBinaryCodec.encode()` / `decode()`

### 12. API Key 不再硬编码
- **现象**：API Key 内嵌在 APK 中不安全
- **决策**：API Key 存入 SharedPreferences（ApiKeyManager），手机端可配置推送，手表也可键盘输入
- **Fallback**：BuildConfig.HEARTVOICE_API_KEY 作为默认值，确保首次安装可用

---

## 免责声明

本应用仅供健康参考，**不能用于医疗诊断**。如有不适请就医。
