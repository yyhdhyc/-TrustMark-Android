//! JNI bridge for the Android TrustMark tool.
//!
//! The Rust side owns the ONNX sessions and all pixel processing. Kotlin only
//! supplies compressed input bytes + a binary payload and receives compressed
//! output bytes / a small JSON decode result.

use std::{
    io::Cursor,
    panic::{catch_unwind, AssertUnwindSafe},
    str::FromStr,
    sync::{Mutex, OnceLock},
};

use image::{codecs::jpeg::JpegEncoder, DynamicImage, ImageFormat};
use jni::{
    objects::{JByteArray, JObject, JString},
    sys::{jboolean, jbyteArray, jstring, JNI_FALSE, JNI_TRUE},
    JNIEnv,
};
use trustmark::{Trustmark, Variant, Version};

static ENGINE: OnceLock<Trustmark> = OnceLock::new();
static INIT_LOCK: Mutex<()> = Mutex::new(());

fn jstring_to_string(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Result<String, String> {
    env.get_string(value)
        .map(|java_string| java_string.into())
        .map_err(|error| format!("读取 JNI 字符串失败: {error}"))
}

fn throw(env: &mut JNIEnv<'_>, message: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", message);
}

fn json_escape(value: &str) -> String {
    let mut output = String::with_capacity(value.len() + 8);
    for ch in value.chars() {
        match ch {
            '"' => output.push_str("\\\""),
            '\\' => output.push_str("\\\\"),
            '\n' => output.push_str("\\n"),
            '\r' => output.push_str("\\r"),
            '\t' => output.push_str("\\t"),
            c if c.is_control() => output.push_str(&format!("\\u{:04x}", c as u32)),
            c => output.push(c),
        }
    }
    output
}

fn error_json(message: &str) -> String {
    format!("{{\"ok\":false,\"error\":\"{}\"}}", json_escape(message))
}

fn apply_orientation(image: DynamicImage, orientation: i32) -> DynamicImage {
    // EXIF orientation values 2..8. Values 5 and 7 include a mirror.
    match orientation {
        2 => image.fliph(),
        3 => image.rotate180(),
        4 => image.flipv(),
        5 => image.rotate90().fliph(),
        6 => image.rotate90(),
        7 => image.rotate270().fliph(),
        8 => image.rotate270(),
        _ => image,
    }
}

fn encode_output(image: DynamicImage, original: &[u8]) -> Result<Vec<u8>, String> {
    let is_png = original.starts_with(b"\x89PNG\r\n\x1a\n");
    let is_jpeg = original.starts_with(&[0xFF, 0xD8]);

    if is_png || !is_jpeg {
        let mut cursor = Cursor::new(Vec::new());
        image
            .write_to(&mut cursor, ImageFormat::Png)
            .map_err(|error| format!("PNG 编码失败: {error}"))?;
        return Ok(cursor.into_inner());
    }

    // JPEG has no alpha. Quality 95 keeps the watermark robust while avoiding
    // an unnecessarily huge output file.
    let rgb = DynamicImage::ImageRgb8(image.to_rgb8());
    let mut cursor = Cursor::new(Vec::new());
    let mut encoder = JpegEncoder::new_with_quality(&mut cursor, 95);
    encoder
        .encode_image(&rgb)
        .map_err(|error| format!("JPEG 编码失败: {error}"))?;
    Ok(cursor.into_inner())
}

#[no_mangle]
pub extern "system" fn Java_com_trustmarktool_nativebridge_TrustMarkNative_nativeInitialize(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    ort_library_path: JString<'_>,
    models_dir: JString<'_>,
    variant: JString<'_>,
) -> jboolean {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<(), String> {
        let ort_path = jstring_to_string(&mut env, &ort_library_path)?;
        let models_path = jstring_to_string(&mut env, &models_dir)?;
        let variant_string = jstring_to_string(&mut env, &variant)?;
        let variant = Variant::from_str(&variant_string)
            .map_err(|_| format!("未知模型变体: {variant_string}"))?;

        let _guard = INIT_LOCK
            .lock()
            .map_err(|_| "原生初始化锁已损坏".to_string())?;
        if ENGINE.get().is_some() {
            return Ok(());
        }

        // Must run before any other ort API. `load-dynamic` means this is the
        // only place that knows where the AAR extracted libonnxruntime.so.
        ort::init_from(&ort_path)
            .with_name("trustmark_android")
            .with_telemetry(false)
            .commit()
            .map_err(|error| format!("加载 ONNX Runtime 失败: {error}"))?;

        let trustmark = Trustmark::new(&models_path, variant, Version::Bch5)
            .map_err(|error| format!("加载 TrustMark 模型失败: {error}"))?;
        ENGINE
            .set(trustmark)
            .map_err(|_| "TrustMark 引擎已初始化".to_string())?;
        Ok(())
    }));

    match result {
        Ok(Ok(())) => JNI_TRUE,
        Ok(Err(error)) => {
            throw(&mut env, &error);
            JNI_FALSE
        }
        Err(_) => {
            throw(&mut env, "原生初始化发生 panic");
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_trustmarktool_nativebridge_TrustMarkNative_nativeEncode(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    image_bytes: JByteArray<'_>,
    payload_bits: JString<'_>,
    ecc_wire_name: JString<'_>,
    strength: f32,
    orientation: i32,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<Vec<u8>, String> {
        let engine = ENGINE
            .get()
            .ok_or_else(|| "TrustMark 引擎尚未初始化".to_string())?;
        let bytes = env
            .convert_byte_array(image_bytes)
            .map_err(|error| format!("读取图片字节失败: {error}"))?;
        if bytes.is_empty() {
            return Err("图片字节为空".to_string());
        }

        let payload = jstring_to_string(&mut env, &payload_bits)?;
        if payload.is_empty() || payload.chars().any(|ch| ch != '0' && ch != '1') {
            return Err("payload 必须是 0/1 位串".to_string());
        }

        let version_name = jstring_to_string(&mut env, &ecc_wire_name)?;
        let version = Version::from_str(&version_name)
            .map_err(|_| format!("未知 BCH 等级: {version_name}"))?;

        let image = image::load_from_memory(&bytes)
            .map_err(|error| format!("无法解码输入图片（仅支持 JPEG/PNG）: {error}"))?;
        let image = apply_orientation(image, orientation);

        let watermarked = engine
            .encode_with_version(payload, image, strength.clamp(0.0, 1.0), version)
            .map_err(|error| format!("TrustMark 编码失败: {error}"))?;

        encode_output(watermarked, &bytes)
    }));

    match result {
        Ok(Ok(bytes)) => match env.byte_array_from_slice(&bytes) {
            Ok(array) => array.into_raw(),
            Err(error) => {
                throw(&mut env, &format!("创建输出字节数组失败: {error}"));
                std::ptr::null_mut()
            }
        },
        Ok(Err(error)) => {
            throw(&mut env, &error);
            std::ptr::null_mut()
        }
        Err(_) => {
            throw(&mut env, "编码过程中发生 panic");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_trustmarktool_nativebridge_TrustMarkNative_nativeDecode(
    env: JNIEnv<'_>,
    _this: JObject<'_>,
    image_bytes: JByteArray<'_>,
    orientation: i32,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<String, String> {
        let engine = ENGINE
            .get()
            .ok_or_else(|| "TrustMark 引擎尚未初始化".to_string())?;
        let bytes = env
            .convert_byte_array(image_bytes)
            .map_err(|error| format!("读取图片字节失败: {error}"))?;
        if bytes.is_empty() {
            return Err("图片字节为空".to_string());
        }

        let image = image::load_from_memory(&bytes)
            .map_err(|error| format!("无法解码输入图片（仅支持 JPEG/PNG）: {error}"))?;
        let image = apply_orientation(image, orientation);

        let decoded = engine
            .decode_with_confidence(image)
            .map_err(|error| format!("TrustMark 解码失败: {error}"))?;

        Ok(format!(
            "{{\"ok\":true,\"payload\":\"{}\",\"confidence\":{:.6},\"bitflips\":{},\"version\":\"{}\"}}",
            decoded.payload,
            decoded.confidence,
            decoded.bitflips,
            decoded.version,
        ))
    }));

    let json = match result {
        Ok(Ok(json)) => json,
        Ok(Err(error)) => error_json(&error),
        Err(_) => error_json("解码过程中发生 panic"),
    };

    match env.new_string(json) {
        Ok(string) => string.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_trustmarktool_nativebridge_TrustMarkNative_nativeVersion(
    env: JNIEnv<'_>,
    _this: JObject<'_>,
) -> jstring {
    let value = format!(
        "trustmark {} / ort {}",
        env!("CARGO_PKG_VERSION"),
        "2.0.0-rc.10"
    );
    match env.new_string(value) {
        Ok(string) => string.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
