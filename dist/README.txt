TrustMarkTool 1.2.0 debug APK
=============================

Current file:
  TrustMarkTool-1.2.0-debug.apk
  Package: com.trustmarktool.debug
  Version: 1.2.0 (versionCode 3)
  Size: 37,138,499 bytes
  SHA-256: 3A84E1901CF4F295952E6156C3C814B487D3AFC08AEB7768735E9B27625F3439
  Min Android: 8.0 (API 26)
  ABIs: arm64-v8a, x86_64
  Signature: Android debug certificate (v2). Installable, NOT for production.

Install:
  adb install -r TrustMarkTool-1.2.0-debug.apk

First run:
  The APK does NOT contain the ONNX models. The first encode/decode downloads
  encoder_Q.onnx + decoder_Q.onnx (~61.7 MiB) from Adobe's official CDN and
  verifies pinned SHA-256 digests. Grant notification permission for progress.

v1.2 notes:
  - Universal mode stores text only, with no serial.
  - Batch mode stores a 4-letter batch code + 12-bit serial + CRC-7.
  - Batch strength is adjustable 0.50-2.00 (default 1.30). The bundled JNI
    library has a verified binary patch raising the native clamp to 2.0;
    native/src/lib.rs is also updated, but was not rebuilt (no NDK/Rust).
  - Output names are <original>_tm.<ext> and <original>_tm_2.<ext>.
  - No physical-device JNI/ORT/model test has been performed.

Models can be bundled offline by running:
  python scripts/fetch-models.py --to-assets
  ./gradlew :app:assembleDebug

Verify integrity:
  sha256sum -c SHA256SUMS.txt
