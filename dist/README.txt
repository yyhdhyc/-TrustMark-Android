TrustMarkTool 1.0.0 debug APK
=============================

File: TrustMarkTool-1.0.0-debug.apk
Package: com.trustmarktool.debug
Min Android: 8.0 (API 26)
ABIs: arm64-v8a, x86_64
Signature: Android debug certificate (v2). Installable, NOT for production.

Install:
  adb install -r TrustMarkTool-1.0.0-debug.apk

First run:
  The APK does NOT contain the ONNX models. The first encode/decode downloads
  encoder_Q.onnx + decoder_Q.onnx (~61.7 MiB) from Adobe's official CDN and
  verifies pinned SHA-256 digests. Grant notification permission for progress.

Models can be bundled offline by running:
  python scripts/fetch-models.py --to-assets
  ./gradlew :app:assembleDebug

Verify integrity:
  sha256sum -c SHA256SUMS.txt
