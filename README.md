# Wear OS Health Companion

一个**独立运行在 Wear OS 手表上**的健康监测应用（不需要手机），使用本地传感器 + 本地 AI 模型分析健康数据。

## 特性

- 完全独立运行在手表上（不需要手机 App）
- 采集心率、HRV、血氧、运动数据
- 本地 TensorFlow Lite 模型推理
- 异常告警（心率过高/过低、心律不齐筛查）
- 完全离线，数据不出手表

## 目标设备

- Samsung Galaxy Watch 7 / Ultra (Wear OS 5)
- 最低支持：Wear OS 3.0 (Galaxy Watch 4+)

## 构建

### 本地构建

```bash
./gradlew assembleRelease
```

### GitHub Actions 自动构建（推荐）

推送到 `main` 分支或打 tag 时，GitHub Actions 会自动：
1. 从 Secrets 解码签名 keystore
2. 使用 `github.run_number` 作为递增的 versionCode（支持覆盖安装）
3. 构建签名 release APK
4. 上传为 Release 资产

在 Actions 运行记录里下载 APK 即可。

## 签名

签名 keystore 已作为 GitHub Secret 存储（`KEYSTORE_BASE64`），不会提交到代码仓库。
所有 CI 构建使用同一个 keystore，因此**可以覆盖安装**升级。

⚠️ **请妥善保管本地 keystore 备份**（`.keystore/release.keystore`），丢失后无法再给老用户做覆盖升级。
