#!/usr/bin/env pwsh
#Requires -Version 7.0
<#
.SYNOPSIS
  Cross-compiles native/ (Rust + TrustMark) into app/src/main/jniLibs.
.DESCRIPTION
  Requires Rust 1.88+, cargo-ndk, Android NDK r27 (27.0.12077973), and the two
  Android Rust targets. The resulting APK uses the official ONNX Runtime AAR.
#>
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$native = Join-Path $root "native"
$jniLibs = Join-Path $root "app\src\main\jniLibs"
$ndkVersion = "27.0.12077973"

function Resolve-Ndk {
    if ($env:ANDROID_NDK_HOME -and (Test-Path $env:ANDROID_NDK_HOME)) { return $env:ANDROID_NDK_HOME }
    if ($env:ANDROID_NDK_ROOT -and (Test-Path $env:ANDROID_NDK_ROOT)) { return $env:ANDROID_NDK_ROOT }

    $localProps = Join-Path $root "local.properties"
    $sdk = $null
    if (Test-Path $localProps) {
        $line = Get-Content $localProps | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) {
            $value = $line -replace '^sdk\.dir=', ''
            $sdk = $value.Replace('\:', ':').Replace('\\', '\')
        }
    }
    if (-not $sdk -and $env:ANDROID_HOME) { $sdk = $env:ANDROID_HOME }
    if (-not $sdk -and $env:ANDROID_SDK_ROOT) { $sdk = $env:ANDROID_SDK_ROOT }
    if ($sdk) {
        $candidate = Join-Path $sdk "ndk\$ndkVersion"
        if (Test-Path $candidate) { return $candidate }
    }
    return $null
}

$ndk = Resolve-Ndk
if (-not $ndk) {
    throw "找不到 Android NDK $ndkVersion。请安装 NDK 并设置 ANDROID_NDK_HOME，或写入 local.properties 的 sdk.dir。"
}

foreach ($tool in @("cargo", "cargo-ndk", "rustup")) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        if ($tool -eq "cargo-ndk") {
            throw "缺少 cargo-ndk。请运行：cargo install cargo-ndk"
        }
        throw "缺少 $tool。请安装 Rust 1.88+：https://rustup.rs"
    }
}

Write-Host "NDK: $ndk"
Write-Host "Rust targets..."
rustup target add aarch64-linux-android x86_64-linux-android

$env:ANDROID_NDK_HOME = $ndk
New-Item -ItemType Directory -Force -Path $jniLibs | Out-Null

Push-Location $native
try {
    cargo ndk -t arm64-v8a -t x86_64 -P 26 -o $jniLibs build --release
} finally {
    Pop-Location
}

$outputs = @(
    (Join-Path $jniLibs "arm64-v8a\libtrustmark_jni.so"),
    (Join-Path $jniLibs "x86_64\libtrustmark_jni.so")
)
foreach ($so in $outputs) {
    if (-not (Test-Path $so)) { throw "构建产物缺失：$so" }
    $sizeMb = [math]::Round((Get-Item $so).Length / 1MB, 1)
    Write-Host "OK $so ($sizeMb MB)"
}
Write-Host "完成。现在可运行 .\gradlew.bat :app:assembleDebug"
