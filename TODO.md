# Wear OS Health Companion TODO

## 已完成
- [x] 项目骨架（Gradle / Manifest / Compose for Wear OS）
- [x] GitHub 仓库 + 签名 keystore Secrets
- [x] GitHub Actions 自动构建 release APK（版本号自增）

## 进行中
- [ ] 传感器采集模块（心率、HRV、血氧、加速度）
- [ ] 本地异常检测算法（阈值 + HRV 分析）
- [ ] TFLite 推理框架（预留接口）
- [ ] Compose for Wear OS UI
- [ ] 前台服务（后台采集）

## 待办
- [ ] 探索 ECG 原始数据采集（Health Services / Samsung SDK）
- [ ] 训练专门的轻量模型（房颤筛查等）
- [ ] 数据本地持久化（Room）
- [ ] 单元测试
