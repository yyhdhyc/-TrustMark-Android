# Changelog

本项目遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)；版本号与 `app/build.gradle.kts` 的 `versionName` 保持一致。

## v1.2.2 — 2026-11-15

v1.2.2 让通用模式也支持嵌入强度调节，payload 帧结构、包名和部署层不变。

### 新增

- **通用模式嵌入强度调节**：通用模式与批次模式共用同一个强度值（0.50–2.00，步长 0.05，默认 1.30），滑条提取为 `StrengthSlider` 共用组件；强度写入 `SharedPreferences`，重启恢复。
- 通用任务的 strength 不再固定为 1.0，而是随 UI 值传给 JNI；native clamp 仍为 0.0–2.0，无需重建 `.so`。
- 应用版本：`versionCode 5`、`versionName 1.2.2`。

### 验证

- `:app:testDebugUnitTest`：17/17 通过。
- `:app:lintDebug`：0 error / 8 warning。
- Debug APK：`dist/TrustMarkTool-1.2.2-debug.apk`，36,952,274 bytes，v2 签名。
- SHA-256：`e32ab9cada1b5485a45991ce2081bb5117a9319c104f087a3ffe4980fd74c428`。
- Release APK（真机验收后追加）：`dist/TrustMarkTool-1.2.2-release.apk`，29,176,504 bytes，正式密钥 v2 签名（`CN=TrustMarkTool`），包名 `com.trustmarktool`；SHA-256 `d00ed802dd74d174fce9dbe1559581bb8da306cdc8144690ef7db0524128e371`。
- 打包的 `libtrustmark_jni.so` 与 v1.2.1 相同（arm64 `9e70ed8a…`，x86_64 `93860ec5…`）。
- **未在物理设备上验证 JNI、ONNX Runtime、模型推理与 MediaStore 读写。**

## v1.2.1 — 2026-11-08

v1.2.1 是 v1.2.0 的兼容性修复与交互完善版本，payload 帧结构、包名和部署层不变。

### 修复

- **PNG 生成失败**：`native/src/lib.rs` 的 PNG 分支此前把 `ImageRgba32F`/`ImageRgb32F` 直接交给 PNG 编码器；现按是否有 alpha 转为 `Rgba8`/`Rgb8` 后再写入，透明通道保留。
- **批量失败原因不可见**：单张图片异常现在记录“源文件名 -> 输出文件名: 原因”，最多汇总 8 条/4096 字符，随任务终态传到 UI；单张失败不再中断整批。

### 新增

- 生成参数持久化：固定文本、当前批次、通用/批次模式写入 `SharedPreferences`，启动时恢复。
- BCH 下拉按纠错能力从低到高排列：BCH 3 / 4 / 5 / Super；每项两行显示数据位数与可纠位数。
- 生成页与解密页共用 `TaskProgressCard`：运行中显示转圈、取消、进度和当前文件；完成/取消/失败不再显示运行态元素。
- 应用版本：`versionCode 4`、`versionName 1.2.1`。

### 验证

- `:app:testDebugUnitTest`：17/17 通过。
- `:app:lintDebug`：0 error / 8 warning。
- Debug APK：`dist/TrustMarkTool-1.2.1-debug.apk`，38,707,624 bytes，v2 签名。
- SHA-256：`d9ae088b9adb7e77dd87576dda8738ae4c195f2d09d2c04eed21d489748fb773`。
- 两个 ABI 的 `libtrustmark_jni.so` 已用 Rust 1.88.0 + NDK r27b 重新编译；LOAD 对齐 16 KB。
- **未在物理设备上验证 PNG、JNI、ONNX Runtime、模型推理与 MediaStore 读写。**

### 已知限制

- 批次导出仍导出全部批次记录，而非仅当前选中批次。
- 首次启动仍需联网下载约 64.7 MB 的 Q 模型。
- 卸载应用会丢失加密映射表；需定期导出备份。

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
