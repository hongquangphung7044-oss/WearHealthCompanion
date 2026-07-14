# WearHealthCompanion — Wear OS ECG 心电图应用 + 手机同步器

多模块项目：**手表端**独立采集 ECG 并调用 AI 分析，**手机端**通过 Wearable Data Layer 同步数据、查看详情、配置 API Key、导出 PDF 报告。

> 本文档面向维护者与 AI 助手，记录了架构、关键算法、历史踩坑点，方便快速审查 bug。
> **修改代码前请先阅读「关键设计决策与历史踩坑」章节**，避免回退已修复的问题。

---

## 当前构建状态

- **最新构建**：#39（成功）
- **最新 commit**：`55fa53d`
- **CI**：GitHub Actions，推送到 `main` 自动构建手表+手机 APK 并发布 Release
- **分支**：`main`（唯一开发分支）

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
- **同步到手机**：测量详情中"传送到手机"按钮
  - 传送前检测手机节点连接状态，未连接时提示错误
  - 已传送的记录显示"重新传送"按钮（可点击重新传送）
  - 历史列表每条记录显示同步状态（已传送 ✓ / 未传送）
- **自动同步**：手机端请求同步时，手表自动推送所有未传送数据

### 手机端（:mobile）
- **数据接收**：通过 Wearable Data Layer 自动接收手表测量数据
- **本地存储**：Room 数据库存储所有接收到的测量记录（断联后仍可查看）
- **详细查看**：大屏显示完整 ECG 波形（可交互缩放）+ 所有参数
- **API Key 配置**：输入 API Key → 推送到手表保存
- **请求同步**：主动请求手表发送未传送数据
- **PDF 导出**：完整心电图报告（含波形图、诊断、参数表格、免责声明）
- **手表连接状态**：底部状态栏实时显示手表连接/未连接，支持手动刷新
  - `onResume` 时自动刷新连接状态
  - 刷新按钮手动重新检测
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

### 传送流程（含连接检测）

```
手表：用户点击"传送到手机"
  ↓ syncToPhone()
  ↓ 先检测 NodeClient.connectedNodes（是否有手机连接）
  ↓ 无连接 → 提示"未检测到已连接的手机"，不标记为已传送
  ↓ 有连接 → PutDataMapRequest + setUrgent() + putDataItem()
  ↓ 成功 → markSynced() 标记为已传送
  ↓ 失败 → 提示"传送失败: {错误信息}"

手机：onResume → refreshWatchConnection() 刷新连接状态
手机：用户点击刷新按钮 → refreshWatchConnection()
手机：用户点击"请求同步" → MessageClient 发送 /sync_request 到手表
手表：WatchWearableListenerService.onMessageReceived() → syncAllUnsynced()
```

> **重要**：`putDataItem()` 写入本地缓存就返回成功，不代表对方已接收。因此在调用前先检测 `connectedNodes`，确保手机确实在线。但 Data Layer 的 `putDataItem` 本身不保证送达——如果手机在传送后断联，数据可能丢失。用户可通过"重新传送"按钮重试。

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
│       ├── MainActivity.kt            # 入口 + 屏幕常亮 + onResume 刷新 API Key 状态
│       ├── model/EcgAnalysisResult.kt # 数据模型 + 状态机 + 算法（预检、R波检测、WPW过滤）
│       ├── sensor/EcgCollector.kt     # Samsung SDK 采集器（预热激活、接触检测、采集循环）
│       ├── network/HeartVoiceApiClient.kt  # API 调用（构造函数接收 apiKey）
│       ├── data/
│       │   ├── ApiKeyManager.kt       # API Key SharedPreferences 存储 + refreshTrigger
│       │   └── EcgHistoryRepository.kt# 历史记录（含 rawEcgData + syncedToPhone + getUnsynced/markSynced）
│       ├── service/
│       │   └── WatchWearableListenerService.kt  # 接收 API Key + 响应同步请求
│       └── ui/
│           ├── HealthViewModel.kt     # 测量流程 + syncToPhone（含连接检测）+ syncAllUnsynced
│           ├── HealthMonitorScreen.kt # Wear Material 3 主 UI（含 when(state) 全分支覆盖）
│           └── InteractiveEcgChart.kt # 交互式 ECG 波形（拖动+缩放）
│
└── mobile/                            # 手机端模块
    └── src/main/java/com/wearhealth/companion/mobile/
        ├── MainActivity.kt            # 入口 + Navigation Compose + onResume 刷新连接状态
        ├── data/
        │   ├── EcgMeasurementEntity.kt# Room Entity（id自增, rawEcgBytes, downsampledEcgBytes）
        │   ├── EcgMeasurementDao.kt   # Room DAO（insert/getAll Flow/getById/delete）
        │   ├── AppDatabase.kt         # Room Database 单例
        │   └── MeasurementRepository.kt # Repository（Transfer↔Entity 转换）
        ├── service/
        │   ├── PhoneWearableListenerService.kt # 接收手表数据→存入Room→发广播
        │   └── DataLayerManager.kt    # sendApiKeyToWatch / requestSyncFromWatch / getConnectedNodes
        ├── ui/
        │   ├── MobileViewModel.kt     # ViewModel（measurements Flow + 连接刷新 + PDF 导出）
        │   ├── HistoryListScreen.kt   # 历史列表页（卡片+缩略波形+底部连接状态+刷新按钮）
        │   ├── MeasurementDetailScreen.kt # 详情页（完整波形+参数+导出PDF按钮）
        │   ├── SettingsScreen.kt      # API Key 配置页 + 请求同步
        │   ├── MobileEcgChart.kt      # 手机端交互式 ECG 波形（拖动+缩放+ECG网格）
        │   └── DiagnosisLabels.kt     # 诊断标签中文化（12种+isDiagnosisSerious）
        └── pdf/
            └── PdfExporter.kt         # PDF 报告生成（PdfDocument + Canvas 绘制波形）
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

### 手表端（:app）文件职责

#### [ApiKeyManager.kt](app/src/main/java/com/wearhealth/companion/data/ApiKeyManager.kt)
- SharedPreferences 存储 API Key（不再硬编码到 BuildConfig）
- `getApiKey()`：优先读 SharedPreferences，fallback 到 BuildConfig
- `saveApiKey(key)`：保存到 SharedPreferences
- `refreshTrigger`：StateFlow，用于通知 UI 刷新（Service 接收到新 Key 时触发）

#### [EcgHistoryRepository.kt](app/src/main/java/com/wearhealth/companion/data/EcgHistoryRepository.kt)
- `HistoryItem` data class 字段：
  - `ecgSamples: List<Int>` — 降采样波形（用于手表显示，约 300 点）
  - `rawEcgData: List<Int>` — 完整原始波形（用于传送到手机，约 15000 点）
  - `syncedToPhone: Boolean` — 是否已同步到手机
- `getUnsynced()`：获取未同步到手机的记录
- `markSynced(timestamp)`：标记为已同步
- JSON 序列化包含 `sync` (boolean) 和 `raw` (int array) 字段

#### [WatchWearableListenerService.kt](app/src/main/java/com/wearhealth/companion/service/WatchWearableListenerService.kt)
- `onDataChanged()`：接收手机推送的 API Key（路径 `/api_key`），存入 ApiKeyManager
- `onMessageReceived()`：响应手机的 `/sync_request`，调用 `syncAllUnsynced()`
- 独立 CoroutineScope，App 不在前台也能完成同步

#### [HealthViewModel.kt](app/src/main/java/com/wearhealth/companion/ui/HealthViewModel.kt)
- `syncToPhone(item)`：传送前先检测 `NodeClient.connectedNodes`
  - 无连接 → 提示"未检测到已连接的手机"，不标记为已传送
  - 有连接 → `PutDataMapRequest` + `setUrgent()` + `putDataItem()` → `markSynced()`
  - 允许已传送的记录重新传送（不移除 syncedToPhone 拦截）
- `syncAllUnsynced()`：遍历所有未传送记录逐条发送
- `saveApiKey(key)`：保存 API Key 并重建 API 客户端
- `EcgUiState` 包含 `syncingToPhone: Boolean` 和 `syncMessage: String?`

#### [HealthMonitorScreen.kt](app/src/main/java/com/wearhealth/companion/ui/HealthMonitorScreen.kt)
- 历史详情"传送到手机"按钮逻辑：
  - 未传送 → 蓝色"传送到手机"按钮
  - 已传送 → 灰色"重新传送"按钮（**可点击**，非禁用）
  - 传送中 → "传送中..."
- 历史列表项显示同步状态（"已传送 ✓" 绿色 / "未传送" 橙色）
- API Key 输入界面（当未配置时显示）
- `when(state)` 覆盖所有 7 个 `EcgCollectionState` 分支
- `syncMessage` 使用局部变量避免 smart cast 问题

#### [MainActivity.kt](app/src/main/java/com/wearhealth/companion/MainActivity.kt)
- `onResume()` 调用 `viewModel.refreshApiKeyStatus()`（手机端可能在后台期间下发新 Key）
- 测量期间（Connecting/Preheating/Collecting/Analyzing）`FLAG_KEEP_SCREEN_ON`

### 手机端（:mobile）文件职责

#### [MainActivity.kt](mobile/src/main/java/com/wearhealth/companion/mobile/MainActivity.kt)
- `onResume()` 调用 `viewModel.refreshWatchConnection()`（每次回到前台刷新手表连接状态）
- ViewModel 通过 `by lazy` 创建，Navigation Compose 在三个页面间导航

#### [PhoneWearableListenerService.kt](mobile/src/main/java/com/wearhealth/companion/mobile/service/PhoneWearableListenerService.kt)
- `onDataChanged()`：接收手表的 `/ecg_measurement` 数据
- 用 `MeasurementSerializer.fromDataMap()` 反序列化
- 存入 Room 数据库，发送广播通知 UI

#### [DataLayerManager.kt](mobile/src/main/java/com/wearhealth/companion/mobile/service/DataLayerManager.kt)
- `sendApiKeyToWatch(apiKey)`：`PutDataMapRequest` + `setUrgent()` 发送 API Key
  - `awaitTask` 包装 `Task<DataItem>`，成功返回 `true`
- `requestSyncFromWatch()`：`MessageClient.sendMessage()` 发送同步请求
  - `awaitTask` 包装 `Task<Int>`，成功后 `sent++`
- `getConnectedNodes()`：查询已连接的手表节点
- `awaitTask` 是私有挂起函数，用 `suspendCancellableCoroutine` 包装 Google Play Services Task

#### [EcgMeasurementEntity.kt](mobile/src/main/java/com/wearhealth/companion/mobile/data/EcgMeasurementEntity.kt)
- Room Entity，`@PrimaryKey(autoGenerate = true) id: Long`（自增，非 timestamp）
- `rawEcgBytes` / `downsampledEcgBytes`：二进制波形存储
- `rawDataJson`：`MeasurementSerializer.toJson()` 的元数据
- 重写了 `equals`/`hashCode`（因包含 ByteArray）

#### [HistoryListScreen.kt](mobile/src/main/java/com/wearhealth/companion/mobile/ui/HistoryListScreen.kt)
- 历史列表 + 缩略波形 + 诊断中文化
- 底部 `WatchConnectionBar`：
  - 显示"手表已连接：{名称}"或"手表未连接"
  - 包含刷新按钮（`Icons.Default.Refresh`），点击调用 `viewModel.refreshWatchConnection()`
- 使用 `collectAsState()`（非 `collectAsStateWithLifecycle`，因未引入 lifecycle-runtime-compose 依赖）

#### [PdfExporter.kt](mobile/src/main/java/com/wearhealth/companion/mobile/pdf/PdfExporter.kt)
- 使用 Android `PdfDocument` API（不依赖第三方库）
- A4 页面：标题 + 时间 + 诊断表格 + 参数表格 + ECG 波形图（Canvas 绘制）+ 免责声明
- 波形带 ECG 网格背景，去基线 + 自适应缩放
- 斜体用 `Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)`（非 `Paint.isItalic`）

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
- **历史**：曾有其他 AI 引入 BLE 直传方案（`ble/BleEcgUploader.kt`），已删除，不要恢复

### 11. 波形二进制编码传输
- **现象**：15000 个整数用 JSON 文本约 83KB，接近 DataItem 限制
- **决策**：用 `EcgBinaryCodec` 将 `List<Int>` 编码为 `ByteArray`（4 字节/int，60KB），节省 40% 空间
- **代码位置**：`EcgBinaryCodec.encode()` / `decode()`

### 12. API Key 不再硬编码
- **现象**：API Key 内嵌在 APK 中不安全
- **决策**：API Key 存入 SharedPreferences（ApiKeyManager），手机端可配置推送，手表也可键盘输入
- **Fallback**：BuildConfig.HEARTVOICE_API_KEY 作为默认值，确保首次安装可用

### 13. 传送前检测手机连接状态
- **现象**：手表点击"传送到手机"后立即显示"已传送"，但手机未连接/未收到数据
- **原因**：`putDataItem()` 写入本地缓存就返回成功，不检测对方是否在线
- **决策**：`syncToPhone()` 调用前先 `NodeClient.connectedNodes` 检测手机节点，无连接时提示错误
- **注意**：`putDataItem` 仍不保证送达（手机可能在传送后断联），用户可通过"重新传送"重试
- **代码位置**：`HealthViewModel.syncToPhone()`

### 14. 已传送记录可重新传送
- **现象**：已传送的记录按钮禁用，无法重新传送
- **决策**：移除 `syncToPhone()` 中的 `item.syncedToPhone` 早期返回拦截，UI 改为可点击的"重新传送"按钮
- **代码位置**：`HealthViewModel.syncToPhone()` + `HealthMonitorScreen.kt` 传送按钮

### 15. 手机端连接状态实时刷新
- **现象**：手机端底部"手表未连接"状态静态不更新
- **原因**：`refreshWatchConnection()` 只在 ViewModel init 调用一次
- **决策**：`MainActivity.onResume()` 调用 `refreshWatchConnection()`，底部状态栏添加刷新按钮
- **代码位置**：`mobile/MainActivity.kt` + `HistoryListScreen.kt` 的 `WatchConnectionBar`

### 16. 手机端用 `collectAsState()` 而非 `collectAsStateWithLifecycle()`
- **现象**：`collectAsStateWithLifecycle` 编译错误（Unresolved reference）
- **原因**：未引入 `lifecycle-runtime-compose` 依赖
- **决策**：改用 `collectAsState()`，不额外增加依赖
- **代码位置**：`HistoryListScreen.kt` 等 mobile UI 文件

### 17. 手机端主题用 XML 而非 Compose 主题引用
- **现象**：`Theme.Material3.DynamicColors.DayNight` 在 Manifest 中找不到（AAPT error）
- **原因**：该主题是 Material Components XML 主题，但项目未依赖 `com.google.android.material`
- **决策**：创建 `Theme.WearHealthMobile` XML 主题（DayNight 变体），实际颜色由 Compose `MaterialTheme` 在代码中设置
- **代码位置**：`mobile/src/main/res/values/themes.xml` + `values-night/themes.xml`

### 18. `Paint.isItalic` 不存在
- **现象**：PdfExporter 编译错误
- **原因**：Android `Paint` 没有 `isItalic` 属性
- **决策**：用 `typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)` 替代
- **代码位置**：`PdfExporter.kt`

### 19. `awaitTask` 返回类型注意
- **现象**：`DataLayerManager` 类型不匹配编译错误
- **原因**：`putDataItem()` 返回 `Task<DataItem>`（非 Boolean），`sendMessage()` 返回 `Task<Int>`（非 Boolean）
- **决策**：`awaitTask` 后不直接当 Boolean 用，`putDataItem` 成功后返回 `true`，`sendMessage` 成功后 `sent++`
- **代码位置**：`DataLayerManager.kt`

### 20. `syncMessage` smart cast 问题
- **现象**：`uiState.syncMessage.startsWith()` 编译错误
- **原因**：`syncMessage` 是委托属性（StateFlow），Kotlin 无法 smart cast
- **决策**：先用 `val syncMsg = uiState.syncMessage` 提取局部变量，再做 null 检查
- **代码位置**：`HealthMonitorScreen.kt`

---

## 编译错误排查清单

如果 CI 构建失败，按以下顺序检查：

1. **`when(state)` 分支完整性**：全局搜索 `is EcgCollectionState.`，确保 7 个分支都覆盖
2. **`libs.versions.toml` 引用**：所有 `libs.xxx.yyy` 都在 toml 中有定义
3. **`BuildConfig.HEARTVOICE_API_KEY`**：`app/build.gradle.kts` 中 `buildConfigField` 存在且 `buildConfig = true`
4. **mobile 模块主题**：Manifest 引用 `@style/Theme.WearHealthMobile`，`res/values/themes.xml` 存在
5. **mobile 模块依赖**：不使用 `collectAsStateWithLifecycle`（用 `collectAsState`）
6. **DataLayerManager 返回类型**：`putDataItem` → `Task<DataItem>`，`sendMessage` → `Task<Int>`
7. **PdfExporter**：不使用 `Paint.isItalic`，用 `Typeface.ITALIC`
8. **smart cast**：委托属性（StateFlow）不能 smart cast，先用局部变量提取

---

## 免责声明

本应用仅供健康参考，**不能用于医疗诊断**。如有不适请就医。
