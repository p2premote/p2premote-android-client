#!/usr/bin/env bash
# build-punch-native.sh - Linux/macOS 版 Rust punch JNI 构建（与 build-punch-native.ps1 等价；
# Windows 继续走 ps1，由 app/build.gradle 按宿主 OS 选择）。
#
# 前置依赖：cargo/rustup、Android NDK（ANDROID_HOME 下 ndk/<版本>），
# 以及兄弟目录 ../p2premote-punch-rs（punch-native/Cargo.toml 的 path 依赖）。
set -euo pipefail

NDK_VERSION="${NDK_VERSION:-27.0.12077973}"

repoRoot="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
crateDir="$repoRoot/punch-native"
jniRoot="$repoRoot/app/src/main/jniLibs"

AndroidSdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$AndroidSdk" ]]; then
    echo "Android SDK path is required: set ANDROID_HOME or ANDROID_SDK_ROOT" >&2
    exit 1
fi

ndkRoot="$AndroidSdk/ndk/$NDK_VERSION"
if [[ ! -d "$ndkRoot" ]]; then
    # 未装指定版本时取已安装的最高版本（可用 NDK_VERSION 环境变量钉版）
    ndkRoot="$(ls -d "$AndroidSdk"/ndk/*/ 2>/dev/null | sort -V | tail -1 || true)"
    if [[ -z "${ndkRoot:-}" ]]; then
        echo "Android NDK not found under $AndroidSdk/ndk" >&2
        exit 1
    fi
    echo "NDK $NDK_VERSION not found, using $(basename "$ndkRoot")"
fi

hostTag="$(uname -s)-$(uname -m)"
[[ "$hostTag" == Darwin-* ]] && hostTag="darwin-$(uname -m)"
toolBin="$ndkRoot/toolchains/llvm/prebuilt/$hostTag/bin"

command -v cargo >/dev/null 2>&1 || { echo "cargo not found in PATH" >&2; exit 1; }
command -v rustup >/dev/null 2>&1 || { echo "rustup not found in PATH" >&2; exit 1; }

export ANDROID_NDK_HOME="${ndkRoot%/}"
export ANDROID_NDK_ROOT="${ndkRoot%/}"
targetDir="${CARGO_TARGET_DIR:-$crateDir/target}"

targets="aarch64-linux-android:arm64-v8a x86_64-linux-android:x86_64"
for pair in $targets; do
    rust="${pair%%:*}"
    abi="${pair##*:}"
    rustup target add "$rust"

    key="${rust//-/_}"
    keyUpper="$(echo "$key" | tr '[:lower:]' '[:upper:]')"
    export "CC_$key=$toolBin/${rust}29-clang"
    export "CXX_$key=$toolBin/${rust}29-clang++"
    export "AR_$key=$toolBin/llvm-ar"
    export "CARGO_TARGET_${keyUpper}_LINKER=$toolBin/${rust}29-clang"

    (cd "$crateDir" && cargo build --release --target "$rust")

    source="$targetDir/$rust/release/libp2premote_punch_jni.so"
    destination="$jniRoot/$abi/libp2premote_punch_jni.so"
    if [[ ! -f "$source" ]]; then
        echo "JNI library was not generated: $source" >&2
        exit 1
    fi
    mkdir -p "$(dirname "$destination")"
    cp -f "$source" "$destination"
done

echo "built Rust punch JNI libraries under $jniRoot"
