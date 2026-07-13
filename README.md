# Wear OS Health Companion

一个**独立运行在 Wear OS 手表上**的健康监测应用（不需要手机），支持 ECG 心电图测量和 AI 分析。

## 特性

- 完全独立运行在手表上（不需要手机 App）
- **ECG 心电图测量**：通过 Samsung Health Sensor SDK 采集 500Hz 原始 ECG 波形
- **AI 心电分析**：通过 HeartVoice API 进行单导联 ECG 分析（房颤检测、心律不齐、早搏计数等）
- 实时心率、HRV、血氧监测
- 本地 TensorFlow Lite 推理框架（预留接口）
- 异常告警（心率过高/过低、血氧偏低、HRV 偏低）

## 目标设备

- Samsung Galaxy Watch 7 / Ultra (Wear OS 5)
- 最低支持：Wear OS 3.0 (Galaxy Watch 4+)
- ECG 功能需要 Galaxy Watch 4 及以上（Samsung BioActive Sensor）

## 构建

### 基础版（只有心率/HRV监测）

无需额外配置，直接推送代码即可触发 GitHub Actions 自动构建。

### 完整版（含 ECG 测量 + AI 分析）

需要配置两个 GitHub Secrets：

#### 1. Samsung Health Sensor SDK

1. 访问 https://developer.samsung.com/health/sensor/overview.html
2. 接受许可协议，下载 SDK（`samsung-health-sensor-api.aar`，约 70KB）
3. Base64 编码：`base64 -w 0 samsung-health-sensor-api.aar`
4. 添加为 GitHub Secret：`SAMSUNG_SDK_BASE64`

#### 2. HeartVoice API Key

1. 访问 https://www.heartvoice.com.cn/aiCloud/ 注册获取 API Key
2. 添加为 GitHub Secret：`HEARTVOICE_API_KEY`

配置完成后，推送代码即可自动构建完整版 APK（文件名含 `-ecg`）。

## 技术架构

```
[Galaxy Watch 传感器]
    ↓ Samsung Health Sensor SDK (500Hz ECG)
[ECG 原始波形数据]
    ↓ HTTPS POST
[HeartVoice API 云端分析]
    ↓ JSON 响应
[诊断结果: 窦性心律/房颤/早搏...]
    ↓ Compose for Wear OS UI
[手表屏幕显示]
```

### ECG 分析能力

- 窦性心律 / 窦性心动过速 / 窦性心动过缓
- 心房颤动 (AF) / 心房扑动
- 室性早搏 (VPB) / 房性早搏 (APB)
- 束支传导阻滞 / 房室传导阻滞
- ST 段异常 / QT 间期异常
- QRS / PR / QT 间期测量
- 信号质量评分

## 签名

签名 keystore 已作为 GitHub Secret 存储，所有 CI 构建使用同一签名，**可以覆盖安装**升级。

## 免责声明

本应用仅供健身和健康参考，**不能用于医疗诊断**。ECG 数据和分析结果仅供参考，如有不适请就医。

Samsung Health Sensor SDK 数据声明：用于健身和健康信息，不用于任何医疗状况的诊断或治疗。
