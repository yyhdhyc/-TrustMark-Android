TrustMarkTool 1.2.2 APK
=======================

Release build (recommended):
  TrustMarkTool-1.2.2-release.apk
  Package: com.trustmarktool
  Version: 1.2.2 (versionCode 5)
  Size: 29,176,504 bytes
  SHA-256: D00ED802DD74D174FCE9DBE1559581BB8DA306CDC8144690EF7DB0524128E371
  Signature: release key (v2), CN=TrustMarkTool. Min Android: 8.0 (API 26).
  ABIs: arm64-v8a, x86_64

Debug build:
  TrustMarkTool-1.2.2-debug.apk
  Package: com.trustmarktool.debug
  Version: 1.2.2 (versionCode 5)
  Size: 36,952,274 bytes
  SHA-256: E32AB9CADA1B5485A45991CE2081BB5117A9319C104F087A3FFE4980FD74C428
  Signature: Android debug certificate (v2). Installable, NOT for production.

Install:
  adb install -r TrustMarkTool-1.2.2-release.apk
  (release 和 debug 包名不同，可共存；卸载任一不影响另一个)

First run:
  The APK does NOT contain the ONNX models. The first encode/decode downloads
  encoder_Q.onnx + decoder_Q.onnx (~61.7 MiB) from Adobe's official CDN and
  verifies pinned SHA-256 digests. Grant notification permission for progress.

v1.2.2 notes:
  - Universal mode now has the same adjustable strength slider as batch mode
    (0.50-2.00, step 0.05, default 1.30); one value is shared and persisted.

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
    libraries were rebuilt with Rust 1.88.0 + NDK r27b; no binary patch is used.  - Output names are <original>_tm.<ext> and <original>_tm_2.<ext>.
  - No physical-device PNG/JNI/ORT/model test has been performed.

Models can be bundled offline by running:
  python scripts/fetch-models.py --to-assets
  ./gradlew :app:assembleDebug

Verify integrity:
  sha256sum -c SHA256SUMS.txt
