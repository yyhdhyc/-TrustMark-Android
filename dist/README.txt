TrustMarkTool 1.2.1 debug APK
=============================

Current file:
  TrustMarkTool-1.2.1-debug.apk
  Package: com.trustmarktool.debug
  Version: 1.2.1 (versionCode 4)
  Size: 38,707,624 bytes
  SHA-256: d9ae088b9adb7e77dd87576dda8738ae4c195f2d09d2c04eed21d489748fb773
  Min Android: 8.0 (API 26)
  ABIs: arm64-v8a, x86_64
  Signature: Android debug certificate (v2). Installable, NOT for production.

Install:
  adb install -r TrustMarkTool-1.2.1-debug.apk

First run:
  The APK does NOT contain the ONNX models. The first encode/decode downloads
  encoder_Q.onnx + decoder_Q.onnx (~61.7 MiB) from Adobe's official CDN and
  verifies pinned SHA-256 digests. Grant notification permission for progress.

v1.2.1 notes:
  - PNG inputs (with or without alpha) are converted to Rgba8/Rgb8 before the
    PNG encoder runs; this fixes the v1.2.0 float-image failure.
  - Individual failures now show source/output file and reason; the batch
    continues with the remaining images.
  - Generate text, selected batch and mode persist across app restarts.
  - BCH menu is ordered BCH 3 / 4 / 5 / Super with two-line descriptions.
  - Generate and decode share one terminal-aware progress card.
  - Universal mode stores text only, with no serial.
  - Batch mode stores a 4-letter batch code + 12-bit serial + CRC-7.
  - Batch strength is adjustable 0.50-2.00 (default 1.30). Both bundled JNI
    libraries were rebuilt with Rust 1.88.0 + NDK r27b; no binary patch is used.
  - Output names are <original>_tm.<ext> and <original>_tm_2.<ext>.
  - No physical-device PNG/JNI/ORT/model test has been performed.

Models can be bundled offline by running:
  python scripts/fetch-models.py --to-assets
  ./gradlew :app:assembleDebug

Verify integrity:
  sha256sum -c SHA256SUMS.txt
