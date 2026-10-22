#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK_VERSION="27.0.12077973"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"

if [[ -z "${NDK}" && -f "${ROOT}/local.properties" ]]; then
  SDK="$(grep '^sdk.dir=' "${ROOT}/local.properties" | head -n1 | cut -d= -f2- || true)"
  if [[ -n "${SDK}" && -d "${SDK}/ndk/${NDK_VERSION}" ]]; then
    NDK="${SDK}/ndk/${NDK_VERSION}"
  fi
fi
if [[ -z "${NDK}" ]]; then
  echo "找不到 Android NDK ${NDK_VERSION}。请设置 ANDROID_NDK_HOME。" >&2
  exit 1
fi

command -v cargo >/dev/null || { echo "缺少 Rust 1.88+" >&2; exit 1; }
command -v cargo-ndk >/dev/null || { echo "请先运行 cargo install cargo-ndk" >&2; exit 1; }

export ANDROID_NDK_HOME="${NDK}"
rustup target add aarch64-linux-android x86_64-linux-android
mkdir -p "${ROOT}/app/src/main/jniLibs"

cd "${ROOT}/native"
cargo ndk -t arm64-v8a -t x86_64 -P 26 -o "${ROOT}/app/src/main/jniLibs" build --release

ls -lh "${ROOT}/app/src/main/jniLibs/arm64-v8a/libtrustmark_jni.so" \
       "${ROOT}/app/src/main/jniLibs/x86_64/libtrustmark_jni.so"
