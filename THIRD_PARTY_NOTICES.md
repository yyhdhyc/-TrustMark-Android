# Third-party notices

## Adobe TrustMark

- Source: https://github.com/adobe/trustmark
- Version: 0.4.0 (Rust crate, vendored under `native/vendor/trustmark`)
- License: MIT (see `native/vendor/trustmark/LICENSE`)
- Local changes:
  - `Trustmark::encode_with_version` so one loaded encoder can use any BCH schema
  - `Bits::new_with_detail` / `from_soft_array` to expose BCH repair count and a
    soft-decision confidence to the Android UI
- ONNX models are downloaded separately and are not part of the repository.

## ONNX Runtime

- Artifact: `com.microsoft.onnxruntime:onnxruntime-android:1.22.0`
- License: MIT
- Source: https://github.com/microsoft/onnxruntime

## ort (Rust bindings)

- Version: 2.0.0-rc.10
- License: MIT OR Apache-2.0
- Source: https://github.com/pykeio/ort

## jni-rs

- Version: 0.21.1
- License: MIT OR Apache-2.0
- Source: https://github.com/jni-rs/jni-rs

## AndroidX / Jetpack Compose / Coil

Licensed under Apache License 2.0. See the respective Maven artifacts and
their `NOTICE` files.

## ONNX models

The Q encoder/decoder models are downloaded from Adobe's official CDN and are
distributed under the MIT license according to the TrustMark repository. Review
Adobe's model terms before redistribution.
