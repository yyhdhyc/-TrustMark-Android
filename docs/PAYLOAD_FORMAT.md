# TMK2 payload 位布局

本文件描述应用在 TrustMark 二进制 payload 内部定义的帧格式。它位于 BCH 纠错层**之内**，不是 TrustMark 的 100 位 packet 格式。

## 1. 两层结构

```text
用户文本 + 编号
      │  PayloadCodec (Kotlin)
      ▼
TMK2 frame（10 + 文本 + 编号 位，<= 40/61/68/75）
      │  trustmark::Bits::apply_error_correction_and_schema (Rust)
      ▼
100-bit packet = data(补零到容量) + BCH parity + schema(4)
      │  encoder_Q.onnx
      ▼
水印图片
```

解码时反向：

```text
100-bit decoder output
      │  BCH 校验/纠错，读取最后 2 位 schema
      ▼
data 位（40/61/68/75，尾部有零填充）
      │  PayloadCodec
      ▼
TMK2 frame → 文本 + 编号
```

## 2. TMK2 帧

| 字段 | 位宽 | 说明 |
| --- | ---: | --- |
| magic | 3 | 固定 `101` |
| alphabet | 1 | `0` = A–Z（5 位/字符）；`1` = 扩展字母表（6 位/字符） |
| serial width | 2 | `0` 无编号；`1` 8 位；`2` 16 位；`3` 32 位 |
| text length | 4 | 1–15 |
| text | L × 5/6 | 见字母表 |
| serial | 0/8/16/32 | 无符号大端位串（高位在前） |

固定头 = 3 + 1 + 2 + 4 = **10 位**。

### 5-bit 字母表

`A=0, B=1, ..., Z=25`

只有文本全部为 A–Z 时才使用。大小写会先统一转大写。

### 6-bit 字母表

```text
0..25   A..Z
26..35  0..9
36      -
37      _
38      .
39      (space)
```

出现数字或符号时使用。注意 6 位模式每字符多 1 位，容量会下降。

## 3. 容量公式

```text
total = 10 + L * bitsPerChar + serialWidth
valid = total <= ecc.dataBits
remaining = ecc.dataBits - total
```

| ECC | 容量 | 7 个 A–Z + 16 位 | 8 个 A–Z + 16 位 | 12 个 A–Z + 无编号 |
| --- | ---: | ---: | ---: | ---: |
| BCH_SUPER | 40 | 51 ✗ | 56 ✗ | 70 ✗ |
| BCH_5 | 61 | **61 ✓** | 66 ✗ | 70 ✗ |
| BCH_4 | 68 | 61 ✓ | 66 ✓ | 70 ✗ |
| BCH_3 | 75 | 61 ✓ | 66 ✓ | **70 ✓** |

> 表中的“7 个 A–Z + 16 位”是 10 + 35 + 16 = 61。UI 会根据当前输入实时计算，而不是使用固定上限。

## 4. 示例

### 4.1 `ABCDEFG`，编号 42，16 位，BCH_5

```text
magic       101
alphabet    0
serial w    10          (2 = 16-bit)
length      0111        (7)
text        A=00000 B=00001 C=00010 D=00011 E=00100 F=00101 G=00110
serial      0000000000101010  (42)
```

逻辑 payload 长度 61，刚好等于 BCH_5 的数据容量。native 补 BCH + schema 后为 100 位。

### 4.2 `CONFIDENTIAL`，无编号，BCH_3

- 全部 A–Z，使用 5 位模式
- 10 + 12×5 + 0 = 70 ≤ 75
- 可编码；剩余 5 位
- 若开启 8 位编号则 78 > 75，UI 会拒绝

### 4.3 `A1-B`，无编号

- 含数字/符号，使用 6 位模式
- 10 + 4×6 = 34 位

## 5. 解码与兼容性

- 只有 `bits.startsWith("101")` 且长度足够、字段自洽时才按 TMK2 解析。
- 文本长度必须为 1–15；字符 code 必须落在字母表内；serial 必须放得下。
- 任何字段异常都回退到原始 payload 展示，不会崩溃。
- 非 TMK2 payload 会尝试 7-bit / 8-bit ASCII，并标记为 `RAW_ASCII7` / `RAW_ASCII8`；否则标记 `RAW_BINARY`。

## 6. 为什么不用 TrustMark 的“文本模式”

Adobe 的 Rust crate 明确说明 text mode 未实现；Python 的 `encode_text_ascii` 使用 7-bit ASCII 且没有文本长度字段，解码时只能靠 `rstrip('\x00')`，无法可靠还原编号，也无法判断 padding。TMK2 在 BCH 预算内加入了长度和编号位宽，因此跨设备可逆。

## 7. 安全边界

TMK2 不是加密，也不是签名。它只是把文本和编号紧凑编码为位串。任何知道格式的人都能伪造同样格式的 payload；需要真实性/来源证明时，应结合 C2PA 或数字签名。
