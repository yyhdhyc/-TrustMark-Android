package com.trustmarktool.nativebridge

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File

/**
 * Thin JNI surface. The native library is built from `native/` and wraps
 * Adobe's TrustMark Rust crate. It deliberately contains no Android UI logic.
 */
object TrustMarkNative {

    @Volatile
    private var libraryError: Throwable? = null

    init {
        libraryError = runCatching {
            System.loadLibrary("trustmark_jni")
        }.exceptionOrNull()
    }

    val isLibraryLoaded: Boolean get() = libraryError == null

    val libraryErrorMessage: String?
        get() = libraryError?.let { it.message ?: it.javaClass.simpleName }

    /**
     * Loads ONNX Runtime from the official `onnxruntime-android` AAR, then asks
     * the Rust side to construct the encoder/decoder sessions.
     *
     * `ort` is compiled with `load-dynamic`, so there is no link-time dependency
     * on ORT. This is why the AAR version (1.22.0) and `ort` rc.10 must match.
     */
    fun initialize(context: Context, modelsDir: File) {
        libraryError?.let {
            throw IllegalStateException(
                "libtrustmark_jni.so 未加载。请先运行 scripts/build-native.ps1（或 " +
                    "-PbuildNative=true）生成 JNI 库。原始错误：${it.message}",
                it,
            )
        }

        // The AAR extracts libonnxruntime.so into nativeLibraryDir. Loading it
        // explicitly makes its dependency resolution deterministic on Android
        // 7–15 before Rust dlopen()s the absolute path.
        runCatching { System.loadLibrary("onnxruntime") }
            .onFailure { error ->
                throw IllegalStateException(
                    "无法加载 ONNX Runtime。请确认 onnxruntime-android:1.22.0 已打包且 ABI 匹配。",
                    error,
                )
            }

        val ortLibrary = File(context.applicationInfo.nativeLibraryDir, "libonnxruntime.so")
        if (!ortLibrary.isFile) {
            throw IllegalStateException("缺少 ${ortLibrary.absolutePath}，APK 可能只包含 32 位 ABI")
        }

        if (!nativeInitialize(ortLibrary.absolutePath, modelsDir.absolutePath, "Q")) {
            throw IllegalStateException("原生 TrustMark 初始化失败")
        }
    }

    fun encode(
        imageBytes: ByteArray,
        payloadBits: String,
        eccWireName: String,
        strength: Float,
        orientation: Int,
    ): ByteArray = nativeEncode(imageBytes, payloadBits, eccWireName, strength, orientation)

    fun decode(imageBytes: ByteArray, orientation: Int): NativeDecodeResult {
        val json = JSONObject(nativeDecode(imageBytes, orientation))
        if (!json.optBoolean("ok", false)) {
            throw IllegalStateException(json.optString("error", "解码失败"))
        }
        return NativeDecodeResult(
            payloadBits = json.optString("payload"),
            confidence = json.optDouble("confidence", 0.0).toFloat(),
            bitflips = json.optInt("bitflips", 0),
            version = json.optString("version"),
        )
    }

    fun abiInfo(): String = if (!isLibraryLoaded) {
        "JNI 未加载"
    } else {
        runCatching { "${Build.SUPPORTED_ABIS.joinToString()} / ${nativeVersion()}" }
            .getOrDefault(Build.SUPPORTED_ABIS.joinToString())
    }

    private external fun nativeInitialize(
        ortLibraryPath: String,
        modelsDir: String,
        variant: String,
    ): Boolean

    private external fun nativeEncode(
        imageBytes: ByteArray,
        payloadBits: String,
        eccWireName: String,
        strength: Float,
        orientation: Int,
    ): ByteArray

    private external fun nativeDecode(imageBytes: ByteArray, orientation: Int): String

    private external fun nativeVersion(): String
}

data class NativeDecodeResult(
    val payloadBits: String,
    val confidence: Float,
    val bitflips: Int,
    val version: String,
)
