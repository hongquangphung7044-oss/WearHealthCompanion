# WearHealthCompanion

WearHealthCompanion 是一个面向 Samsung Galaxy Watch 的 Wear OS ECG（心电图）采集、AI 分析与手机归档项目。

项目由两个可安装 APK 和一个共享库组成：

- **手表端**采集 Samsung BioActive Sensor 的 500 Hz 单导联 ECG，调用 HeartVoice API 分析，并保留最近 50 条历史；
- **手机端**接收完整 ECG、持久化到 Room、显示波形详情并导出 PDF；
- **共享模块**定义 Google Wearable Data Layer 与无 GMS BLE GATT 两套协议。

> **这是当前项目的权威 AI / 开发者交接文档。** 接手前先读“当前状态”“首次使用流程”“不可回退约束”和“下一位 AI 的任务清单”。本应用仅供健康参考，不能替代医疗诊断。

---

## 1. 当前状态（2026-07-14）

| 项目 | 状态 |
|---|---|
| 仓库 | `hongquangphung7044-oss/WearHealthCompanion`（private） |
| 默认分支 | `main` |
| 当前功能提交 | `81df5446f08ac8e31bb64f044c91fda23ce83804` — `feat: provision watch API key over paired BLE` |
| 最近确认成功构建 | **Build #48** / tag `build-48` / commit `c4ca6dd` |
| 当前验证构建 | **Build #49**，正在验证 BLE API Key 首次配置链路 |
| Build #49 Actions | `https://github.com/hongquangphung7044-oss/WearHealthCompanion/actions/runs/29303982200` |
| 目标设备 | Samsung Galaxy Watch，尤其是 One UI Watch 8 国行版（无可用 Google Data Layer 的场景） |
| 当前同步策略 | Google Data Layer 保留；无 GMS 时自动/主动使用直接 BLE GATT |
| 实机验证状态 | Build #48 能构建并启动 BLE 广播；完整 Key 获取与 ECG 回传仍需使用 Build #49+ 两端 APK 实测 |

### 最近构建演进

| Build | 结果 | 说明 |
|---:|---|---|
| #44 | ✅ 成功 | Data Layer ACK 修复后，手表和手机 Release APK 均产出 |
| #45 | ❌ 失败 | `BleSyncProtocol.parseAck()` 表达式函数体中使用 `return`，且漏了 `MAX_TRANSFER_BYTES` |
| #46 | ❌ 失败 | `BleSyncServer.receiveFrame()` 同类 Kotlin 表达式函数体问题 |
| #47 | ❌ 失败 | Kotlin/Java 编译已通过，失败于手机 `lintVitalRelease` 的 Fragment 版本误报 |
| #48 | ✅ 成功 | 直接 BLE ECG 回传版本成功产出 Release APK |
| #49 | 验证中 | 新增“手机保存 Key → 手表无历史也可通过 BLE 获取 Key”以打破首次使用死循环 |

### 为什么改成双通道

国行 One UI Watch 8 与 Galaxy Wearable 蓝牙配对正常，但项目使用的：

```kotlin
CapabilityClient.FILTER_REACHABLE
```

始终找不到 Google Wearable Data Layer 节点。因此“Galaxy Wearable 已连接”不等于第三方应用的 Google Data Layer 可用。Outlook 等系统/商店应用可能依赖三星或微软的私有框架、通知、账户或云同步，这些能力不能直接假设对普通第三方 APK 开放。

当前架构因此是：

```text
有可达 Google Data Layer 节点 → 使用 Data Layer
没有可达节点 / 国行无 GMS    → 使用直接 BLE GATT
```

不要再把“Google 同步通道未发现手表”显示或解释为“蓝牙未配对”。

---

## 2. 首次使用流程（国行 / 无 GMS）

这是新对话接手后最重要的实机测试路径。

### 2.1 安装要求

1. 手机和手表必须安装**同一个 Build** 的 APK；不要混用本地 Debug、旧 Release 或不同签名版本。
2. 手机 APK：`WearHealthCompanion-mobile-v1.0.{build}-code{build}.apk`。
3. 手表 APK：`WearHealthCompanion-watch-v1.0.{build}-code{build}-ecg.apk`。
4. 手机首次启动允许“附近设备”权限。
5. 手表首次启动允许身体传感器、扫描和蓝牙连接权限。
6. 手机与手表必须已经通过 Galaxy Wearable 正常配对。

### 2.2 手机先保存 API Key

1. 打开手机“ECG 同步器”；
2. 进入“设置”；
3. 确认状态显示：

   ```text
   BLE 同步器就绪，等待手表传送
   ```

   这只表示手机 GATT 服务已注册并开始广播，**不表示手表已经连接**；
4. 输入 HeartVoice API Key；
5. 点击“保存并提供给手表”；
6. 手机应提示 Key 已保存，可让手表通过 BLE 主动获取；
7. 保持手机 App 在前台。

### 2.3 手表在没有历史记录时获取 Key

手表缺 Key 页面现在应显示：

```text
从手机 BLE 获取
```

点击后流程为：

```text
手表扫描自定义 Service UUID
→ 只接受系统报告为 BOND_BONDED 的手机
→ 建立 GATT 连接
→ 发现 API_KEY_UUID
→ 通过加密读取权限读取 Key
→ 保存到手表 ApiKeyManager
→ 重建 HeartVoiceApiClient
→ 显示“已从手机获取并保存 API Key ✓”
```

这个入口**不依赖 ECG 历史记录**，用于解决旧版本的死循环：

```text
无 Key → 无法完成分析 → 无历史 → 无法触发 BLE → 永远无 Key
```

仍可在手表输入框手工输入 Key，作为 BLE 获取失败时的备用方案。

### 2.4 测量与传送 ECG

1. 手表戴紧手腕；
2. 另一只手食指轻触 Home 键电极，不要按下；
3. 等待接触检测 → 5 秒预热 → 30 秒正式采集 → API 分析；
4. 完成后进入“历史记录 → 测量详情”；
5. 手机保持前台且 BLE 同步器就绪；
6. 手表点击“传送到手机”；
7. 手表先尝试 Data Layer，无可达节点则自动改用 BLE；
8. 手机应依次显示：

   ```text
   手表已通过 BLE 连接，等待 ECG 数据
   正在通过 BLE 接收 ECG 数据…
   正在保存 ECG 数据…
   ECG 已保存并确认给手表
   ```

9. 只有手机 Room 写入成功并返回 ACK 后，手表才显示“已传送”。

### 2.5 “重启 BLE 同步器”按钮

Build #48 之前的“启动 / 刷新”在服务已经运行时会直接返回，看起来像按钮无效。现在按钮会真正执行：

```text
停止广播和 GATT Server
→ 等待 300 ms
→ 重新注册 GATT Service
→ 重新开始 BLE 广播
```

仅在广播异常、长时间无法发现或系统蓝牙状态变化后使用；正常显示“BLE 同步器就绪”时不需要反复点击。

---

## 3. 模块与目录

| Gradle 模块 | namespace / 产物 | minSdk | 职责 |
|---|---|---:|---|
| `:app` | `com.wearhealth.companion` / Wear APK | 33 | Samsung ECG 采集、AI 分析、手表历史、Data Layer/BLE 发送 |
| `:mobile` | `com.wearhealth.companion.mobile` / 手机 APK | 26 | BLE 外设、Data Layer 接收、Room、详情、PDF、Key 配置 |
| `:shared` | `com.wearhealth.companion.shared` / Android library | 26 | 传输模型、序列化、Data Layer 路径、BLE 帧协议 |

两个 APK 的 `applicationId` 都是：

```text
com.wearhealth.companion
```

且 Release 必须使用同一签名证书。这是 Google Data Layer 安全匹配要求；不要只改一个模块的 applicationId 或签名。

### 核心代码地图

```text
app/src/main/java/com/wearhealth/companion/
├── MainActivity.kt
├── sensor/EcgCollector.kt
├── network/HeartVoiceApiClient.kt
├── model/EcgAnalysisResult.kt
├── data/ApiKeyManager.kt
├── data/EcgHistoryRepository.kt
├── ble/BleApiKeyFetcher.kt          # 手表无历史也可从手机读取 Key
├── ble/BleEcgUploader.kt            # 手表扫描、连接、分片上传、等待 Room ACK
├── service/WatchWearableListenerService.kt
└── ui/
    ├── HealthViewModel.kt           # 测量、Key、Data Layer/BLE 选择、历史
    ├── HealthMonitorScreen.kt
    └── InteractiveEcgChart.kt

mobile/src/main/java/com/wearhealth/companion/mobile/
├── MainActivity.kt                  # 权限、启动 BLE 服务、导航
├── data/
│   ├── AppDatabase.kt               # Room v2 + migration 1→2
│   ├── EcgMeasurementEntity.kt      # timestamp 唯一索引
│   ├── EcgMeasurementDao.kt
│   ├── MeasurementRepository.kt     # 按 timestamp 幂等 upsert
│   └── MobileApiKeyStore.kt         # 手机侧 Key（SharedPreferences）
├── service/
│   ├── BleSyncServer.kt             # 手机 GATT Server / advertiser
│   ├── DataLayerManager.kt
│   └── PhoneWearableListenerService.kt
├── ui/MobileViewModel.kt
├── ui/SettingsScreen.kt
├── ui/HistoryListScreen.kt
├── ui/MeasurementDetailScreen.kt
├── ui/MobileEcgChart.kt
└── pdf/PdfExporter.kt

shared/src/main/java/com/wearhealth/companion/shared/
├── DataLayerPaths.kt
├── EcgMeasurementTransfer.kt        # 跨端模型 + EcgBinaryCodec
├── MeasurementSerializer.kt         # DataMap / JSON 元数据
└── BleSyncProtocol.kt               # UUID、帧、CRC、BLE payload codec
```

---

## 4. 产品功能

### 手表端

- Samsung Health Sensor SDK 读取 BioActive Sensor 原始 ECG；
- 固定 500 Hz，正式采集 30 秒，典型约 15,000 点；
- 接触后预热 5 秒，预热数据丢弃；
- HeartVoice API 返回诊断标签、平均心率、PR/QRS/QT/QTc、PAC/PVC、信号质量；
- 本地 R-R 算法只用于显示 min/max 心率，不用于拒绝测量；
- 手表保存最近 50 条历史、缩略波形和完整原始波形；
- 交互波形支持拖动和双指缩放；
- 支持手工输入 Key、Data Layer 下发 Key、BLE 主动读取 Key；
- 支持 Data Layer 和 BLE 两种 ECG 回传。

### 手机端

- Data Layer 或直接 BLE 接收完整测量；
- Room 持久化，断连后可查看；
- 历史列表、详情参数、交互式 ECG 波形；
- PDF 导出使用 Android `PdfDocument`；
- 手机保存 HeartVoice Key，供已配对手表通过 BLE 读取；
- 显示 Google 通道状态和 BLE 广播/连接/接收状态；
- 同一 timestamp 重传不会产生重复 ECG。

---

## 5. ECG 采集状态机与不可回退约束

```text
Idle
  → Connecting（连接 SDK / 等待接触，最多约 30 秒）
  → Preheating(5s，数据丢弃)
  → Collecting(30s，正式记录)
  → 本地预检（数据量 ≥10s、RMS ≥10）
  → Analyzing（HeartVoice API）
  → Done / Error
```

### 必须保留的历史修复

1. **正式采集中不要因 `leadOff` 瞬时变成 5 而中断。** Samsung SDK 在稳定接触时也可能波动。
2. **接触判断使用 `leadOff != 5`。** 1–4 是可能的中间值。
3. **5 秒预热必须保留。** 电极建立期噪声不应进入正式波形。
4. **首次接触与预热结束要清理无效数据。** 否则缩略图会出现直线/噪声。
5. **SDK `onError` 当前只记录警告。** 部分 TrackerError 可恢复，不要直接破坏采集状态机。
6. **每次新测量前彻底释放服务。** `fullyReleaseService()` 中的 listener 解绑、断开、置空和延迟用于避免第二次测量无法连接。
7. **本地预检只检查数据量和 RMS。** 不要重新用本地 R 波算法拒绝 ECG。
8. **修改 `EcgCollectionState` 后全局补齐所有 `when` 分支。** 这是历史 Kotlin 编译失败来源。
9. **测量期间保持屏幕常亮。** Connecting、Preheating、Collecting、Analyzing 均需要。
10. **波形单位是 `mV × 1000` 的整数；API `adcGain = 1000.0`。**

---

## 6. Google Wearable Data Layer 协议

Data Layer 对国际版 / 有可用 GMS 的环境仍是首选通道。

### Capabilities

| 端 | Capability | 资源文件 |
|---|---|---|
| 手机 | `wearhealth_phone_sync_v1` | `mobile/src/main/res/values/wear.xml` |
| 手表 | `wearhealth_watch_sync_v1` | `app/src/main/res/values/wear.xml` |

查询使用 `CapabilityClient.FILTER_REACHABLE`，不要回退到通用 `connectedNodes`；后者可能包含未安装本项目的设备。

### 路径

| 方向 | 路径 | 类型 | 作用 |
|---|---|---|---|
| 手表 → 手机 | `/ecg_measurement/{timestamp}` | DataItem/DataMap | 完整 ECG |
| 手机 → 手表 | `/api_key` | DataItem/DataMap | HeartVoice Key |
| 手机 → 手表 | `/sync_request` | Message | 请求未确认记录 |
| 手机 → 手表 | `/ecg_ack/{timestamp}` | Message | Room 持久化成功确认 |

### ACK 语义

```text
putDataItem() 成功
≠ 手机收到
≠ Room 已保存
≠ 手表可以标记为已同步
```

只有收到 `/ecg_ack/{timestamp}` 后，手表才能执行：

```kotlin
historyRepo.markSynced(timestamp)
```

手机接收端先幂等写 Room，再发 ACK。ACK 发送失败时记录已经存在，之后重传会覆盖同一 timestamp，不会制造重复行。

---

## 7. 直接 BLE GATT 协议（国行核心）

### 角色

```text
手机：BLE Peripheral / GATT Server / Advertiser
手表：BLE Central / Scanner / GATT Client
```

手机 App 需保持前台以提高广播和进程存活可靠性。当前没有前台 Service；系统杀进程后广播会停止。

### UUID

| 名称 | UUID | 属性 |
|---|---|---|
| Service | `9a4b7d00-2b58-4b02-8bb8-6f15f03e2a01` | Primary service |
| ECG Upload | `9a4b7d01-2b58-4b02-8bb8-6f15f03e2a01` | Write with response |
| API Key | `9a4b7d02-2b58-4b02-8bb8-6f15f03e2a01` | Read, encrypted permission |
| ECG ACK | `9a4b7d03-2b58-4b02-8bb8-6f15f03e2a01` | Indicate |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` | Enable indication |

### 设备限制

两端都会检查：

```kotlin
device.bondState == BluetoothDevice.BOND_BONDED
```

手机还只允许当前活动连接设备读取 Key/写 ECG。API Key 特征使用 `PERMISSION_READ_ENCRYPTED`。

> **实机风险：** Galaxy Wearable 的配对设备在应用扫描结果中是否始终报告 `BOND_BONDED`，需用国行 One UI Watch 8 验证。如果手表能看到广播但被此条件忽略，需要采集 `bondState` 和地址日志后调整，不能未经验证直接删除安全检查。

### ECG payload

`BleMeasurementCodec` 使用：

```text
MAGIC "WHC1"
+ protocol version
+ metadata JSON length
+ raw ECG byte length
+ thumbnail byte length
+ metadata JSON（不含波形）
+ raw ECG binary
+ thumbnail ECG binary
```

原始波形和缩略波形均由 `EcgBinaryCodec` 编码，每个 Int 4 字节。30 秒波形约 60 KB；不要改回 JSON 整数数组。

最大 payload：1,000,000 字节。

### GATT 帧

```text
BEGIN = type + version + totalPayloadBytes + CRC32
CHUNK = type + zeroBasedSequence + bytes
END   = type + expectedChunkCount
ACK   = type + success/failure + measurementTimestamp
```

- 手表请求 MTU 247；ECG 最低接受 MTU 188；
- chunk payload 根据实际 MTU 计算：`mtu - 3 ATT - 1 type - 4 sequence`；
- 每个 chunk 使用 write with response，前一个成功后才发送下一个；
- 手机验证序号、总长度、chunk 数和 CRC；
- 手机只在 Room 成功后发送 indication ACK；
- 手表上传总超时 45 秒；Key 获取超时 25 秒。

### BLE 状态含义

| 手机显示 | 含义 |
|---|---|
| `正在启动 BLE 同步器…` | 正在注册 GATT Service |
| `正在启动 BLE 广播…` | Service 已注册，等待 AdvertiseCallback |
| `BLE 同步器就绪，等待手表传送` | 广播成功，尚不代表手表连接 |
| `手表已通过 BLE 连接，等待 ECG 数据` | 已有已配对 GATT 客户端 |
| `正在通过 BLE 接收 ECG 数据…` | 已收到 BEGIN/CHUNK |
| `正在保存 ECG 数据…` | CRC 通过，正在写 Room |
| `ECG 已保存并确认给手表` | Room 成功且已尝试发送 ACK |

---

## 8. 存储与幂等性

### 手表

`EcgHistoryRepository` 使用 SharedPreferences JSON 保存最近 50 条记录：

- timestamp；
- 诊断和 ECG 参数；
- 约 400 点缩略波形；
- 约 15,000 点完整原始波形；
- `syncedToPhone`。

手表存储较大 JSON 不是长期理想方案，但当前不要在同步问题未稳定前同时迁移存储层。

### 手机 Room

数据库：`ecg_mobile.db`，当前 version 2。

`EcgMeasurementEntity` 保留自增 id，同时给 timestamp 添加唯一索引：

```kotlin
Index(value = ["timestamp"], unique = true)
```

Migration 1→2：

1. 删除旧数据库中相同 timestamp 的重复行，只保留最小 id；
2. 创建 timestamp 唯一索引。

`MeasurementRepository.upsertByTimestamp()` 先读取旧 id，再使用 `OnConflictStrategy.REPLACE` 写入。Data Layer 与 BLE 重传均走此逻辑。

---

## 9. API Key 生命周期与安全现状

### 当前路径

```text
手机输入
→ MobileApiKeyStore（SharedPreferences）
→ 已配对手表通过加密 GATT read 主动获取
→ ApiKeyManager（手表 SharedPreferences）
→ HeartVoiceApiClient
```

Data Layer `/api_key` 仍保留，GMS 可用时手机也会尝试提交。

### 重要安全事项

- 不得把 GitHub PAT、HeartVoice Key、keystore 或 Samsung AAR 写入 README、源码、Git remote、提交或日志；
- 当前手机和手表 Key 都是普通 SharedPreferences，**不是加密存储**；
- BLE Key 特征要求 bonded + encrypted read，但本地静态存储仍应后续迁移到 Android Keystore 支持的加密方案；
- `app/build.gradle.kts` 仍存在 HeartVoice Key 的硬编码 fallback，这是已知高优先级安全债；应移除并轮换已经暴露的 Key；
- 曾在对话中明文使用的 GitHub Personal Access Token 必须撤销并重建。

不要在交接文档中记录任何 Secret 实值。

---

## 10. CI、签名与发布

工作流：`.github/workflows/build-apk.yml`

触发：

- push 到 `main`；
- `v*` tag；
- `workflow_dispatch`。

正式命令：

```bash
./gradlew :app:assembleRelease :mobile:assembleRelease --no-daemon --stacktrace
```

环境：JDK 17、compile/target SDK 35、版本号取 `github.run_number`。

### 必需 GitHub Secrets

| Secret | 用途 |
|---|---|
| `KEYSTORE_BASE64` | Release keystore |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key 密码 |
| `SAMSUNG_SDK_B64_1` / `SAMSUNG_SDK_B64_2` | 拆分后的 Samsung Sensor SDK AAR |
| `HEARTVOICE_API_KEY` | 编译时 fallback（建议后续移除） |

成功后：

- 生成手表和手机 APK；
- 上传 30 天 Actions Artifact；
- main push 创建 `build-{run_number}` Release/tag。

Samsung AAR 解码至：

```text
app/libs/samsung-health-sensor-api.aar
```

它受许可约束并被 `.gitignore` 忽略，不得提交。

### Kotlin / lint 历史坑

1. Kotlin 2.0 不允许表达式函数体 `fun x() = try {}` 内使用 `return`；使用块函数体。
2. 不要在 `?: run {}` 内使用 `continue`/`break`，这是实验特性，会让 CI 失败。
3. `ComponentActivity.registerForActivityResult()` 被 lint 误报 Fragment <1.3；两个应用模块都禁用了 `InvalidFragmentVersionForActivityResult`。
4. 修改状态 sealed class 后必须补齐所有 `when`。

---

## 11. 技术栈

- Kotlin 2.0.0
- Android Gradle Plugin 8.6.0
- KSP 2.0.0-1.0.21
- JDK 17
- compileSdk / targetSdk 35
- Compose BOM 2025.04.01
- Wear Compose Material 3 1.6.2
- Lifecycle 2.8.4
- Activity Compose 1.9.2
- Coroutines 1.8.1
- Google Play Services Wearable 18.2.0
- Room 2.6.1
- Navigation Compose 2.7.7
- OkHttp 4.12.0
- Samsung Health Sensor SDK（私有 AAR，仅手表）

---

## 12. 下一位 AI 的优先任务

### P0：确认 Build #49 或文档提交后的下一个 Build

1. 查询最新 Actions，而不是假设 README 中的状态永远最新；
2. 确认 `:app:assembleRelease` 与 `:mobile:assembleRelease` 都成功；
3. 确认 Rename、Artifact、Release 步骤均成功；
4. 如果失败，下载完整 run logs，按**全部错误**修复，不只看网页摘要。

### P0：国行 One UI Watch 8 端到端实机测试

使用同一 Build 的两个 APK：

#### 测试 A：无 Key 首次配置

```text
手机保存 Key
→ 手机 BLE 就绪
→ 手表点“从手机 BLE 获取”
→ 手表显示成功
→ 重启手表 App 后 Key 仍存在
```

记录每一步手机与手表显示的完整文字。

#### 测试 B：完整 ECG

```text
采集 30 秒
→ API 分析成功
→ 历史出现记录
→ 手机保持前台
→ 手表详情点击传送
→ 手机 Room 出现一条记录
→ 手表收到 ACK，变为已传送
```

#### 测试 C：幂等重传

对同一记录点击“重新传送”两次：

- 手机只能保留一个 timestamp 对应记录；
- 详情仍能读取完整波形；
- ACK 均能正常返回。

#### 测试 D：异常恢复

- 手机 App 不在前台；
- 手机关闭蓝牙；
- 手表/手机拒绝附近设备权限；
- 传输中断开；
- 点击“重启 BLE 同步器”后重试。

### P0：若手表仍发现不了手机 BLE

优先排查：

1. 手机状态是否真的到 `AdvertiseCallback.onStartSuccess()`；
2. 手表 `ScanCallback.onScanResult()` 是否触发；
3. `ScanResult.device.bondState` 是否为 `BOND_BONDED`；
4. Galaxy Wearable 使用的配对地址与 BLE 广播随机地址是否一致；
5. GATT Server 是否收到 `onConnectionStateChange`；
6. 加密 Key read 是否因链路加密/配对状态失败。

建议增加不含 Secret 的诊断状态到 UI，而不是要求普通用户抓大量日志。必要时用 ADB：

```bash
adb logcat -s BleSyncServer BleEcgUploader BleApiKeyFetcher
```

不要先删除 bonded/encrypted 安全检查；先用日志证明是哪一层失败。

### P1：后台可靠性

当前 BLE Server 依附手机 App 进程，没有前台 Service。核心流程稳定后可考虑：

- 显式前台 Service；
- 通知渠道；
- 启停和权限生命周期；
- 不常驻高功耗 `ADVERTISE_MODE_LOW_LATENCY`。

### P1：安全清理

1. 移除 `app/build.gradle.kts` 的硬编码 HeartVoice fallback；
2. 轮换已暴露 Key；
3. 手机/手表 Key 迁移到加密存储；
4. 撤销对话中暴露的 GitHub PAT。

### P1：测试

仓库目前缺少自动化测试。优先添加：

- `BleSyncProtocol` 帧编码/解析；
- `BleMeasurementCodec` round-trip、长度和 CRC；
- `EcgBinaryCodec` round-trip；
- `MeasurementSerializer` round-trip；
- Room migration 1→2 和 timestamp 幂等写入；
- ACK timestamp 不匹配时不能标记同步；
- 空/超长/乱序/重复 BLE chunk。

---

## 13. 提交前检查表

1. `git status --short --branch`；
2. `git log -5 --oneline`；
3. 查看最新 Actions 完整状态；
4. 修改 BLE 时同时检查：shared UUID/帧、手表 client、手机 server、权限、UI、README；
5. 修改 Data Layer 时同时检查：paths、capabilities、双方 service、Manifest；
6. 修改 Room entity 时提供 migration；
7. 修改 `EcgCollectionState` 时补齐所有 `when`；
8. 扫描 Secret，不提交 token/key/keystore/AAR/local.properties；
9. `git diff --check`；
10. 以 GitHub Actions Release 构建作为最终编译依据。

---

## 14. 给新对话 AI 的建议开场

可以把下面这段直接发给下一位 AI：

```text
请先阅读仓库 README.md，它是当前权威交接文档。项目是 Samsung Galaxy Watch ECG + Android 手机同步器，目标设备是国行 One UI Watch 8。Google Wearable Data Layer 在该设备上不可用，因此代码保留 Data Layer，同时新增直接 BLE GATT：手机做 peripheral/server，手表做 central/client。当前重点是验证手机保存 API Key→手表无历史也可 BLE 获取 Key→完成 ECG→BLE 分片上传→手机 Room 落库→ACK 后手表标记同步。请先查最新 GitHub Actions 和 main，不要依据旧 Build 状态；不要回退 ECG 采集历史修复，也不要把 putDataItem/GATT 写成功当作持久化成功。
```

---

## 医疗免责声明

本应用显示的 ECG、AI 诊断、心率和 PDF 报告仅供健康参考，不用于疾病诊断、治疗决策或紧急医疗判断。出现胸痛、呼吸困难、晕厥、持续心悸等症状时，应立即寻求专业医疗帮助。
