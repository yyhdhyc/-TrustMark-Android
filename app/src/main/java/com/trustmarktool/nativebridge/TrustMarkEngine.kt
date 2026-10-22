package com.trustmarktool.nativebridge

import android.content.Context
import com.trustmarktool.data.ModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serializes native calls because one mobile GPU/CPU should not run two 45 MB
 * decoder sessions concurrently, and because ONNX Runtime session creation is
 * expensive. Encoding and decoding remain separate sessions on the Rust side.
 */
object TrustMarkEngine {

    private val mutex = Mutex()
    private var initialized = false

    suspend fun ensureInitialized(context: Context) = mutex.withLock {
        ensureInitializedLocked(context)
    }

    suspend fun encode(
        context: Context,
        imageBytes: ByteArray,
        payloadBits: String,
        eccWireName: String,
        strength: Float,
        orientation: Int,
    ): ByteArray = mutex.withLock {
        ensureInitializedLocked(context)
        withContext(Dispatchers.Default) {
            TrustMarkNative.encode(imageBytes, payloadBits, eccWireName, strength, orientation)
        }
    }

    suspend fun decode(
        context: Context,
        imageBytes: ByteArray,
        orientation: Int,
    ): NativeDecodeResult = mutex.withLock {
        ensureInitializedLocked(context)
        withContext(Dispatchers.Default) {
            TrustMarkNative.decode(imageBytes, orientation)
        }
    }

    private fun ensureInitializedLocked(context: Context) {
        if (initialized) return
        TrustMarkNative.initialize(context.applicationContext, ModelRepository.modelsDir(context))
        initialized = true
    }
}
