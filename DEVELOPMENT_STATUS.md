# WearHealthCompanion — 项目现状与未完成工作

> 写给后续接手此项目的 AI 或开发者。  
> 最后一次更新：2026-07-13  
> 最后一个构建成功的提交：`a92be32`  

---

## 1. 项目概览

这是一个 **Wear OS 独立 ECG 心电图应用**（不需要手机），通过 Samsung Health Sensor SDK 采集原始 ECG 波形，发送到 HeartVoice AI API 进行云端分析。同时新增了一个 **Android 手机同步器**，用于接收手表 ECG 数据、查看报告、导出 PDF，以及管理 HeartVoice API Key。

GitHub 仓库：`https://github.com/hongquangphung7044-oss/WearHealthCompanion`（当前为私有仓库）

---

## 2. 工程结构

```
WearHealthCompanion/
├── app/                          # 手表模块 (Wear OS APK)
│   └── src/main/java/com/wearhealth/companion/
│       ├── MainActivity.kt       # 手表入口；运行时请求 BODY_SENSORS + BLE 权限
│       ├── model/
│       │   └── EcgAnalysisResult.kt  # 数据模型、状态机、诊断中文化、R波检测、WPW过滤
│       ├── sensor/
│       │   └── EcgCollector.kt   # Samsung Health Sensor SDK ECG 采集器 ⚠️ 不可修改
│       ├── network/
│       │   └── HeartVoiceApiClient.kt  # HeartVoice API 调用；API Key 来自 ApiKeyStore
│       ├── data/
│       │   ├── EcgHistoryRepository.kt  # SharedPreferences 历史记录（含同步状态）
│       │   └── EcgRawArchive.kt         # 完整原始 ECG gzip 压缩归档
│       ├── security/
│       │   └── ApiKeyStore.kt           # EncryptedSharedPreferences 存储 API Key
│       ├── ble/
│       │   ├── BleProtocol.kt           # BLE GATT 协议定义（UUID、帧类型、分包大小）
│       │   └── BleEcgUploader.kt        # 手表端 BLE Central：扫描手机、连接、发送 ECG
│       └── ui/
│           ├── HealthViewModel.kt       # 测量流程编排 + BLE 同步触发 + API Key 本地保存
│           ├── HealthMonitorScreen.kt   # Wear Material 3 主界面 + 历史详情含同步按钮
│           └── InteractiveEcgChart.kt   # 可交互 ECG 波形组件（拖动+缩放）
├── mobileApp/                    # 手机模块 (Android Phone APK)
│   └── src/main/java/com/wearhealth/companion/mobile/
│       ├── MainActivity.kt       # 手机入口：报告列表 + 设置页 + PDF 导出入口
│       ├── data/
│       │   ├── EcgRecord.kt      # Room Entity + DAO + Database
│       │   └── EcgStore.kt       # Room 单例 + 原始文件路径工具
│       ├── settings/
│       │   └── SecureSettings.kt # EncryptedSharedPreferences 保存 API Key 和用户资料
│       ├── ble/
│       │   ├── BleProtocol.kt    # 与手表端相同的 BLE 协议定义
│       │   └── BleSyncServer.kt  # 手机端 BLE Peripheral：广播、GATT Server、接收 ECG
│       └── pdf/                  # （预留）PDF 报告生成，当前仅入口
├── .github/workflows/
│   └── build-apk.yml             # CI：同时构建两个 APK，自动上传 Release
├── gradle/libs.versions.toml     # 版本目录（无 GMS 依赖）
├── build.gradle.kts              # 根构建文件
└── settings.gradle.kts           # 包含 :app 和 :mobileApp
```

---

## 3. 构建信息

| 项目 | 值 |
|---|---|
| AGP | 8.6.0 |
| Kotlin | 2.0.0 |
| Gradle | 8.10.2 |
| compileSdk | 35 |
| 手表 minSdk | 33 (Wear OS 3+) |
| 手机 minSdk | 26 (Android 8) |
| JDK | Temurin 17 |
| CI | ubuntu-24.04, GitHub Actions |
| 签名 | 同一 Release Keystore，两个 APK 共用 |

**构建命令**（Actions 使用）：
```bash
./gradlew :app:assembleRelease :mobileApp:assembleRelease --no-daemon --stacktrace
```

CI 需要以下 GitHub Secrets：
- `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` — 签名
- `SAMSUNG_SDK_B64_1`、`SAMSUNG_SDK_B64_2` — Samsung Health Sensor SDK AAR

**HeartVoice API Key 不通过 CI 环境变量注入**。Key 由用户通过手机或手表本地加密存储。

---

## 4. 核心 ECG 功能（不可修改 / 修改需极度谨慎）

以下决策来自原作者的历史踩坑经验，已在 README 中记录，`EcgCollector.kt` 自 `a1061ed` 起未改动：

1. **采集中不检测接触断开** — Samsung SDK 的 `leadOff` 在测量过程中会短暂波动，误判会导致测量失败
2. **SDK `onError` 不中断采集** — 只记日志；部分 TrackerError 可恢复
3. **5 秒预热激活** — 电极接触后丢弃前 5 秒数据，避免建立期噪声
4. **首次接触清空 ecgData** — 丢弃预热期噪声
5. **`fullyReleaseService()`** — 每次新连接前彻底释放 SDK 资源（解决第二次连不上）
6. **预检不做心跳检测** — 只检查数据量 + RMS，心跳检测交给 API 的 sqGrade
7. **leadOff 接触判定用 `!= 5`** — 避免中间值 1-4 误判
8. **实时波形 250 点** — 显示最近 0.5 秒（500Hz × 0.5s）
9. **修改 sealed class 必须补全 when 分支** — 否则 Kotlin 编译失败

---

## 5. 技术限制：无 Google Play 服务

⚠️ **目标设备 Galaxy Watch 7 国行没有 Google 服务框架。**

因此：
- **禁止使用** `com.google.android.gms:play-services-wearable`
- **禁止使用** Wear OS Data Layer API（`WearableListenerService`、`DataClient`、`DataMap`、`Asset` 等）
- **禁止声明** `com.google.android.gms.permission.WEARABLE_BIND_LISTENER`
- 当前所有 GMS 依赖已从 Gradle、Manifest 和源码中移除

同步方案改用 **原生 Bluetooth LE GATT**（见下节）。

---

## 6. BLE GATT 同步协议（当前在修）

### 协议设计

```
手机端：BLE Peripheral (GATT Server)
  - 广播 SERVICE_UUID: 9a4b7d00-2b58-4b02-8bb8-6f15f03e2a01
  - 两个 Characteristic:
    UPLOAD_UUID:  9a4b7d01...  — 手表写入 ECG 数据帧
    API_KEY_UUID: 9a4b7d02...  — 手机读取时返回已保存的 HeartVoice API Key
  - 帧协议:
    TYPE_BEGIN(1): 16B 记录 UUID + 4B 总字节数 + JSON 元数据
    TYPE_CHUNK(2): 负载数据（最多 160 字节/帧）
    TYPE_END(3):   传输完成，手机校验并持久化
    TYPE_ACK(4):   预留

手表端：BLE Central (GATT Client)
  - 扫描手机广播的 SERVICE_UUID
  - 连接后请求 MTU 247，发现服务
  - 把压缩原始 ECG 分包写入 UPLOAD_UUID
```

### 当前 BLE 构建状态

| 模块 | 文件 | 状态 |
|---|---|---|
| 手表 | `ble/BleProtocol.kt` | 已推送 |
| 手表 | `ble/BleEcgUploader.kt` | 已推送（手表 app 编译通过） |
| 手机 | `ble/BleProtocol.kt` | 已推送 |
| 手机 | `ble/BleSyncServer.kt` | ⚠️ 已推送但编译失败 |

### 上次构建失败原因

```
mobileApp/src/main/java/com/wearhealth/companion/mobile/ble/BleSyncServer.kt
```

`receive()` 方法原来使用表达式函数体 `= try { ... }`，其中包含多个 `return`。Kotlin 编译器报错：

```
Returns are prohibited for functions with an expression body.
Use block body '{...}'.
```

已在 `DEV_FIX` 工作区中准备改为块体 `{ return try { ... } }` 的方案；该修补尚未提交和推送。

---

## 7. 未完成的工作

### 高优先级 — 编译修复

1. **手机端 BLE Server 编译修复**  
   把 `receive()` 改为块体函数，去掉表达式函数体中的早返回。  
   文件：`mobileApp/src/main/java/com/wearhealth/companion/mobile/ble/BleSyncServer.kt`

2. **验证双模块 Release 构建成功**  
   修复后等待 Actions 完成，确认两个 APK 均生成。

### 高优先级 — BLE 功能验证

3. **实机 BLE 权限测试**  
   手表（Galaxy Watch 7 国行 Wear OS）运行时需要 `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` 权限。  
   手机需要 `BLUETOOTH_ADVERTISE` + `BLUETOOTH_CONNECT`。  
   Android 12+ 需要运行时请求。

4. **BLE 连接调试**  
   GATT 协议仅在代码层面实现，尚未在实机上验证：
   - 手机 BLE 广播是否被手表发现
   - GATT 连接、MTU 协商、服务发现
   - 分包写入和接收完整性
   - 写入完成后手机持久化 + 回 ACK
   - 手表收到 ACK 后标记 `SENT`

5. **手表本地 API Key 配置入口**  
   当前 UI 中用户无法直接在手表输入 Key。  
   建议在手表"设置"或主界面增加一个极小的输入入口（Wear Material 3 的 `OutlinedTextField` 或弹出对话框）。

### 中优先级

6. **手机 BLE 权限请求 UI**  
   当前手机端没有权限请求逻辑，BLE Server 启动时会因缺少权限而静默失败。

7. **BLE 前台 Service**  
   手机端 Manifest 已声明 `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE`，但前台 Service 代码未实现。没有它，手机在后台时 BLE 广播可能被系统终止。

8. **断联/重连/重试逻辑**  
   手表端 `BleEcgUploader` 有 20 秒超时，但无自动重试队列。

### 低优先级 — UI 和报告

9. **完整 ECG PDF 报告**  
   当前 PDF 仅输出固定字符串。需要读取手机端归档的完整原始波形（`EcgStore.rawFile()`），用 `PdfDocument` + `Canvas` 绘制多页 ECG 网格、指标表格、用户资料和免责声明。用户资料字段（姓名、性别、出生日期、身高、体重）在 `SecureSettings.profile()` 中有存储接口。

10. **手机端 Material 3 美化**  
    首版 UI 较简陋（标准 Material 3 组件 + 基础 BottomNavigation）。后续可优化：报告卡片设计、详情页完整波形、自适应平板布局等。

11. **API Key 下发状态确认**  
    当前仅手机端保存 Key；手表端通过 GATT Read 主动拉取。需要在手机端显示手表 Key 同步状态。

---

## 8. 关键路径总结（推荐顺序）

```
① 修复 mobileApp BleSyncServer.receive() 编译错误
    ↓
② Actions 构建成功，生成双 APK
    ↓
③ 添加手表本地 API Key 输入 UI
    ↓
④ 添加手机 BLE 权限请求 + 前台 Service
    ↓
⑤ 实机测试 BLE 连接、ECG 传输、ACK 确认
    ↓
⑥ 稳定后补充断联重试、PDF 详情、UI 美化
```

---

## 9. 最后成功提交

```text
a92be32 fix: remove Google service dependency for China watch
```

该提交的特点：
- 手表独立 ECG 核心功能完好
- 完整原始 ECG 本地归档已实现
- API Key 使用 EncryptedSharedPreferences
- 手机端 Room 数据库和基础 UI 可用
- PDF 导出有入口（输出最小有效 PDF）
- 构建完全不含 Google Play services
- 手表 `EcgCollector.kt` 与原始稳定版本完全一致
