# TrustMark Android 批量隐水印工具

基于 [Adobe TrustMark](https://github.com/adobe/trustmark) 的 Android 原生批量图片隐水印应用。Kotlin + Jetpack Compose (Material 3) 负责交互、任务调度和存储，Rust + JNI 封装 TrustMark 0.4.0 的编码/解码器，ONNX Runtime 使用官方 Android AAR。

> **部署方案：方案 A（已选定）**
> Rust 实现 + ONNX Runtime Android 版 + JNI。没有使用 TFLite，也没有把 ONNX 转成 TFLite，因此不存在转换后的精度损失问题。

## 当前交付状态

- Kotlin 编译通过（AGP 8.7.3 / Kotlin 2.0.21 / compileSdk 35）
- 单元测试 13 项全部通过（`PayloadCodecTest` 9 项 + `OutputNamingTest` 4 项）；`assembleDebug` 通过；`lintDebug` 0 error / 8 warning
- `libtrustmark_jni.so` 已为 `arm64-v8a` 和 `x86_64` 交叉编译，导出 4 个 JNI 符号
- 可安装 debug APK：`dist/TrustMarkTool-1.0.0-debug.apk`（37,213,739 字节，v2 签名，含两套 ABI 与 ORT）
  - SHA-256：`bba32957a284076d2d977b1b8d8f1ab0411ee0120124d6acfe713979235b4d1c`
- 本次批次/映射/溯源重构后**只重新验证了 debug 构建**；`assembleRelease` 未在重构后重新执行
- 未在物理手机上做端到端推理测试；首次启动需要联网下载模型，或按第 5.4 节离线打包
- 版本管理：Git `main` 分支，当前发布标签 `v1.0.0`；变更记录见 [CHANGELOG.md](CHANGELOG.md)
- 已验证的是编译、单元测试与 APK 打包；JNI、ONNX Runtime、模型推理和相册读写仍需真机验收

---

## 1. 先看三个硬约束

### 1.1 TrustMark 的 Rust API 只接受二进制 payload

`trustmark::Trustmark::encode` 的第二个参数是 `String`，但内容必须是 `0/1` 位串。Rust 实现**不支持文本模式**。本应用在 Kotlin 侧实现了“文本 + 编号 → 位串”的编码器，并在解码时反向解析。

### 1.2 可用容量不是 100 位

TrustMark 最终输入模型的是 100 位，但其中包含 BCH 校验位和 4 位 schema：

| 纠错等级 | 可用数据位 | 可纠错位数 | 最终 packet |
| --- | ---: | ---: | ---: |
| `BCH_SUPER` | 40 | 8 | 100 |
| `BCH_5`（默认） | 61 | 5 | 100 |
| `BCH_4` | 68 | 4 | 100 |
| `BCH_3` | 75 | 3 | 100 |

本应用不会把用户文本直接传给 native，而是先压进这个预算，再由 native 补齐 BCH 和 schema。

### 1.3 文本 + 编号必须共享预算

自定义帧的固定开销是 **10 位**：

```
magic(3) + 字母表模式(1) + 编号位宽(2) + 文本长度(4) = 10
```

- 纯大写文本：5 位/字符（A–Z）
- 含数字/符号：6 位/字符（`A–Z 0–9 - _ . 空格`）
- 编号：0 / 8 / 16 / 32 位可选

因此：

- `ABCDEFG` + 16 位编号 + `BCH_5` = 10 + 35 + 16 = **61 位**，刚好占满。
- `ABCDEFGH` + 16 位编号 + `BCH_5` = 66 位，**放不下**，UI 会直接禁止开始。
- `CONFIDENTIAL`（12 个字母）+ 无编号 + `BCH_3` = 70 位，**可以**。
- `CONFIDENTIAL` + 16 位编号 + `BCH_5` 不可能，任何实现都不应该声称可以。

生成页会实时显示“已用 / 容量 / 剩余可用标识符位数”，并在开始前做批次编号范围校验。

完整的位布局见 [`docs/PAYLOAD_FORMAT.md`](docs/PAYLOAD_FORMAT.md)。

---

## 2. 功能

### 生成水印

- 系统 Photo Picker 多选 JPEG/PNG，单批最多 50 张源图；**同一张源图可以重复选择**
- 自定义文本（A–Z、0–9、空格、`-`、`_`、`.`），自动转大写
- 每张源图独立编号：固定 `1..N`，不再使用起始值/步长；通用模式每源 1–99 份
- 每份副本都从原始 URI 重新读取像素再编码，绝不拿已加水印的 Bitmap 二次编码
- 纠错等级：`BCH_5` / `BCH_3` / `BCH_4` / `BCH_SUPER`；编号位宽 8/16/32 位
- 实时 payload 预算、二进制预览、最大字符数、最后编号容量校验
- 真实扁平任务列表：源图 × 份数，逐任务执行、失败继续、取消停止剩余任务
- WorkManager 前台任务 + 进度条 + 通知栏进度 + 取消（Running/Completed/Cancelled/Error 显式终态）
- 输出到 `Pictures/TrustMarkTool/`，文件名：
  `原文件名_tm_b{批次短码}_n{编号}.jpg/png`
- 通用模式自动建批次，短码为 `G` + 时间戳（如 `G2510221430`）
- JPEG 以 quality 95 重新编码；PNG 保留 alpha；EXIF 方向会先归一化

### 批次与映射表

- 「先生成后补表」和「先建表再生成」共用同一套批次模型
- 批次短码 `B` + 3 位字符；一个批次对应一次会议/一次分发
- 映射表：`batch_id / serial / recipient / note`
- 输出记录：`batch_id / source_image / serial / output_file / created_at`
- 使用映射表模式时，每张源图按全部接收人编号生成，并自动写输出记录
- 批次/映射/输出记录使用 Android Keystore AES-256-GCM 加密，仅存应用私有目录
- 支持导出 CSV / JSON（SAF），字段：
  `batch_id,batch_name,serial,recipient,note,output_file,created_at`
- 卸载应用会丢失本地映射表，UI 会提示定期导出备份

### 批量解密

- 多选疑似水印图片，运行 TrustMark Q 解码器
- 自动解析 BCH schema；返回 100 位中的有效 payload
- 反向解析自定义帧为文本 / 编号；非本工具 payload 会尽力按 7/8-bit ASCII 解释，否则保留原始二进制
- 结果卡片：文件名、文本、编号、ECC、BCH 修正位数、置信度、原始二进制
- 导出 CSV / JSON（系统“创建文档”SAF，无需存储权限）

### 溯源查询

- 对单张图片解码，优先从文件名解析 `b{批次短码}_n{编号}`
- 文件名没有短码时可手动指定本地批次
- 三种结果严格区分：
  1. 解码失败：`水印可能已被破坏或模型变体不匹配`
  2. 解码成功但本地无记录：`批次短码存在但本地无此批次记录` / `未找到对应批次记录`
  3. 命中：显示批次名、接收人、编号、创建时间、置信度

### 模型

- 不把 64.7 MB ONNX 模型提交进仓库
- 首次任务从 Adobe 官方 CDN 下载，使用固定 SHA-256 校验
- 可选把模型打进 `assets/models/`，完全离线
- Q 变体（Adobe 默认，PSNR 约 43–45）；模型文件名固定为 `encoder_Q.onnx` / `decoder_Q.onnx`

---

## 3. 架构

```text
Compose UI (MainActivity / MainScreen: 生成 / 解密 / 溯源)
  │
  ├─ MainViewModel
  │    ├─ PayloadCodec       文本/编号 <-> 自定义二进制帧 + 批次预检
  │    ├─ BatchStore         批次/映射/输出记录（Keystore AES-GCM）
  │    ├─ TaskPlanStore      扁平任务列表 JSON，交给 Worker
  │    ├─ WorkManager        EncodeWorker / DecodeWorker（前台服务 + 通知）
  │    ├─ ModelRepository    assets 解压 / HTTPS 下载 / SHA-256
  │    ├─ OutputNaming       输出名生成 + 批次/编号解析
  │    └─ ImageStore         MediaStore / 旧版外部存储
  │
  └─ TrustMarkEngine (Kotlin Mutex)
       └─ TrustMarkNative (JNI)
            └─ libtrustmark_jni.so
                 ├─ vendored TrustMark 0.4.0（Rust）
                 │    ├─ encoder_Q.onnx
                 │    └─ decoder_Q.onnx
                 └─ ort 2.0.0-rc.10 (load-dynamic)
                      └─ libonnxruntime.so (onnxruntime-android:1.22.0 AAR)
```

### 为什么用 `load-dynamic`

TrustMark 0.4.0 固定 `ort = 2.0.0-rc.10`，而该版本没有 Android 预编译 dist。应用改为：

1. Gradle 引入官方 `com.microsoft.onnxruntime:onnxruntime-android:1.22.0`；
2. Rust 的 `ort` 打开 `load-dynamic`，编译期不链接 ORT；
3. 运行时 Kotlin 先 `System.loadLibrary("onnxruntime")`，再把 `nativeLibraryDir/libonnxruntime.so` 绝对路径传给 Rust；
4. Rust 调用 `ort::init_from(path).commit()`，随后创建 TrustMark session。

ORT 1.22.0 的 C API version 是 22，正好匹配 `ort` rc.10 的 `ORT_API_VERSION = 22`。**不要随意升级 AAR 或 `ort`**，否则可能出现 `The requested API version [22] is not available`。

### 原生库做了什么

- `nativeInitialize(ortPath, modelsDir, "Q")`
  - 只初始化一次 ORT 和 TrustMark 引擎
  - 加载 encoder/decoder 两个 session
- `nativeEncode(jpegOrPngBytes, bits, ecc, strength, orientation)`
  - 解码图片 → 应用 EXIF 方向 → `encode_with_version` → 按原格式输出
  - 使用打了本地补丁的 `encode_with_version`，同一个引擎可切换四种 BCH，避免加载四套模型
- `nativeDecode(jpegOrPngBytes, orientation)`
  - 解码 → 方向归一化 → `decode_with_confidence`
  - 返回 JSON：payload、置信度、BCH 修正位数、schema
- 所有 JNI 导出都包了 `catch_unwind`，避免 Rust panic 穿过 FFI

> 置信度不是密码学概率。它由 ONNX 软判决 margin（30%）和 BCH 修正位数（70%）组合，用于排序/提示，不应作为法律取证结论。

---

## 4. 目录

```text
.
├─ app/
│  ├─ build.gradle.kts
│  ├─ proguard-rules.pro
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ assets/models/README.txt       # 可选放 *.onnx
│     ├─ jniLibs/                       # cargo-ndk 生成的 .so
│     ├─ java/com/trustmarktool/
│     │  ├─ MainActivity.kt
│     │  ├─ MainViewModel.kt
│     │  ├─ model/
│     │  │  ├─ PayloadCodec.kt          # 文本→二进制编码器（重点）
│     │  │  ├─ Batch.kt / MappingEntry.kt / OutputRecord.kt
│     │  │  ├─ GenerateTask.kt          # 扁平任务模型
│     │  │  └─ UiModels.kt              # 显式任务状态机
│     │  ├─ nativebridge/               # JNI 桥
│     │  ├─ data/
│     │  │  ├─ BatchStore.kt            # 加密批次/映射/输出记录
│     │  │  ├─ TaskPlanStore.kt         # 任务列表落盘
│     │  │  ├─ OutputNaming.kt          # _tm_b.._n.. 命名/解析
│     │  │  └─ ImageStore.kt / ModelRepository.kt / ...
│     │  ├─ worker/                     # WorkManager + 前台通知
│     │  └─ ui/                         # Compose MD3（生成/解密/溯源）
│     └─ res/
├─ native/
│  ├─ Cargo.toml                        # trustmark_jni (cdylib)
│  ├─ src/lib.rs                        # JNI 导出
│  ├─ .cargo/config.toml                # 16 KB page size
│  ├─ rust-toolchain.toml
│  └─ vendor/trustmark/                 # Adobe TrustMark 0.4.0 MIT 源码 + 本地补丁
├─ scripts/
│  ├─ build-native.ps1 / .sh
│  └─ fetch-models.py
├─ docs/PAYLOAD_FORMAT.md
├─ gradle/wrapper/
└─ README.md
```

---

## 5. 构建

### 5.1 版本矩阵

| 组件 | 版本 |
| --- | --- |
| JDK | 17（AGP 8.7.3 要求） |
| Android Gradle Plugin | 8.7.3 |
| Gradle | 8.9（wrapper 已包含） |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |
| NDK | 27.0.12077973 |
| Rust | 1.88.0 |
| cargo-ndk | 最新稳定版 |
| ONNX Runtime AAR | 1.22.0 |
| ort | 2.0.0-rc.10 |
| TrustMark | 0.4.0 |

ABI：`arm64-v8a`、`x86_64`（模拟器）。**不包含 armeabi-v7a**，因为现代 TrustMark 模型 + ORT 在 32 位地址空间里很容易 OOM。

### 5.2 安装工具链

```bash
# JDK 17、Android Studio、SDK Platform 35、NDK 27.0.12077973
rustup toolchain install 1.88.0
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk
```

Windows 设置 NDK：

```powershell
$env:ANDROID_NDK_HOME = "$env:LOCALAPPDATA\Android\Sdk\ndk\27.0.12077973"
```

或在 `local.properties` 中写：

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

脚本会自动从 `sdk.dir` 找 `ndk/27.0.12077973`。

### 5.3 编译 Rust JNI

```powershell
# Windows
.\scripts\build-native.ps1
```

```bash
# macOS / Linux
./scripts/build-native.sh
```

等价命令：

```bash
cd native
cargo ndk -t arm64-v8a -t x86_64 -P 26 \
  -o ../app/src/main/jniLibs build --release
```

产物：

```text
app/src/main/jniLibs/arm64-v8a/libtrustmark_jni.so
app/src/main/jniLibs/x86_64/libtrustmark_jni.so
```

也可以用 Gradle 一步完成：

```bash
./gradlew :app:assembleDebug -PbuildNative=true
```

`-PbuildNative=true` 会让 `preBuild` 依赖 `cargoNdk`；不传时使用已存在的 `.so`。

### 5.4 模型

#### 方式 A：首次运行下载（默认）

APK 内不含模型。第一次生成/解密时，`ModelRepository` 从

```text
https://cai-watermark.adobe.net/watermarking/trustmark-models/
```

下载并校验：

| 文件 | 大小 | SHA-256 |
| --- | ---: | --- |
| `encoder_Q.onnx` | 17,312,208 | `19b3d1b25836130ffd78775a8f61539f993375d1823ef0e59ba5b8dffb4f892d` |
| `decoder_Q.onnx` | 47,401,222 | `ee3268f057c9dabef680e169302f5973d0589feea86189ed229a896cc3aa88df` |

下载写入 `filesDir/models/*.part`，校验大小和 SHA-256 后才原子替换。失败不会留下半文件。

#### 方式 B：离线打包进 APK

```bash
python scripts/fetch-models.py --to-assets
python scripts/fetch-models.py --verify-only
./gradlew :app:assembleDebug
```

这会把两个 `.onnx` 打到 `app/src/main/assets/models/`，APK 增加约 62 MB；首次任务时复制到 `filesDir/models` 并校验。

> 模型由 Adobe 以 MIT License 分发，但不在 TrustMark git 仓库中。请遵守 Adobe 的模型许可与出口/使用限制。

### 5.5 构建 APK

```bash
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

本仓库还提供了已经构建、带 JNI + ORT 的 debug APK：

```text
dist/TrustMarkTool-1.0.0-debug.apk
dist/SHA256SUMS.txt
```

当前 APK 为 37,213,739 字节，SHA-256
`bba32957a284076d2d977b1b8d8f1ab0411ee0120124d6acfe713979235b4d1c`。
它使用 Android debug 证书（包名 `com.trustmarktool.debug`），可直接安装测试，但不要用于正式发布。

安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release：

```bash
./gradlew :app:assembleRelease
# 配置签名后：app/build/outputs/apk/release/app-release.apk
# 未配置签名：app/build/outputs/apk/release/app-release-unsigned.apk
```

---

## 6. 签名

`app/build.gradle.kts` 支持 `keystore.properties` 或环境变量。没有配置时 release 产物是 **unsigned**，不能直接安装。

`keystore.properties`（已在 `.gitignore`）：

```properties
storeFile=keystore/trustmark-release.jks
storePassword=change-me
keyAlias=trustmark
keyPassword=change-me
```

生成 keystore：

```bash
keytool -genkeypair -v \
  -keystore keystore/trustmark-release.jks \
  -alias trustmark \
  -keyalg RSA -keysize 4096 -validity 10000
```

或使用环境变量：

```bash
export TRUSTMARK_KEYSTORE=/absolute/path/trustmark-release.jks
export TRUSTMARK_KEYSTORE_PASSWORD=...
export TRUSTMARK_KEY_ALIAS=trustmark
export TRUSTMARK_KEY_PASSWORD=...
./gradlew :app:assembleRelease
```

校验：

```bash
apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
```

> 正式发布前请把 `applicationId` 从 `com.trustmarktool` 改成自己的域名，并更新 `namespace`（同时更新 JNI 函数名，见 `native/src/lib.rs`）。

---

## 7. 权限与存储

| 权限 | 用途 |
| --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | 首次下载模型 |
| `READ_MEDIA_IMAGES` (33+) / `READ_EXTERNAL_STORAGE` (≤32) | 声明用于旧版选择器回退；实际优先使用系统 Photo Picker，按 URI 授权 |
| `WRITE_EXTERNAL_STORAGE` (≤28) | 旧系统写入 `Pictures/TrustMarkTool/` |
| `POST_NOTIFICATIONS` (33+) | 前台任务进度通知；拒绝不影响任务执行 |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | WorkManager 前台服务 |

Android 10+ 通过 MediaStore 写入，无需存储权限。Android 8/9 首次生成会请求写权限。

输出路径：

```text
Pictures/TrustMarkTool/<原名>_tm_b<批次短码>_n<编号>.jpg
Pictures/TrustMarkTool/<原名>_tm_b<批次短码>_n<编号>.png
```

批次短码是 4 位（`B` + 3 位字符），通用模式为 `G` + 时间戳。文件名保留 `_tm` 标记，便于相册识别和溯源解析。同名时 MediaStore/文件系统会追加 `_2`、`_3`，不会覆盖原图。

批次、映射和输出记录存放在：

```text
filesDir/batch_store.enc      # TMB1 + AES-256-GCM，密钥在 Android Keystore
filesDir/task_plans/*.json    # Worker 的扁平任务列表，完成后删除
```

映射表**不写入外部存储**，也不会明文落盘；卸载应用会销毁 Keystore 密钥，因此本地映射表不可恢复。

---

## 8. 关键实现说明

### 8.1 文本 → 二进制

`app/src/main/java/com/trustmarktool/model/PayloadCodec.kt` 顶部有完整注释。核心流程：

1. 过滤/大写/压缩空格；
2. 纯 A–Z 用 5 位/字符，否则用 6 位/字符；
3. 写 10 位帧头；
4. 校验 `used <= ecc.dataBits`；
5. 返回位串给 JNI；
6. JNI/TrustMark 补齐 BCH + schema → 100 位。

### 8.2 编号位宽与批次校验

`SerialWidth` 定义 8/16/32 位及最大值。每张源图独立编号，固定从 `1` 开始、步长 `1`，因此不需要再计算 `start + step * index`，也避免了乘法溢出。

开始前会在 Kotlin 侧完成两层检查：

1. `PayloadCodec.plan(lastSerial)` 校验文本 + 帧头 + 最后编号不超过当前 ECC 的数据容量；
2. `PayloadCodec.validateBatch(tasks)` 逐任务校验：
   - 非空任务列表
   - 已知 ECC
   - `serial > 0`
   - payload 非空且不超过 `ecc.dataBits`
   - payload 是合法 TMK2 帧
   - payload 内编号与任务编号一致

任何一条失败都会阻止整批入队，并显示具体原因；Worker 侧仍会防御性校验任务字段，避免外部构造 WorkRequest 绕过 UI。

### 8.3 为什么解码文本能还原

自定义帧是自描述的：magic、字母表模式、编号位宽、文本长度都在 payload 里。解码时先读帧头，再按长度取文本和编号。TrustMark 的 4 个 schema 位由 native 自动识别，Kotlin 不需要猜 BCH 等级。

### 8.4 非本工具 payload

如果 magic 不匹配，解码结果会标记为 `RAW_ASCII7` / `RAW_ASCII8` / `RAW_BINARY`，并保留完整二进制。不会强行把别人的 TrustMark payload 解释成本工具的编号。

---

## 9. 测试

```bash
./gradlew :app:testDebugUnitTest
```

`PayloadCodecTest` 覆盖：

- `BCH_5` + 7 个大写字母 + 16 位编号刚好 61 位
- `BCH_5` + 8 个大写字母 + 16 位编号必须失败
- `BCH_3` + 10 个大写字母 + 8 位编号
- `CONFIDENTIAL` + `BCH_3` 无编号
- 6 位字母表模式
- 8/16/32 位编号范围与溢出
- 原始 7-bit ASCII 回退

`OutputNamingTest` 覆盖中文原文件名保留、非法字符替换、MediaStore 冲突后缀 `_2` 的批次/编号解析、非法编号拒绝。

原生侧目前没有设备级自动化测试；建议至少做一次：

1. 生成 → 重新导入输出图 → 解密；
2. 微信/相册压缩后解密（验证鲁棒性）；
3. 旋转 90°/180° 后解密；
4. PNG alpha 图生成后检查 alpha；
5. 长宽比 >2:1 的图（TrustMark 会中心裁剪）。

---

## 10. 常见问题

### `UnsatisfiedLinkError: libtrustmark_jni.so`

没有编译 native：

```bash
./scripts/build-native.sh
# 或
./gradlew :app:assembleDebug -PbuildNative=true
```

确认 APK 内存在 `lib/arm64-v8a/libtrustmark_jni.so`。模拟器用 x86_64，真机用 arm64-v8a。

### `dlopen failed: library "libonnxruntime.so" not found`

- 确认依赖 `onnxruntime-android:1.22.0` 没有被 `exclude`；
- 确认 ABI 过滤没有把 ORT 的 ABI 排除；
- 不要同时引入 `onnxruntime-mobile`，会重名冲突。

### `The requested API version [22] is not available`

`ort` 与 ORT AAR 版本不匹配。保持：

```text
ort 2.0.0-rc.10  ↔  onnxruntime-android 1.22.0  ↔  ORT API 22
```

### 首次任务卡在“下载模型”

- 检查网络是否能访问 `cai-watermark.adobe.net`；
- 失败重试会重新下载并校验，不会加载 `.part`；
- 可改用 `--to-assets` 离线打包。

### `OutOfMemoryError` / 系统杀进程

- Q decoder 约 45 MB，运行时内存远高于文件大小；低内存机请一次少选几张；
- 已开启 `largeHeap`，但仍建议单批 ≤20 张；
- 不要并发运行生成和解密（`TrustMarkEngine` 已串行化）。

### Android 15 / 16 KB page size

`native/.cargo/config.toml` 已加：

```text
-C link-arg=-Wl,-z,max-page-size=16384
```

如果厂商要求 16 KB 对齐，请用 NDK r27+ 重新编译，并检查 ORT AAR 是否支持 16 KB。

### 通知不显示

Android 13+ 需要 `POST_NOTIFICATIONS`。拒绝后任务仍会执行，只是通知栏不可见；可到系统设置手动开启。

### 输出目录里没有文件

- Android 10+：`Pictures/TrustMarkTool/`，用相册/文件管理器查看；
- Android 8/9：需要写权限；
- 失败详情写在 `filesDir/last_encode_failures.txt`（可通过应用信息/调试查看）。

---

## 11. 已知限制

- 只集成 Q 变体；B/C/P 可通过扩展 `ModelRepository` 和 JNI variant 参数加入，但模型体积/内存显著增加。
- 自定义文本上限 15 字符，实际可用字符数由 ECC + 编号位宽共同决定，UI 会实时限制。
- 不支持下划线以外的 Unicode、中文、emoji；这些字符无法在 5/6 位紧凑字母表中表达。
- 置信度是启发式分数，不是校准概率。
- 不支持 TrustMark 的去水印（remover）和检测框（bbox）模型。
- EXIF 的镜像方向（2/4/5/7）采用近似矩阵变换；极端镜像 JPEG 建议先在相册里另存。
- 输出会重新编码（JPEG q95 / PNG lossless），不保留原始 EXIF/XMP/C2PA 元数据；如需 C2PA，请在输出后另行注入 manifest。

---

## 12. 许可

- 本应用代码：MIT（见 `LICENSE`）。
- `native/vendor/trustmark`：Adobe TrustMark MIT，见 `native/vendor/trustmark/LICENSE`；本地修改已在源码注释中标注。
- ONNX Runtime：MIT。
- `ort` / `jni-rs`：MIT / Apache-2.0。
- ONNX 模型：Adobe 以 MIT 分发，但文件不随本仓库提供；下载/使用请遵守 Adobe 的模型条款。
- 完整第三方清单见 `THIRD_PARTY_NOTICES.md`。
