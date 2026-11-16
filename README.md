# 隐迹 · TrustMark Android

在 Android 手机上批量写入、批量读取 Adobe TrustMark 隐水印。基于 Rust 版 TrustMark + ONNX Runtime,通过 JNI 在端侧完成推理;图片、模型与批次映射全部保留在本机,不上传任何数据。

- **应用名**:隐迹
- **包名**:`com.trustmarktool`
- **支持系统**:Android 8.0+(API 26)
- **ABI**:`arm64-v8a`、`x86_64`
- **上游项目**:[adobe/trustmark](https://github.com/adobe/trustmark)

---

## ✨ 功能

| 功能 | 说明 |
|---|---|
| 通用模式 | 将固定文本写入每张图片,不携带编号;适合 `CONFIDENTIAL`、`ACME 2026` 这类统一标记 |
| 批次模式 | 批次短码 + 接收人编号,逐人生成独立副本;适合会议材料分发与泄露溯源 |
| 批量解密 | 自动识别通用帧 / 批次帧,批量提取文本或批次编号 |
| 本地映射 | 批次、接收人、输出记录以 AES-256-GCM 加密保存在应用私有目录 |
| 批量导出 | 解密结果与批次映射均可导出 CSV / JSON |
| 进度与取消 | 前台通知 + 应用内进度卡片;单张失败不中断整批,卡片列出失败原因 |

---

## 📥 下载安装

前往 [Releases](https://github.com/yyhdhyc/-TrustMark-Android/releases/latest) 下载 APK:

| 文件 | 说明 | SHA-256 |
|---|---|---|
| `TrustMarkTool-1.2.2-release.apk` | 正式签名,推荐使用 | `D00ED802DD74D174FCE9DBE1559581BB8DA306CDC8144690EF7DB0524128E371` |
| `TrustMarkTool-1.2.2-debug.apk` | 调试签名,仅供测试 | `E32AB9CADA1B5485A45991CE2081BB5117A9319C104F087A3FFE4980FD74C428` |

两个包名不同,可在同一设备共存。

> ⚠️ 首次使用会从 Adobe 官方 CDN 下载 Q 模型(约 62 MB)并校验 SHA-256,需要联网;下载完成后可完全离线使用。

---

## 🚀 使用方法

### 通用模式:统一文本

1. 打开 **生成**,模式选择 **通用**。
2. 从相册选择一张或多张 JPEG / PNG。
3. 输入固定文本:仅支持 `A–Z`、`0–9`、空格和 `- _ .`,自动转大写,最长 15 字符。
4. 选择 BCH 级别(默认 **BCH 5**),按需调整嵌入强度(0.50–2.00,默认 1.30)。
5. 开始生成,每张源图输出 1 个文件。

### 批次模式:逐人编号

1. 在 **生成 → 批次与映射** 中新建批次,填写接收人列表(编号 1、2、3……)。
2. 回到生成页,模式选择 **批次**,选择源图与批次。
3. 每张源图按批次内全部接收人各生成一份副本,编号逐人递增。
4. 批次模式固定使用 BCH Super,以容纳批次短码、编号与校验位。

示例:1 张源图 + 3 个接收人 → 3 个输出文件,编号 1、2、3;2 张源图 + 3 个接收人 → 6 个文件,每张源图都从编号 1 开始。

### 解密与溯源

1. 打开 **解密**,选择一张或多张可疑图片。
2. 应用自动识别帧类型:通用帧直接显示文本;批次帧校验 CRC-7 后读取批次短码与编号,并自动查询本地映射表。
3. 结果分三色显示:命中接收人(绿)、批次存在但无该编号(黄)、帧损坏或 CRC 失败(红)。
4. 结果可导出 CSV / JSON。

> 解码溯源依赖本机的加密映射表。换机、清除数据或卸载应用后映射表不会自动恢复,请定期导出备份。

---

## 🔍 水印格式简介

TrustMark 原生只接受 100 位 0/1 payload。应用在 Kotlin 层构造数据位,native 层补齐 BCH 校验位:

- **通用帧**:帧类型 `0` + 字母表模式 + 4 位文本长度 + 字符,可配 BCH 3 / 4 / 5 / Super
- **批次帧**:帧类型 `1` + 4 字母批次短码 + 12 位编号(1–4095)+ CRC-7,固定 40 位,BCH Super

完整位布局、容量表、CRC-7/MMC 参数与示例见 [`docs/PAYLOAD_FORMAT.md`](docs/PAYLOAD_FORMAT.md)。

> v1.2 帧格式与 v1.1 的 TMK2 帧不兼容:旧版生成的图片无法按新规则解码。

---

## 📁 输出与数据存储

| 内容 | 位置 | 说明 |
|---|---|---|
| 水印图片 | `Pictures/TrustMarkTool/` | 命名 `原文件名_tm.ext`,冲突时追加 `_2`、`_3`… |
| 模型 | 应用私有 `filesDir/models` | 运行时下载,校验通过后离线可用 |
| 批次映射 | 应用私有 `filesDir/batch_store.enc` | AES-256-GCM(Android Keystore),卸载即丢失 |
| 解密结果 | 应用私有 `filesDir/decode_results.json` | 可随时导出 |

每份输出都重新读取原始图片解码,绝不在已编码的图上二次编码;PNG 透明通道保留。

---

## 🛠 从源码构建

### 环境要求

- JDK 21
- Android SDK Platform 35 + Build Tools 35.0.0(在 `local.properties` 配置 `sdk.dir`)
- Gradle Wrapper 已附带(Gradle 8.9),无需单独安装

### 编译 APK

```bash
./gradlew :app:assembleDebug        # 产物: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # 单元测试
./gradlew :app:lintDebug            # Lint 检查
```

### 编译 native 库(可选)

预编译的 `libtrustmark_jni.so`(arm64-v8a / x86_64)已随源码入库,仅在修改 Rust 代码时需要重新编译:

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk-r27b
cargo install cargo-ndk
./scripts/build-native.sh
```

### 模型

模型不打包进 APK,运行时从官方 CDN 下载并校验:

| 文件 | 大小 | SHA-256 |
|---|---:|---|
| `encoder_Q.onnx` | 17,312,208 | `19b3d1b25836130ffd78775a8f61539f993375d1823ef0e59ba5b8dffb4f892d` |
| `decoder_Q.onnx` | 47,401,222 | `ee3268f057c9dabef680e169302f5973d0589feea86189ed229a896cc3aa88df` |

如需离线兜底,可将模型放入 `app/src/main/assets/models/` 后再打包:

```bash
python scripts/fetch-models.py --to-assets
./gradlew :app:assembleDebug
```

### 签名

Release 签名读取 `keystore.properties`(可选 `TRUSTMARK_*` 环境变量)中配置的密钥库路径与密码;未配置时回退到 debug 签名。签名密钥不入库,请自行妥善保管。

---

## ⚠️ 使用须知

1. **文本字符集受限**:水印文本不支持中文与 emoji(仅 `A–Z`、`0–9`、空格、`- _ .`);图片文件名可保留中文。
2. **批次编号上限 4095**:批次帧编号为 12 位。
3. **映射表仅存本机**:卸载、清数据或换机后丢失,务必定期导出 CSV / JSON 备份。
4. **嵌入强度权衡**:强度越高越抗压缩,但视觉扰动越明显;默认 1.30 适合大多数场景。
5. **水印不是加密**:隐水印用于来源标识与溯源,不能替代加密与访问控制。

---

## 📚 更多文档

- [`docs/PAYLOAD_FORMAT.md`](docs/PAYLOAD_FORMAT.md) — payload 位布局、CRC 参数、容量表
- [`CHANGELOG.md`](CHANGELOG.md) — 版本变更记录

## 致谢

水印算法与模型来自 Adobe 开源的 [TrustMark](https://github.com/adobe/trustmark),本项目为其 Android 端侧实现。
