# WearHealthCompanion

WearHealthCompanion 是一个面向 Samsung Galaxy Watch 的 Wear OS ECG（心电图）采集、AI 分析与手机归档项目。

项目由两个可安装 APK 和一个共享库组成：

- **手表端**采集 Samsung BioActive Sensor 的 500 Hz 单导联 ECG，调用 HeartVoice API 分析，并保留最近 50 条历史；
- **手机端**接收完整 ECG、持久化到 Room、显示波形详情并导出 PDF；
- **共享模块**定义 Google Wearable Data Layer 与无 GMS BLE GATT 两套协议。

> **这是当前项目的权威 AI / 开发者交接文档。** 接手前先读“当前状态”“首次使用流程”“不可回退约束”和“下一位 AI 的任务清单”。本应用仅供健康参考，不能替代医疗诊断。

---

## 1. 当前状态（2026-07-14）

<!-- AUTO_BUILD_STATUS:START -->
| 自动发布项目 | 最新成功状态 |
|---|---|
| CI 构建 | **Build #90** / tag `build-90` / source commit `9c98a7e` |
| Actions | `https://github.com/hongquangphung7044-oss/WearHealthCompanion/actions/runs/29459995530`（协议测试、双 Release APK、Artifact、Release 全部成功） |
| Release | `https://github.com/hongquangphung7044-oss/WearHealthCompanion/releases/tag/build-90` |
| APK | `WearHealthCompanion-mobile-v1.0.90-code90.apk`；`WearHealthCompanion-watch-v1.0.90-code90-ecg.apk` |
| 更新时间 | 2026-07-16T00:03:32Z |
| 状态边界 | 仅证明 CI / Artifact / Release 成功；国行 One UI Watch 8 实机结论以“实机验证状态”和任务清单为准 |
<!-- AUTO_BUILD_STATUS:END -->

| 项目 | 状态 |
|---|---|
| 仓库 | `hongquangphung7044-oss/WearHealthCompanion`（private） |
| 默认分支 | `main` |
| 当前功能 | BLE Key 获取入口对“无 Key / 已有 Key”均可见；手表主页始终保留手工输入 API Key 入口；手机设置页可清除缓存（API Key + ECG 历史）并重启 BLE；ECG 波形改用固定时间/电压比例（接近 25 mm/s、10 mm/mV），不再自适应拉伸；Release APK 不再内置 HeartVoice Key。**DeepSeek 双分析已上线**：手表可选 HeartVoice 专业 API 或 DS（flash_fast / flash_balanced / pro_max 三档），DS 模式由本地 `EcgFeatureExtractor` 提取特征 + DS 大模型解读，输出扁平 JSON 报告。**DS 设置 BLE 下发**：国行无 GMS，DS 设置经 `DS_SETTINGS_UUID` GATT 特征值从手机拉取（与 HeartVoice Key 同思路）。**节律判别增强**：RR 变异系数 + Poincaré 散点形态（彗星/扇形/鱼雷/复杂形）+ 短-长 RR 配对数喂给 DS，可区分窦性/房颤/早搏。**间期算法优化**：QRS 阈值交叉法（兼容负向 R 波）+ T 波去基线平滑导数法 + 中位数基线估计；提示词标注系统偏差方向（QRS 偏小 10-20ms、QT 偏大 20-50ms）。**JsonCleaner**：剥离 Markdown 包裹 + 全角引号/冒号/逗号转半角（DS 输出中文标点不再导致解析失败）。**PDF 波形降采样**：`downsampleKeepPeaks` 保留桶内 max+min，R 波尖峰不丢失。**PPG 干扰检测**：绿灯闪烁时告警提示停止后台心率监测再测心电。 |
| 目标设备 | Samsung Galaxy Watch，尤其是 One UI Watch 8 国行版（无可用 Google Data Layer 的场景） |
| 当前同步策略 | Google Data Layer 保留；无 GMS 时自动/主动使用直接 BLE GATT |
| 实机验证状态 | Build #59 已在国行 One UI Watch 8 确认：手机向手表提供 Key 成功，ECG 已能从手表传到手机，Build #58 的 514-byte CHUNK 闪退已解决。用户已能在手机打开记录详情并尝试导出 PDF；但尚无明确证据确认手表最终成功文案、匹配 timestamp 标记和 ACK indication 的完整最终状态，因此仍不得把端到端 ACK 声称为实机通过 |

### 最近构建演进

| Build | 结果 | 说明 |
|---:|---|---|
| #44 | ✅ 成功 | Data Layer ACK 修复后，手表和手机 Release APK 均产出 |
| #45 | ❌ 失败 | `BleSyncProtocol.parseAck()` 表达式函数体中使用 `return`，且漏了 `MAX_TRANSFER_BYTES` |
| #46 | ❌ 失败 | `BleSyncServer.receiveFrame()` 同类 Kotlin 表达式函数体问题 |
| #47 | ❌ 失败 | Kotlin/Java 编译已通过，失败于手机 `lintVitalRelease` 的 Fragment 版本误报 |
| #48 | ✅ 成功 | 直接 BLE ECG 回传版本成功产出 Release APK |
| #49 | ✅ 成功 | 新增“手机保存 Key → 手表无历史也可通过 BLE 获取 Key”代码 |
| #50 | ⚠️ 构建成功但实机入口隐藏 | 手表 APK 仍有编译时 Key fallback，UI 误判为已有 Key，故不显示仅限缺 Key 页面的按钮 |
| #53 | ⚠️ 构建成功，实机仍无扫描回调 | 已绕过 128-bit UUID 硬件过滤，但国行 Watch 8 未向第三方 App 交付任何 BLE 广播 |
| #54 | ⚠️ Key 链路实机成功、ECG 失败 | 手机 Key 已成功传到手表，证明 bonded 直连和加密读取可用；ECG 专用 MTU/ACK/分片阶段仍失败 |
| #57 | ⚠️ BEGIN 实机成功、CHUNK 入队失败 | BLE 已连接，Service/MTU/ACK 已就绪，手机收到 63,748 字节 BEGIN；手表紧接着提交下一帧时 GATT 队列拒绝 |
| #58 | ⚠️ 实机传送触发手表闪退 | Key 链路仍成功；点击 ECG 传送后手机未收到数据。Android 14 会将 MTU 提升为 517，旧公式产生 514 字节 CHUNK attribute value，而 Android 13+ 写 API 的上限为 512 字节且会抛出异常 |
| #59 | ⚠️ ECG 已到手机，最终 ACK 待确认 | 完整 CHUNK 帧限制为 512 字节后不再闪退；国行 Watch 8 已确认手机可收到 ECG，匹配 timestamp ACK/手表最终成功状态尚待明确实测 |

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
   BLE 待机：当前未连接；正在监听手表主动连接
   ```

   这只表示手机 GATT 服务已注册并开始广播，**不表示手表已经连接**；
4. 输入 HeartVoice API Key；
5. 点击“保存并提供给手表”；
6. 手机应提示 Key 已保存，可让手表通过 BLE 主动获取；
7. 保持手机 App 在前台。

### 2.3 手表在没有历史记录时获取 Key

手表主页始终提供 BLE Key 入口：

```text
无 Key：从手机 BLE 获取
已有 Key：从手机 BLE 更新 Key
```

Build #50 因编译时 fallback 使手表误判为已有 Key，且当时仅在缺 Key 页面显示入口，所以实机看不到按钮；下一构建已同时移除 fallback 并让入口始终可见。

点击后流程为：

```text
手表优先枚举系统已配对设备并直接探测本项目 GATT Service
→ 若直连均不匹配，再扫描自定义 Service UUID 广播
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
4. 分析成功后记录先保存到手表历史；默认会自动尝试 Data Layer → 8 秒 ACK → BLE fallback；
5. 测量前先在手机开启“后台 BLE 同步”并确认常驻通知存在（也可暂时保持 App 前台），状态为 BLE 待机或已连接；
6. 手表分析保存后等待自动传输；若失败，进入历史详情点击“传送到手机”重试；
7. 手表先尝试 Data Layer，无可达节点则自动改用 BLE；
8. 手机应依次显示：

   ```text
   BLE 已连接到手表；正在建立 ECG 通道…
   BLE 已连接，ECG 与 ACK 通道就绪；等待手表分片
   正在接收 ECG 分片：…%
   正在保存 ECG 数据…
   ECG 已保存，ACK 已送达手表
   ```

9. 只有手机 Room 写入成功并返回 ACK 后，手表才显示“已传送”。

### 2.5 “重新连接（重启 BLE 监听）”按钮

Build #48 之前的“启动 / 刷新”在服务已经运行时会直接返回，看起来像按钮无效。现在按钮会真正执行：

```text
停止广播和 GATT Server
→ 等待 300 ms
→ 重新注册 GATT Service
→ 重新开始 BLE 广播
```

仅在监听异常、长时间无法连接或系统蓝牙状态变化后使用；正常显示“BLE 待机：当前未连接；正在监听手表主动连接”时，表示手机已准备好，手表会在获取 Key 或传 ECG 时临时连接，不需要常驻已连接。

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
│   ├── AppDatabase.kt               # Room v3 + migration 1→2→3
│   ├── EcgMeasurementEntity.kt      # timestamp 唯一索引
│   ├── EcgMeasurementDao.kt
│   ├── MeasurementRepository.kt     # 按 timestamp 幂等 upsert
│   └── MobileApiKeyStore.kt         # 手机侧 Key（SharedPreferences）
├── service/
│   ├── BleSyncServer.kt             # 手机 GATT Server / advertiser
│   ├── BleSyncRuntime.kt            # 进程级唯一 BLE Server 所有权
│   ├── BleSyncForegroundService.kt  # connectedDevice 前台服务/常驻通知
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
- HeartVoice 基础版单导联 API 当前文档字段均已建模：确定诊断、可能诊断、导联方向、平均心率、P/PR/QRS/QT/QTc、PAC/PVC、信号质量和异常标志；原始响应字符串仅在分析结果对象中短暂保留，不进入手表历史、跨端模型或手机 Room，也不直接展示；
- 本地 R-R 算法只用于显示 min/max 心率，不用于拒绝测量；
- 手表保存最近 50 条历史、缩略波形和完整原始波形；
- 交互波形支持拖动和双指缩放；
- 支持手工输入 Key、Data Layer 下发 Key、BLE 主动读取 Key；
- 支持 Data Layer 和 BLE 两种 ECG 回传；默认在分析成功并保存历史后自动传输，失败不删历史且可手工重试。

### 手机端

- Data Layer 或直接 BLE 接收完整测量；
- Room 持久化，断连后可查看；
- 历史列表、详情参数、交互式 ECG 波形；
- PDF 使用 Android `PdfDocument`；Android 10+ 发布到共享 `Download/WearHealthCompanion`，Android 8–9 通过系统文件选择器保存，并可从 App 直接打开；
- 手机保存 HeartVoice Key，供已配对手表通过 BLE 读取；
- 显示 Google 通道状态和 BLE 广播/连接/接收状态；默认开启 connected-device 前台服务，以常驻通知保持后台监听；
- 同一 timestamp 重传不会产生重复 ECG；手机详情提供带确认对话框的“删除此记录”，只删除手机 Room 副本，不删除手表历史或改变手表同步标记。

### PDF 与波形解释边界

- Android 10+ PDF 保存到 `Download/WearHealthCompanion/ECG_yyyyMMdd_HHmmss.pdf`，系统“文件”应用可见；写入采用 MediaStore `IS_PENDING`，失败会删除未完成条目；
- Android 8–9 使用 Storage Access Framework 由用户选择保存位置，不请求“管理所有文件”权限；
- 手机详情读取约 15,000 点完整 `rawEcgData`，不是约 400 点缩略图；界面显示点数、500 Hz 采样率和估算时长；
- 波形先去均值并按可见窗口自适应振幅，完整 30 秒默认压缩显示；具有节律、趋势和相对形态参考价值，放大后更易观察；
- 屏幕与 PDF 网格不是严格校准的 25 mm/s、10 mm/mV 临床心电图纸，单导联不能等同于临床 12 导联，也不能用于疾病诊断。
- `isReverse` 继续作为 API/协议兼容字段内部保存，但因单导联实机存在持续误报，普通用户界面和 PDF 不再显示“导联可能拿反”提示；不据此拒绝或要求重测。
- 最低/最高心率仍由完整波形的有效 R-R 间期本地估算，不是 API 直接返回；手机、手表和 PDF 始终显示这两个项目，无法可靠估算时明确显示“暂无可靠估算”，不静默隐藏也不填猜测值。

### 自动同步流程

```text
测量完成 → HeartVoice 分析成功 → 用同一精确 timestamp 保存历史
→ 默认自动提交 Data Layer → 最多等待 8 秒 Room ACK
→ 无匹配 ACK 自动 BLE fallback
→ 手机完成长度/序号/chunk/CRC 校验并按 timestamp 幂等写 Room
→ 匹配 timestamp ACK 后手表才标记成功
```

自动传输可在手表关闭。失败时保留未同步历史并提示从历史详情重试，不影响测量结果查看。

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
11. **ECG 采样缓冲必须线程安全。** Samsung SDK 回调线程写入时，UI 只能读取加锁后的不可变快照；不要直接对共享 `MutableList` 使用 `subList()`，否则可能因并发修改导致无详情“采集失败”。
12. **ECG 波形必须用固定时间/电压比例，不再自适应拉伸。** 历史“陡峭”问题来自：X 轴把全部采样点铺满屏幕宽度（30 秒压进 ~200px），Y 轴 auto-scale 把信号拉伸到画布 75%。交互图（手表 `InteractiveEcgChart` / 手机 `MobileEcgChart`）改为固定 `pxPerSecond` 与 `pxPerMillivolt`（接近标准 25 mm/s、10 mm/mV 视觉比例），网格按 1mm 细格 / 5mm 大格刻度；PDF 概览因一页需容纳完整 30 秒，横轴时间已压缩，但纵轴仍按标准 10 mm/mV；手表实时 `EcgWaveform` 改为固定 ±2mV 电压窗口。仅缩略图模式保留自适应填充。不得回退为“铺满宽度 + auto-scale”。
13. **API Key 必须经过 `ApiKeyValidator` 三层清洗。** BLE characteristic value 可能被协议栈在尾部/中段填充 `0x00`，Kotlin `String.trim()` 不移除 0x00，OkHttp 会抛 `Unexpected char 0x00 at N in Authorization value`。所有 Key 入口（BLE 读取、Data Layer 下发、手表手工输入、手机保存、GATT read 响应、HTTP 请求前）必须经 `shared/ApiKeyValidator` 去尾部 NUL + 拒绝中间/非可见控制字符；不得回退为简单 `.trim()`。

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

手机接收端先幂等写 Room，再发 ACK。ACK 发送失败时记录已经存在，之后重传会覆盖同一 timestamp，不会制造重复行。手表前台单条传送即使 `putDataItem()` 返回成功，也只等待最多 8 秒的匹配 ACK；超时会自动转入直接 BLE 上传，不能把 Data Layer 本地缓存提交当成同步成功。

---

## 7. 直接 BLE GATT 协议（国行核心）

### 角色

```text
手机：BLE Peripheral / GATT Server / Advertiser
手表：BLE Central / Scanner / GATT Client
```

手机端由 `BleSyncForegroundService`（`foregroundServiceType="connectedDevice"`）持有进程级唯一 `BleSyncServer`，开启“后台 BLE 同步”后显示低重要度常驻通知，App 可退到后台继续监听。用户可关闭该功能；关闭后仅在 App 可见时监听。前台服务不能保证百分之百抵抗三星电池策略，必要时需允许后台活动或取消电池优化。

### UUID

| 名称 | UUID | 属性 |
|---|---|---|
| Service | `9a4b7d00-2b58-4b02-8bb8-6f15f03e2a01` | Primary service |
| ECG Upload | `9a4b7d01-2b58-4b02-8bb8-6f15f03e2a01` | Write with response |
| API Key | `9a4b7d02-2b58-4b02-8bb8-6f15f03e2a01` | Read, encrypted permission |
| ECG ACK | `9a4b7d03-2b58-4b02-8bb8-6f15f03e2a01` | Indicate |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` | Enable indication |

### Samsung / Wear OS 扫描兼容性

Build #51/#52 的错误为“未发现手机 BLE 同步器”；Build #53 改用空过滤器后，实机进一步确认“未收到任何 BLE 广播”，说明 One UI Watch 8 未向本 App 交付扫描回调。部分 Android/Samsung 蓝牙控制器不仅会对自定义 128-bit Service UUID 的硬件 `ScanFilter` 静默漏报，还可能受 Nearby Devices AppOps 限制。因此 Build #54 同时采用两条路径：

```text
优先：BluetoothAdapter.bondedDevices
→ 逐个对系统已配对设备使用 TRANSPORT_LE 直连
→ 发现本项目 GATT Service 后缓存该手机地址

回退：使用空的系统 ScanFilter 接收广播
→ 在应用内先查 ScanRecord.serviceUuids
→ 再解析原始 AD type 0x06 / 0x07 的 128-bit UUID
→ 仅 UUID 匹配后检查 BOND_BONDED 并连接
```

Manifest 的 `BLUETOOTH_SCAN` 声明 `neverForLocation`；本项目不从扫描推导位置。这不会放宽设备授权：直连候选必须来自系统 bonded 列表，扫描候选必须匹配本项目 UUID 且 bonded，Key characteristic 仍要求 encrypted read。成功识别手机后只缓存其 bonded 地址，不缓存任何 Secret。Key 获取和 ECG 上传期间保持手表屏幕常亮。

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

- 手表请求 MTU 247；ECG 最低接受 MTU 188；Android 14 可能把首次 MTU 请求提升为 517，因此单个完整 CHUNK attribute value 还必须限制为 512 字节；
- chunk payload 根据实际 MTU 和 attribute 上限计算：`min(mtu - 3 ATT, 512) - 1 type - 4 sequence`；
- 每个 chunk 使用 write with response，前一个成功后才发送下一个；One UI Watch 8 上必须等成功回调完全退出，再延迟 12 ms 提交下一帧；若新写 API 返回 GATT busy，则保持同一序号延迟重试，只有成功回调后才推进序号；
- 手机验证序号、总长度、chunk 数和 CRC；
- 手机只在 Room 成功后发送 indication ACK；
- 手表上传总超时 45 秒；Key 获取超时 25 秒。

### BLE 状态含义

| 手机显示 | 含义 |
|---|---|
| `正在启动 BLE 同步器…` | 正在注册 GATT Service |
| `正在启动 BLE 广播…` | Service 已注册，等待 AdvertiseCallback |
| `BLE 待机：当前未连接；正在监听手表主动连接` | 手机 GATT Server/广播已就绪；手表只在获取 Key 或传 ECG 时临时连接 |
| `BLE 已连接到手表；正在建立 ECG 通道…` | 已有已配对 GATT 客户端，尚未完成 ACK CCCD |
| `BLE 已连接，ECG 与 ACK 通道就绪；等待手表分片` | ACK indication 已订阅，可开始上传 |
| `已收到 ECG BEGIN；准备接收 … 字节` / `正在接收 ECG 分片：…%` | 已收到 BEGIN，并显示手机已接收的 payload 进度 |
| `正在保存 ECG 数据…` | CRC 通过，正在写 Room |
| `Room 已保存 ECG；正在等待 ACK indication 送达手表…` | Room 成功，ACK 已排队但尚未由系统确认发送 |
| `ECG 已保存，ACK 已送达手表` | `onNotificationSent(GATT_SUCCESS)` 已确认 indication 发送 |

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

数据库：`ecg_mobile.db`，当前 version 3。Migration 2→3 为 HeartVoice 基础版稳定字段增加 `possibleDiagnoses`、`isReverse`、`avgP`，旧记录使用空/false/0 默认值。

`EcgMeasurementEntity` 保留自增 id，同时给 timestamp 添加唯一索引：

```kotlin
Index(value = ["timestamp"], unique = true)
```

Migration 1→2：

1. 删除旧数据库中相同 timestamp 的重复行，只保留最小 id；
2. 创建 timestamp 唯一索引。

`MeasurementRepository.upsertByTimestamp()` 先读取旧 id，再使用 `OnConflictStrategy.REPLACE` 写入。Data Layer 与 BLE 重传均走此逻辑。Data Layer 每次提交还会附加不含隐私的随机 `transferNonce`，保证同一 timestamp、同一内容在 ACK 丢失后重传时仍触发新的 `TYPE_CHANGED`，从而让手机幂等落库并重发 ACK。

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
- Release APK 已移除 HeartVoice Key 的编译时 fallback；全新安装必须手工输入，或从手机通过 BLE / Data Layer 配置；
- API Key 在所有入口（BLE / Data Layer / 手工输入 / GATT read 响应 / HTTP 请求前）必须经 `shared.ApiKeyValidator` 清洗 NUL 与控制字符；直接 `.trim()` 不足以清除 BLE 协议栈填充的 `0x00`，会导致 OkHttp 抛 `Unexpected char 0x00 at N in Authorization value`；
- 手机设置页提供“删除缓存并重启 BLE”入口，会清除手机端 API Key 与全部 ECG 历史，并重启 BLE 同步器（手表端不受影响）；
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
./gradlew :shared:testReleaseUnitTest :app:assembleRelease :mobile:assembleRelease --no-daemon --stacktrace
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

成功后：

- 生成手表和手机 APK；
- 上传 30 天 Actions Artifact；
- main push 创建 `build-{run_number}` Release/tag；
- 上述步骤全部成功后，`scripts/update_readme_build_status.py` 只更新 README 顶部自动区块，并由 `github-actions[bot]` 提交 `docs: update successful build #N [skip ci]`；该提交不递归触发构建；
- 自动区块只记录 CI/Artifact/Release 事实，不修改“实机验证状态”、不可回退约束或任务清单；同 ref 构建由 Actions 串行执行，脚本也禁止旧 Build 覆盖新 Build。

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

### P0：先读取顶部自动成功构建状态，再继续国行实机验证

1. 先读 README 顶部由 Actions 自动维护的最新成功 Build / Actions / Release / APK；随后仍通过 API 复核，避免把 CI 成功误当作实机成功；
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
- 点击“重新连接（重启 BLE 监听）”后重试。

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

### P1：后台可靠性实机验证

代码已实现显式 connected-device 前台服务、低重要度通知渠道、设置开关、Android 13+ 通知权限和进程级唯一 BLE Server。仍需在国行 One UI Watch 8 验证：App 返回桌面后 Room 落库、ACK 返回、服务停止/重启，以及三星电池策略下的长期存活；不要声称前台服务可百分之百防杀。

### P1：安全清理

1. 轮换已暴露 Key；
2. 手机/手表 Key 迁移到加密存储；
3. 撤销对话中暴露的 GitHub PAT。

### P1：测试

当前 CI 已运行 shared JVM 协议测试，覆盖 ACK、BEGIN/CHUNK/END、MTU、CRC、二进制 round-trip 和畸形长度。继续优先添加：

- BLE server 乱序、重复和并发帧状态机；
- `EcgBinaryCodec` round-trip；
- `MeasurementSerializer` round-trip；
- Room migration 1→2 和 timestamp 幂等写入；
- ACK timestamp 不匹配时不能标记同步；
- 空/超长/乱序/重复 BLE chunk。

### P2（可选）：R 波检测算法升级到 Pan-Tompkins

当前 `EcgFeatureExtractor.detectRPeaks` 用"1秒去基线 + 5点平滑 + 自适应阈值"，已在合成数据测试通过，实机 R 峰检测正常。**若未来出现 R 峰漏检/误检，可考虑移植 Pan-Tompkins 完整版**：

- 5-15Hz Butterworth 带通 + 微分 + 平方 + 滑动积分 + 自适应双阈值 + RR 一致性校验
- 参考实现：[py-ecg-detectors](https://github.com/berndporr/py-ecg-detectors)（单文件，逐函数移植成本低）、[NeuroKit2](https://github.com/neuropsychology/NeuroKit) 的 `pantompkins1985`
- 注意：腕表单导联在噪声场景下 Pan-Tompkins 性能会下降，需配合预处理（0.5Hz 高通 + 50Hz 陷波）或多算法融合（ProMAC）

**不建议当前做**的原因：detectRPeaks 已正常工作，贸然改预处理可能破坏已修复的检测；且 Pan-Tompkins 不直接解决间期偏差（QRS/QT），间期偏差需 delineation 算法（cWT），移植成本更高。在没有真实测试数据回归验证下，风险大于收益。

### P2（可选）：间期 delineation 算法

当前 `estimateIntervals` 用阈值交叉法 + 去基线平滑导数法，QRS 系统偏小、QT 系统偏大（提示词已标注偏差方向让 DS 留余量）。**若需更准的间期数值**，可移植 NeuroKit2 的 `ecg_delineate`（连续小波变换，同时给 P/QRS/T 波边界）：

- 参考实现：[NeuroKit2 ecg_delineate](https://neuropsychology.github.io/NeuroKit/_modules/neurokit2/ecg/ecg_delineate.html)
- 注意：cWT 移植到 Kotlin 需实现小波基函数，工作量较大；腕表单导联精度有物理上限，不可能完全对齐 12 导联专业 API

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
