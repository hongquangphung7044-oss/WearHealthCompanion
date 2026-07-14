# WearHealthCompanion

Wear OS ECG（心电图）采集与分析应用，配套 Android 手机同步器。手表端采集 Samsung BioActive ECG 原始波形并调用 HeartVoice API 分析；手机端通过 **Google Wearable Data Layer** 接收、持久化、查看和导出 PDF 报告。

> **维护交接文档。** 本文件以当前 `main` 为准，供开发者和后续 AI 快速定位构建、协议和风险点。应用仅用于健康参考，不能替代医疗诊断。

---

## 0. 当前快照（先读）

| 项目 | 状态 |
|---|---|
| 默认分支 | `main` |
| 当前基线 | `a206de1` / Build #44，已成功产出手表与手机 Release APK；当前工作区正在引入 BLE GATT 回退，下一次推送将触发验证构建 |
| 国行同步策略 | Google Data Layer 保留为可选通道；未发现 GMS 节点时，手表单条“传送到手机”自动回退到手机 App 前台提供的 BLE GATT 同步器 |
| 失败任务 | `:mobile:compileReleaseKotlin` |
| 最近成功 Actions / Release | **Build #42**，提交 `c4158b2`，tag `build-42` |
| #43 构件 / Release | 未生成；后续重命名、上传 Artifact、创建 Release 步骤均被跳过 |
| 本次推送预期 | 自动触发下一次 `main` 构建，用于验证下述普通控制流修复及两个 Release APK 产出 |

### #43 的确定失败原因与最小修复

`mobile/src/main/java/com/wearhealth/companion/mobile/service/PhoneWearableListenerService.kt` 第 42–45 行使用了 `continue` 作为 `run { ... }` 内联 lambda 的返回路径：

```kotlin
val sourceNodeId = uri.host ?: run {
    Log.w(TAG, "ECG 数据缺少来源节点: $path")
    continue
}
```

Kotlin 2.0.0 将这种 **inline lambda 中的 `break` / `continue`** 视为实验特性，CI 的报错为：

```text
The feature "break continue in inline lambdas" is experimental and should be enabled explicitly
```

不要开启实验特性；改成普通控制流即可：

```kotlin
val sourceNodeId = uri.host
if (sourceNodeId == null) {
    Log.w(TAG, "ECG 数据缺少来源节点: $path")
    continue
}
```

这是当前阻塞发布的首要事项。修复后应重新运行完整 Release 构建；本次检查环境没有 JDK，未在本地复现 Gradle 构建，结论来自 #43 的完整 Actions 日志。

---

## 1. 模块与职责

| Gradle 模块 | 产物 / namespace | 作用 | minSdk |
|---|---|---|---:|
| `:app` | Wear OS APK / `com.wearhealth.companion` | Samsung ECG 采集、AI 分析、手表历史、向手机同步 | 33 |
| `:mobile` | Android APK / `com.wearhealth.companion.mobile` | 接收与 Room 持久化、详情波形、API Key 下发、PDF 导出 | 26 |
| `:shared` | Android library | Data Layer 路径、传输模型、DataMap / JSON / 二进制波形编码 | 26 |

两个 APK 的 **`applicationId` 均为 `com.wearhealth.companion`**，并且必须使用同一签名证书；这是当前 Data Layer 配套发现方案的一部分。不要仅修改其中一个模块的 applicationId 或签名。

### 核心目录

```text
app/                         # 手表端
  sensor/EcgCollector.kt     # Samsung Health Sensor SDK 采集、接触检测、预热、30 秒采集
  network/HeartVoiceApiClient.kt
  data/EcgHistoryRepository.kt
  service/WatchWearableListenerService.kt
  ui/HealthViewModel.kt

mobile/                      # 手机端
  data/AppDatabase.kt
  data/EcgMeasurement*.kt    # Room entity / DAO / repository
  service/PhoneWearableListenerService.kt
  service/DataLayerManager.kt
  ui/MobileViewModel.kt
  pdf/PdfExporter.kt

shared/
  DataLayerPaths.kt
  EcgMeasurementTransfer.kt  # EcgMeasurementTransfer + EcgBinaryCodec
  MeasurementSerializer.kt

.github/workflows/build-apk.yml
```

---

## 2. 产品能力

### 手表端

- Samsung Health Sensor SDK 采集 **500 Hz** 单导联 ECG；接触后预热 5 秒（丢弃预热数据），正式采集 30 秒。
- HeartVoice API 分析：诊断标签、平均心率、PR/QRS/QT/QTc、PAC/PVC、信号质量等。
- 本地保存最近 50 条记录：缩略波形、完整原始波形以及手机确认同步状态。
- 交互式 ECG 图（拖动、双指缩放、ECG 网格），测量期间保持亮屏。
- 既支持手表输入 API Key，也支持手机端通过 Data Layer 下发。

### 手机端

- 接收完整测量数据，写入 Room，断连后仍可查看。
- 历史列表、详情页及可缩放 ECG 波形。
- 向手表发送 HeartVoice API Key、请求补传未确认记录、显示兼容手表连接状态。
- 用 Android `PdfDocument` 导出含波形、参数、诊断和免责声明的 PDF。

---

## 3. 通信协议与“已同步”的定义

**只使用 Google Wearable Data Layer，不要恢复旧的 BLE 直传实现。** Data Layer 负责底层链路与路由；业务代码使用 Capability、DataItem 和 Message。

### Capability

| 端 | Capability | 声明位置 | 用途 |
|---|---|---|---|
| 手机 | `wearhealth_phone_sync_v1` | `mobile/src/main/res/values/wear.xml` | 手表只向可达且声明此能力的手机发送 ECG |
| 手表 | `wearhealth_watch_sync_v1` | `app/src/main/res/values/wear.xml` | 手机只向可达且声明此能力的手表发同步请求 |

查询使用 `CapabilityClient.FILTER_REACHABLE`。不要退回通用 `connectedNodes`，其中可能存在没有安装本项目配套应用的节点。

### 路径

| 方向 | 路径 | API | 说明 |
|---|---|---|---|
| 手表 → 手机 | `/ecg_measurement/{timestamp}` | `DataItem` / `DataMap` | 完整 ECG 测量数据 |
| 手机 → 手表 | `/api_key` | `DataItem` / `DataMap` | HeartVoice API Key |
| 手机 → 手表 | `/sync_request` | `MessageClient` | 请求手表提交所有未确认记录 |
| 手机 → 手表 | `/ecg_ack/{timestamp}` | `MessageClient` | 手机 Room 写入完成后的确认回执 |

常量全部放在 `shared/.../DataLayerPaths.kt`；新增/改动协议必须同步修改两端 Manifest、监听服务、序列化和本文档。

### ECG 编码

`EcgMeasurementTransfer` 为跨端模型。标量字段放入 `DataMap`；完整原始波形和缩略波形由 `EcgBinaryCodec` 编码为 `ByteArray`（int 为 4 字节）。典型 30 秒 × 500 Hz 原始波形约 15,000 点 / 60 KB，避免用 JSON 文本作为 DataItem 波形载荷。

### 当前可靠性流程（#43 引入，尚未通过 CI）

```text
手表用户点击“传送到手机”或手机发送 /sync_request
  → 手表通过 capability 找到可达的配套端
  → 手表 putDataItem(/ecg_measurement/{timestamp})，状态为“已提交，等待确认”
  → 手机 PhoneWearableListenerService 反序列化并写入 Room
  → 手机向 DataItem URI 的来源节点发送 /ecg_ack/{timestamp}
  → 手表 WatchWearableListenerService 收到 ACK 后才 markSynced(timestamp)
```

**关键语义：** `putDataItem()` 成功仅代表本地 Data Layer 接受请求，**不代表手机收到，更不代表 Room 已持久化**。只有 `/ecg_ack/{timestamp}` 能将 `HistoryItem.syncedToPhone` 设为 `true`。未收到 ACK 的记录保持“未传送”，可由用户重试或通过手机“请求同步”补传。

### 国行 / 无 GMS 的 BLE GATT 回退（当前开发中，以下一次 Actions 为准）

当 `CapabilityClient.FILTER_REACHABLE` 没有发现 Google Data Layer 节点时，手表端的**单条**“传送到手机”会改用直接 BLE GATT：

```text
手机 ECG 同步器在前台 → BLE 广播自定义 ECG 服务
手表历史详情点“传送到手机” → 扫描已配对手机 → GATT 连接 / MTU 协商
→ BEGIN（长度 + CRC）→ 带序号二进制 ECG 分片 → END
→ 手机 CRC 校验、按 timestamp 幂等写 Room → ACK indication
→ 手表收到成功 ACK 后才 markSynced(timestamp)
```

- BLE 协议位于 `shared/BleSyncProtocol.kt`；波形仍为二进制 int 数组，不传 JSON 波形。
- 手机端还提供只读 API Key GATT 特征：手机设置页先保存 Key，手表在无 Key 页面点“从手机 BLE 获取”，不需要先有 ECG 历史记录。
- 手机端 `BleSyncServer` 只接受系统显示为已配对的设备，并只在手机同步器进程存活、App 前台使用时提供可靠广播。
- Room 为 `timestamp` 添加唯一索引与迁移；重传会替换同一时间戳记录，避免重复 ECG。
- **使用方式（首次 / 无 Key）**：手机打开设置并保存 Key（状态应为“BLE 同步器就绪”）→ 保持手机前台 → 手表缺 Key 页面点“从手机 BLE 获取”→ 成功后再测量。
- **使用方式（传 ECG）**：手机保持前台 → 手表进入历史详情 → 点“传送到手机”。BLE 回退当前是用户发起的单条传送；批量“请求同步”仍仅适用于 Google Data Layer。
- 手机端按钮现在执行真正的 BLE 重启（停止 GATT/广播、延迟后重新启动），不是就绪状态下的空操作。

### 同步相关代码入口

- 手表单条发送：`HealthViewModel.syncToPhone()`。
- 手表批量发送：`HealthViewModel.syncAllUnsynced()` 与 `WatchWearableListenerService.syncAllUnsynced()`。
- 手表接收 API Key、同步请求、ACK：`WatchWearableListenerService`。
- 手机接收、落库、ACK：`PhoneWearableListenerService`。
- 手机发送 API Key / 请求同步 / capability 查询：`DataLayerManager`。

---

## 4. 构建、签名与发布

### CI

工作流：`.github/workflows/build-apk.yml`

- 触发条件：push 到 `main`、`v*` tag、手动 `workflow_dispatch`。
- JDK：Temurin 17。
- 构建命令：

  ```bash
  ./gradlew :app:assembleRelease :mobile:assembleRelease --no-daemon --stacktrace
  ```

- 版本号来自 GitHub `run_number`：`versionCode = run_number`，`versionName = 1.0.{run_number}`。
- 构建成功后重命名两个 APK、上传 30 天 Artifact，并在 main push 时创建 `build-{run_number}` Release。

### 必需 GitHub Secrets

| Secret | 用途 |
|---|---|
| `KEYSTORE_BASE64` | 共用 Release keystore 的 Base64 内容 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` / `KEY_PASSWORD` | 签名 key 信息 |
| `SAMSUNG_SDK_B64_1` / `SAMSUNG_SDK_B64_2` | 拆分后的 Samsung Sensor SDK AAR Base64 |
| `HEARTVOICE_API_KEY` | CI 注入的 HeartVoice API Key |

Samsung AAR 在 CI 被解码到 `app/libs/samsung-health-sensor-api.aar`。该文件受许可限制且被 `.gitignore` 忽略，不能提交。

### 本地构建

前提：Android SDK、**JDK 17**、有效的签名配置（Release）以及 `app/libs/samsung-health-sensor-api.aar`（手表完整 ECG 功能）。

```bash
# 可先检查环境
java -version

# 若仅调试模块，使用 Debug；Release 还需配置签名环境变量
./gradlew :app:assembleDebug :mobile:assembleDebug

# 与 CI 等价的 Release 构建
VERSION_CODE=44 VERSION_NAME=1.0.44 \
KEYSTORE_PATH=/absolute/path/release.keystore \
KEYSTORE_PASSWORD='...' KEY_ALIAS='...' KEY_PASSWORD='...' \
HEARTVOICE_API_KEY='...' \
./gradlew :app:assembleRelease :mobile:assembleRelease --no-daemon --stacktrace
```

没有 Samsung AAR 时，Gradle 会跳过该依赖并打印“ECG 功能不可用”；不要把“基础版能否编译”误认为真实 ECG 采集已验证。

---

## 5. 关键实现约束（不要随意回退）

1. **采集中不因 `leadOff` 波动终止。** Samsung SDK 在稳定接触下也可能短暂报告 `5`；连接阶段使用 `leadOff != 5` 判断接触，正式采集阶段不以它中断。
2. **预热是有意设计。** 接触后 5 秒数据丢弃，再开始正式 30 秒采集，避免建立期噪声进入历史与 AI 分析。
3. **SDK `onError` 只记录警告。** 部分 tracker 错误可恢复，不能直接把采集状态设为失败。
4. **每次采集前彻底释放服务。** `EcgCollector.fullyReleaseService()` 的 `unsetEventListener`、断开和延迟不可随意删掉，否则第二次测量可能无法连接。
5. **本地预检只检查数据量与 RMS。** 不要用本地 R 波算法拒绝测量；R 波算法仅用于显示 min/max 心率，信号质量主要由 API `sqGrade` 判断。
6. **波形传输保持二进制。** 不要把 15,000 点 ECG 改回 JSON 文本后塞进 DataItem。
7. **修改 `EcgCollectionState` 必须全局补齐所有 `when` 分支。** 这是 Kotlin 编译失败的历史来源。
8. **同步状态以 ACK 为准。** 不能在 `putDataItem()` 返回后调用 `markSynced()`。
9. **已确认记录仍允许手动重新传送。** UI 的“重新传送”不是禁用按钮，用于人工恢复。

---

## 6. 后续 AI 的优先级清单

### P0：恢复可发布构建

1. 按“当前快照”中的普通 `if` 修复 `PhoneWearableListenerService.kt` 的 inline-lambda `continue`。
2. 推送后观察完整 Actions；必须确认两个 Release APK 均产出、Artifact 上传、Release 创建步骤都成功，而不仅是 Kotlin 编译通过。
3. 用真实配对的手表和手机做一次完整实机回归：采集 → 传送 → Room 出现记录 → 收到 ACK → 手表状态变为“已传送”。

### P1：同步幂等性与错误处理

当前 Room `EcgMeasurementEntity` 的主键是自动递增 `id`，`timestamp` **不是唯一键**。Data Layer 的同一记录重复投递或用户重传时，可能插入重复记录；`OnConflictStrategy.REPLACE` 目前无法按 timestamp 去重。

建议在真正依赖 ACK 机制前完成以下之一：

- 给 `timestamp` 加唯一索引，并实现按 timestamp 的 upsert；或
- 以 timestamp 作为稳定主键，审查导航和删除逻辑后迁移数据库。

还应明确重复记录的 ACK 策略（通常：数据已经存在也应视为持久化成功并 ACK），并给 `Tasks.await(sendMessage(...))`、Room 写入增加可观测的异常处理与日志，避免一个 ACK 失败造成后台协程静默失败。

### P1：安全处理

- **立即撤销并重新生成**曾出现在对话、终端历史或任何非 Secret 位置的 GitHub Personal Access Token；不要把 token 写入 README、源码、Git remote URL、提交记录或日志。
- README 已不再写入任何 API Key 实值。检查并移除 `app/build.gradle.kts` 中的硬编码 HeartVoice fallback；应只从 CI Secret、受保护的本地 `local.properties` 或用户运行时配置读取。
- 已泄露的 HeartVoice Key 同样应在服务端轮换，并更新 GitHub Secret。SharedPreferences 适合当前功能，但不应宣称它等同于硬件级密钥保护。

### P2：清理与测试

- `app/src/main/AndroidManifest.xml` 仍有旧 BLE 注释和 Bluetooth 权限，而当前实现采用 Data Layer。确认没有 SDK 需求后清理，避免误导维护者和造成不必要权限。
- 仓库当前没有可见的自动化单元/集成测试。优先为 `EcgBinaryCodec`、`MeasurementSerializer`、历史 JSON 迁移、ACK 路径解析和 Room 幂等写入补测试。
- 对 AndroidManifest 中 Data Layer listener 的 path、capability 资源、同签名安装条件建立实机测试清单；这些问题通常无法仅靠 JVM 单测发现。

---

## 7. 开发交接检查表

每次接手或提交前按此顺序执行：

1. `git status --short --branch`、`git log -5 --oneline`，确认是否有未合并的修复。
2. 查看最新 Actions 的 **失败任务和完整编译错误**，不要把旧的“最近成功构建”当作当前状态。
3. 修改 Data Layer 时同时核对：`DataLayerPaths`、双方 capability 资源、Manifest listener、手表/手机 service、序列化、README。
4. 修改采集状态机时全局检查所有 `when (state)`。
5. 执行至少 `:mobile` 和 `:shared` 的构建；具备 AAR、签名和实机时再执行完整 `:app` Release 与端到端同步验证。
6. 不提交 AAR、keystore、API key、token、构建产物或本地配置。

---

## 8. 技术栈

- Kotlin 2.0.0、AGP 8.6.0、JDK 17
- compileSdk / targetSdk 35
- Compose BOM 2025.04.01；Wear Compose Material 3 1.6.2
- Google Play Services Wearable 18.2.0
- Room 2.6.1、KSP、Navigation Compose
- OkHttp 4.12.0、Samsung Health Sensor SDK（仅手表端）

---

## 医疗免责声明

本应用显示的 ECG、AI 诊断、心率和 PDF 报告仅供健康参考，不用于疾病诊断、治疗决策或紧急医疗判断。出现胸痛、呼吸困难、晕厥、持续心悸等症状时，应立即寻求专业医疗帮助。
