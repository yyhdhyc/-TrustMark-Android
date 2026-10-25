# Changelog

本项目遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)；版本号与 `app/build.gradle.kts` 的 `versionName` 保持一致。

## v1.2.0 — 2026-10-25

v1.2.0 将“通用文本”和“批次溯源”拆成两种自描述 payload，并移除手动溯源页。

### 变更

- 通用模式：1 位帧类型 `0` + 1 位字母表 + 4 位长度 + 文本；不再携带编号。
- 批次模式：1 位帧类型 `1` + 20 位 4 字母短码 + 12 位编号 + 7 位 CRC-7，固定 40 位，BCH Super。
- 解码器按 payload 第 0 位自动分流；批次 CRC 失败直接返回“解码失败”，不再猜测 ASCII。
- 批次强度可调：`0.50–2.00`，步长 `0.05`，默认 `1.30`。
- UI 改为两个 Tab（生成/解密）；移除溯源页、份数、编号位宽和手动批次兜底。
- 输出命名恢复 `原文件名_tm.ext`，同名追加 `_tm_2`。
- 批次短码改为 4 个大写字母（`A–Z`）；单批次编号 `1..4095`。
- 解密结果自动查询加密映射表：命中绿色、批次有效但无映射黄色、CRC/解码失败红色。
- CSV/JSON 导出新增 `frame_type`、`batch_id`、`serial`、`recipient`。
- 应用名称改为“隐迹”；`versionCode 3`、`versionName 1.2.0`。

### 验证

- `:app:testDebugUnitTest`：15/15 通过。
- `:app:lintDebug`：0 error / 8 warning。
- Debug APK：`dist/TrustMarkTool-1.2.0-debug.apk`，37,138,499 bytes，v2 签名。
- SHA-256：`3A84E1901CF4F295952E6156C3C814B487D3AFC08AEB7768735E9B27625F3439`。
- **未在物理设备上验证 JNI/ONNX Runtime/模型推理与相册读写。**

### 已知限制

- v1.2 帧与 v1.1 TMK2 不兼容，旧水印不保证可解。
- 当前 `.so` 为预编译库 + 定点二进制补丁，源码 `native/src/lib.rs` 已改为 clamp `0.0..2.0`；具备 NDK/Rust 后应重新交叉编译。
- 首次启动仍需联网下载约 64.7 MB 的 Q 模型。
- 批次导出当前导出全部批次记录，而非仅当前选中批次。
- 卸载应用会丢失加密映射表；需定期导出备份。

## v1.1.0 — 2026-10-22

本次将“批次 / 映射表 / 多副本 / 溯源查询”重构作为单独版本发布。

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
- Debug APK：`dist/TrustMarkTool-1.1.0-debug.apk`，v2 签名。
- **未在物理设备上验证 JNI/ONNX Runtime/模型推理与相册读写。**

### 已知限制

- 首次启动需联网下载约 64.7 MB 的 Q 模型，或按 README 离线打包。
- 重构后未重新执行 `assembleRelease`。
- 批次导出当前导出全部批次记录，而非仅当前选中批次。
- 卸载应用会丢失加密映射表；需定期导出备份。

## v1.0.0 — 2026-10-22

首个 Git 基线。由于此前开发过程未使用 Git，`v1.0.0` 与 `v1.1.0` 的源码基线相同；从 `v1.1.0` 起单独记录版本号与交付 APK。
