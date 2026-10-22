# Changelog

本项目遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)；版本号与 `app/build.gradle.kts` 的 `versionName` 保持一致。

## v1.0.0 — 2026-10-22

首个纳入 Git 管理的基线版本（此前开发过程未建立版本库，因此没有更早的历史提交）。

### 新增

- 批量生成：每张源图独立编号 `1..N`，支持同一张图生成 1–99 份副本。
- 输出命名：`原文件名_tm_b{批次短码}_n{编号}.扩展名`，保存到 `Pictures/TrustMarkTool/`。
- 批次与映射表：`Batch`、`MappingEntry`、`OutputRecord`；支持先建表再生成、先生成后补表。
- 加密存储：`BatchStore` 使用 Android Keystore AES-256-GCM 写入应用私有 `filesDir/batch_store.enc`。
- 溯源查询：新增第三个主 Tab，支持从文件名解析批次/编号，并区分解码失败、批次不存在、无匹配、命中四种结果。
- 任务状态机：`RUNNING / COMPLETED / CANCELLED / ERROR`，完成/取消/失败后不再显示转圈。
- 映射表与解密结果导出 CSV/JSON（SAF）。

### 保留

- Rust TrustMark 0.4.0 + JNI + 官方 ONNX Runtime Android 1.22.0。
- Q 模型、双 ABI（`arm64-v8a` / `x86_64`）。
- TMK2 payload 帧、BCH 纠错等级、文本/编号位宽校验。
- 批量解密、置信度展示、原有导出与模型预下载。

### 验证

- `:app:testDebugUnitTest`：13/13 通过。
- `:app:assembleDebug`：通过。
- `:app:lintDebug`：0 error / 8 warning。
- Debug APK：`dist/TrustMarkTool-1.0.0-debug.apk`，v2 签名。
- **未在物理设备上验证 JNI/ONNX Runtime/模型推理与相册读写。**

### 已知限制

- 首次启动需联网下载约 64.7 MB 的 Q 模型，或按 README 离线打包。
- 重构后未重新执行 `assembleRelease`。
- 批次导出当前导出全部批次记录，而非仅当前选中批次。
- 卸载应用会丢失加密映射表；需定期导出备份。
