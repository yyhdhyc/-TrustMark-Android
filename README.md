# 隐迹 · TrustMark Android

在 Android 手机上批量写入、批量读取 Adobe TrustMark 隐水印。应用使用 Rust 版 TrustMark + ONNX Runtime，通过 JNI 在端侧完成推理；图片和批次映射都留在本机。

- **应用名**：隐迹
- **包名**：`com.trustmarktool`（debug 为 `com.trustmarktool.debug`）
- **当前版本**：1.2.1（versionCode 4）
- **模型**：TrustMark **Q**，编码器 + 解码器
- **ABI**：`arm64-v8a`、`x86_64`
- **最低系统**：Android 8.0（API 26）

---

## 1. 功能

| 功能 | 说明 |
|---|---|
| 批量生成 | 一次最多 50 张源图，逐张读取原图、逐张编码 |
| 通用模式 | 固定文本写入每张图，不携带编号；适合 `CONFIDENTIAL`、`ACME 2026` 这类统一标记 |
| 批次模式 | 批次短码 + 接收人编号；适合会议材料、逐人分发、泄露溯源 |
| 批量解密 | 自动识别通用帧 / 批次帧，批量提取文本或批次编号 |
| 本地映射 | 批次、接收人、输出记录加密保存在应用私有目录 |
| 导出 | 解密结果导出 CSV / JSON；批次映射导出 CSV / JSON |
| 进度与取消 | 前台通知 + 应用内进度卡片，可取消未完成任务 |
| 失败隔离 | 单张失败不中断整批，卡片列出文件名与原因 |

应用只有两个主 Tab：**生成** 和 **解密**。批次命中信息直接显示在解密结果中，不再单独提供溯源页。

---

## 2. 直接安装

仓库中的 debug APK：

```text
dist/TrustMarkTool-1.2.1-debug.apk
```

- 大小：`38,707,624` bytes
- SHA-256：`d9ae088b9adb7e77dd87576dda8738ae4c195f2d09d2c04eed21d489748fb773`
- 签名：debug keystore，APK Signature Scheme v2
- 首次启动会从 Adobe 官方 CDN 下载 Q 模型（约 62 MB），需联网；下载完成后可离线使用。

> debug 包名带 `.debug`，与 release 包可以并存。正式分发前应使用自己的 release keystore 重新签名。

---

## 3. 使用流程

### 3.1 通用模式：统一文本

1. 打开 **生成**，模式选择 **通用**。
2. 从相册选择一张或多张 JPEG / PNG。
3. 输入固定文本。仅支持 `A–Z`、`0–9`、空格、`-`、`_`、`.`，自动转大写，最长 15 字符。
4. 选择 BCH 等级。默认 **BCH 5**；文本较长时改用 BCH 3 / 4，文本较短且希望更强纠错时用 **BCH Super**。
5. 查看容量条：`已用 X 位 / 剩余 Y 位`，点“查看详情”可看帧头和二进制预览。
6. 开始生成。每张源图生成 **1 个** 输出文件。

示例：`ACME` + BCH Super = `6 + 4×5 = 26` 位，容量 40 位，剩余 14 位。

### 3.2 批次模式：逐人编号

1. 先在 **生成 → 批次与映射** 中新建批次，填写接收人列表（编号 1、2、3……）。
2. 回到生成页，模式选择 **批次**。
3. 选择源图，再选择批次。每张源图会按该批次的全部映射条目各生成一份。
4. 调整嵌入强度。默认 `1.30`，范围 `0.50–2.00`，步长 `0.05`。强度越高越抗压缩，但原图视觉扰动也越明显。
5. 开始生成。批次模式固定使用 **BCH Super**。

示例：1 张源图 + 3 个接收人 → 3 个输出文件，编号分别为 1、2、3。2 张源图 + 3 个接收人 → 6 个输出文件，每张源图都从编号 1 开始。

### 3.3 解密与溯源

1. 打开 **解密**，选择一张或多张可疑图片。
2. 应用读取 payload 第 0 位，自动分流：
   - 0：通用帧，显示固定文本；
   - 1：批次帧，校验 CRC-7，读取 4 字母批次短码和 12 位编号。
3. 批次帧通过 CRC 后，自动查询本地加密映射表：
   - 命中接收人：绿色，显示批次名、接收人、编号；
   - 批次存在但没有对应编号：黄色，“本地无匹配”；
   - 帧损坏 / CRC 失败：红色，“解码失败”。
4. 结果可导出 CSV / JSON。

> 解码结果依赖本机的 `BatchStore`。换手机、清除数据或卸载应用后，映射表不会自动恢复；请定期导出备份。

---

## 4. Payload 规范

TrustMark 原生只接受 0/1 位串。应用在 Kotlin 层构造数据位，native 层补齐到 BCH 数据容量后追加校验位和 4 位 schema，最终严格为 **100 位**。

### 4.1 通用帧

```text
bit 0      : 0（通用帧）
bit 1      : 字母表模式
             0 = A–Z，每字符 5 位
             1 = A–Z / 0–9 / 空格 / - _ .，每字符 6 位
bit 2..5   : 文本长度 1..15（4 位）
bit 6..    : 文本字符
```

帧头固定 6 位。不同 BCH 等级的数据容量与文本上限：

| BCH | 数据容量 | 大写字母上限 | 字母数字上限 |
|---|---:|---:|---:|
| BCH Super | 40 位 | 6 | 5 |
| BCH 5 | 61 位 | 11 | 9 |
| BCH 4 | 68 位 | 12 | 10 |
| BCH 3 | 75 位 | 13 | 11 |

文本必须非空；超出容量时在任务创建阶段阻止整批，并给出具体原因。

### 4.2 批次帧

```text
bit 0      : 1（批次帧）
bit 1..20  : 4 个字母，每个 5 位（A=0 .. Z=25）
bit 21..32 : 12 位编号，范围 1..4095
bit 33..39 : CRC-7，7 位
```

固定 40 位，只能使用 **BCH Super**。

CRC 采用 CRC-7/MMC 参数：

- 多项式：`x^7 + x^3 + 1`（`0x09`）
- 初始值：`0`
- 不反转，不异或输出
- 8 位寄存器实现，隐含 `x^7` 项，末尾补 7 个 0
- 校验值：`"123456789"` → `0x75`

示例：批次 `ABCD`、编号 `42`：

```text
数据位 100000000010001000011000000101010
CRC    1000110  (0x46)
完整   1000000000100010000110000001010101000110
```

### 4.3 兼容性

v1.2 的帧格式与 v1.1 的 TMK2 帧不兼容。v1.1 生成的图片不能按 v1.2 规则解出文本或批次；旧 `B`/`G` 开头的 3 字符批次也不会出现在新批次选择器中。

---

## 5. 输出与映射表

### 5.1 输出文件

- 目录：`Pictures/TrustMarkTool/`
- 命名：`原文件名_tm.ext`
- 同名冲突：`原文件名_tm_2.ext`、`_tm_3.ext`……
- 格式：PNG 输入输出 PNG（保留 alpha）；JPEG 输入输出 JPEG
- 每份输出都重新读取原始 URI 并解码，绝不在已编码 Bitmap 上二次编码

### 5.2 批次映射

| 数据 | 内容 |
|---|---|
| `Batch` | 4 字母短码、批次名、创建时间、模型变体 Q、BCH 等级、备注 |
| `MappingEntry` | 批次 ID、编号 1..4095、接收人、备注 |
| `OutputRecord` | 批次 ID、源图标识、编号、输出文件名、创建时间 |

存储位置：

```text
filesDir/batch_store.enc
```

使用 Android Keystore 中的 AES-256-GCM 密钥加密，密钥别名：

```text
trustmark_batch_store_key_v1
```

映射表只存在应用私有目录，不写外部存储，也不上传。卸载应用会丢失；UI 中有持续提示。

### 5.3 导出

批次映射导出字段：

```text
batch_id,batch_name,serial,recipient,note,output_file,created_at
```

解密结果导出字段：

```text
file_name,frame_type,decoded_text,batch_id,serial,batch_name,recipient,ecc,confidence,bitflips,payload_bits,error
```

导出通过 SAF `CreateDocument` 完成，不需要存储权限。批次导出当前导出 **全部批次**，不是单个选中批次。

---

## 6. 生成参数持久化

以下值保存在 `SharedPreferences("generate_preferences")`，修改即保存，重启应用后恢复：

- 固定文本
- 当前生成模式（通用 / 批次）
- 当前批次 ID

批次列表和映射表本身不放在 SharedPreferences 中，而由加密的 `BatchStore` 管理。

---

## 7. 架构与目录

```text
app/src/main/java/com/trustmarktool/
├── MainActivity.kt
├── MainViewModel.kt              # 双模式状态、任务构建、批次 CRUD、导出
├── model/
│   ├── PayloadCodec.kt           # 通用帧 / 批次帧 / CRC-7 / 自动解码
│   ├── GenerateTask.kt           # 扁平任务模型
│   ├── Batch.kt
│   ├── MappingEntry.kt
│   ├── OutputRecord.kt
│   ├── EccLevel.kt
│   └── UiModels.kt
├── data/
│   ├── BatchStore.kt             # AES-GCM 加密批次/映射/输出记录
│   ├── TaskPlanStore.kt          # Worker 任务清单 JSON
│   ├── GeneratePreferences.kt    # 生成参数持久化
│   ├── ImageStore.kt             # 原图读取、MediaStore 保存
│   ├── OutputNaming.kt           # _tm / _tm_2 命名
│   └── DecodeStore.kt
├── nativebridge/
│   ├── TrustMarkEngine.kt
│   └── TrustMarkNative.kt
├── worker/
│   ├── EncodeWorker.kt
│   ├── DecodeWorker.kt
│   ├── WorkerKeys.kt
│   └── TaskNotifications.kt
└── ui/
    ├── MainScreen.kt
    ├── GenerateScreen.kt
    ├── DecodeScreen.kt
    ├── BatchManagementDialog.kt
    └── components/TaskProgressCard.kt

native/
├── src/lib.rs                    # JNI 桥接、模型加载、PNG/JPEG 编解码
├── vendor/trustmark/             # vendored TrustMark 0.4.0
└── Cargo.toml

scripts/
├── build-native.sh
├── build-native.ps1
└── fetch-models.py
```

推理链路：

```text
Compose UI
  → MainViewModel
  → WorkManager (EncodeWorker / DecodeWorker)
  → TrustMarkEngine (Kotlin)
  → libtrustmark_jni.so (Rust)
  → ONNX Runtime Android
  → encoder_Q.onnx / decoder_Q.onnx
```

生成任务由 `MainViewModel` 先构建为扁平 `List<GenerateTask>`，写入 `TaskPlanStore`，Worker 只按清单顺序执行。进度、成功数、失败数和取消都以这份任务清单为准。

---

## 8. 构建

### 8.1 环境

- JDK 21
- Android SDK Platform 35 + Build Tools 35.0.0
- 项目自带 Gradle Wrapper（Gradle 8.9）
- 如需重新编译 native：Rust 1.88+、Android NDK `27.0.12077973`、`cargo-ndk`

在 `local.properties` 中配置 SDK：

```properties
sdk.dir=D:\\path\\to\\android-sdk
```

### 8.2 测试与 APK

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

Windows 使用 `gradlew.bat`。产物：

```text
app/build/outputs/apk/debug/app-debug.apk
```

当前验证结果：

- 单元测试 17/17 通过
- `lintDebug`：0 errors / 8 warnings
- APK：versionCode 4，versionName `1.2.1-debug`，label `隐迹`
- v2 签名，包含 `arm64-v8a` 与 `x86_64` 的 `libtrustmark_jni.so`、`libonnxruntime.so`

### 8.3 重新编译 native

预编译库已放在：

```text
app/src/main/jniLibs/arm64-v8a/libtrustmark_jni.so
app/src/main/jniLibs/x86_64/libtrustmark_jni.so
```

重新生成：

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk-r27b
cargo install cargo-ndk
./scripts/build-native.sh
```

脚本会执行 `cargo ndk -t arm64-v8a -t x86_64 -P 26`，将两个 `.so` 直接写到 `jniLibs`。产物必须保持 16 KB page size 对齐。

当前预编译库：

| ABI | SHA-256 |
|---|---|
| arm64-v8a | `9e70ed8a548db812e0d09897940f998e384fdb869a3278ce253d62f114609ae5` |
| x86_64 | `93860ec54f8ec49cbc10285a625c330c09164707e64a74b78266f52d98ee6c44` |

### 8.4 模型

模型不默认打包进 APK。运行时 `ModelRepository` 从以下地址下载并做大小、SHA-256 校验：

```text
https://cai-watermark.adobe.net/watermarking/trustmark-models
```

| 文件 | 大小 | SHA-256 |
|---|---:|---|
| `encoder_Q.onnx` | 17,312,208 | `19b3d1b25836130ffd78775a8f61539f993375d1823ef0e59ba5b8dffb4f892d` |
| `decoder_Q.onnx` | 47,401,222 | `ee3268f057c9dabef680e169302f5973d0589feea86189ed229a896cc3aa88df` |

下载完成后保存在应用私有目录 `filesDir/models`。也可以把模型放到 `app/src/main/assets/models/` 作为离线兜底，或预下载：

```bash
python scripts/fetch-models.py --to-assets
./gradlew :app:assembleDebug
```

首次启动时会校验哈希；校验失败会重新下载。

---

## 9. 权限与存储

| 权限 / 能力 | 用途 |
|---|---|
| `INTERNET`、`ACCESS_NETWORK_STATE` | 下载 Q 模型 |
| `READ_MEDIA_IMAGES`（API 33+ 声明） | 兼容旧版选择器；主流程使用 Photo Picker |
| `READ_EXTERNAL_STORAGE`（maxSdk 32） | Android 12 及以下的相册读取兜底 |
| `WRITE_EXTERNAL_STORAGE`（maxSdk 28） | Android 8/9 旧版保存兜底 |
| `POST_NOTIFICATIONS`（API 33+） | 前台任务进度通知 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | WorkManager 前台执行 |

存储位置：

- 输出图片：MediaStore `Pictures/TrustMarkTool/`（API 29+）；Android 8/9 写入公共 Pictures 目录
- 模型：`filesDir/models`
- 批次映射：`filesDir/batch_store.enc`
- 解密结果：`filesDir/decode_results.json`
- 任务清单：`filesDir/task_plans/*.json`

---

## 10. 测试与验收

### 10.1 单元测试

```bash
./gradlew :app:testDebugUnitTest
```

当前 17 个测试，覆盖：

- 通用帧字母 / 字母数字往返
- 容量边界与超长拒绝
- 文本过滤与大写化
- 批次帧 4 字母短码 + 12 位编号
- CRC-7 校验值 `0x75` 与批次向量 `0x46`
- 非法批次码、编号 0 / 4096、非 BCH Super 拒绝
- v1.1 旧帧拒绝
- `_tm` / `_tm_2` 命名与中文文件名
- BCH 菜单顺序与文案

### 10.2 手工验收

**通用模式**

- 选 2 张图，生成 2 个文件，文件名带 `_tm`。
- 文本 `ACME` + BCH Super 可生成；12 个大写字母 + BCH 5 应被拒绝。
- 解码输出文本与输入一致。

**批次模式**

- 新建批次，填 3 个接收人；选 1 张图生成 3 个文件，编号 1/2/3。
- 逐张解密，命中对应接收人；黄色 / 红色状态不应误报为绿色。
- 同一张图生成两份时，第二份输出带 `_tm_2`。

**PNG**

- 带透明通道 PNG：生成成功，输出保留 alpha。
- 不带透明通道 PNG：生成成功。
- PNG + JPEG 混合批次：全部成功。
- 人为制造单张失败：其余继续，进度卡片列出失败文件与原因。

**进度 / 取消**

- 运行中显示转圈、当前文件、进度和取消按钮。
- 完成后不显示转圈或“正在处理中”，按钮恢复。
- 取消后显示“已取消”，通知消失，已完成文件保留。

**持久化**

- 修改固定文本 / 批次后杀掉进程再启动，生成页恢复上次值。

---

## 11. 已知限制

1. **无真机验收**：JNI、ONNX Runtime、模型推理、MediaStore 读写、PNG 透明通道均未在物理设备上跑过完整流程。
2. **release 签名未验证**：仓库 APK 为 debug 签名；正式分发需自备 keystore。
3. **批次导出为全量**：一次导出所有批次，不是只导出选中批次。
4. **映射表仅本机**：卸载、清数据或换机后丢失；必须定期导出 CSV/JSON。
5. **编号上限 4095**：批次帧只有 12 位编号。
6. **文本字符集受限**：不支持中文、emoji；仅 `A–Z`、`0–9`、空格、`- _ .`。中文文件名可以保留，但 payload 文本不行。
7. **旧帧不兼容**：v1.1 TMK2 图片无法按 v1.2 解码；v1.2 也不生成旧帧。
8. **模型首次下载**：约 62 MB，依赖网络；CDN 不可达时只能使用 assets 兜底。
9. **嵌入强度**：批次模式可调 0.50–2.00；通用模式固定 1.0。强度与画质、抗压缩性的平衡尚未做设备侧系统评测。

---

## 12. 文档

- [`docs/PAYLOAD_FORMAT.md`](docs/PAYLOAD_FORMAT.md)：payload 位布局、CRC、容量表、示例
- [`CHANGELOG.md`](CHANGELOG.md)：版本变更
- [`dist/README.txt`](dist/README.txt)：APK 交付说明
- [`dist/SHA256SUMS.txt`](dist/SHA256SUMS.txt)：APK 校验值

上游项目：[adobe/trustmark](https://github.com/adobe/trustmark)
