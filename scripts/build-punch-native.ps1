param(
    [string]$AndroidSdk = "",
    [string]$NdkVersion = "27.0.12077973"
)

$ErrorActionPreference = "Stop"

# Rust 编译缓存根目录（N 盘不可用时改回 D 盘即可，如 "D:\rust-cache"）
$RustCacheRoot = "N:\rust-cache"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $AndroidSdk) {
    if ($env:ANDROID_HOME) {
        $AndroidSdk = $env:ANDROID_HOME
    }
    else {
        # Gradle 环境不一定带 ANDROID_HOME，回退到 local.properties 的 sdk.dir。
        $localProps = Join-Path $repoRoot "local.properties"
        if (Test-Path $localProps) {
            $sdkDir = (Get-Content $localProps | Where-Object { $_ -match '^sdk\.dir\s*=' }) -replace '^sdk\.dir\s*=\s*', ''
            if ($sdkDir) { $AndroidSdk = $sdkDir.Trim() }
        }
    }
    if (-not $AndroidSdk) {
        throw "Android SDK path is required: pass -AndroidSdk or set ANDROID_HOME"
    }
}
$crateDir = Join-Path $repoRoot "punch-native"
$cargoTargetDirectory = Join-Path $RustCacheRoot "windows"
$env:CARGO_TARGET_DIR = $cargoTargetDirectory
$jniRoot = Join-Path $repoRoot "app\src\main\jniLibs"
$ndkRoot = Join-Path $AndroidSdk "ndk\$NdkVersion"
$toolBin = Join-Path $ndkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin"

$null = Get-Command cargo -ErrorAction Stop
$null = Get-Command rustup -ErrorAction Stop
$env:RUSTC_WRAPPER = ""
Write-Host "Rust will compile directly"
if (!(Test-Path $ndkRoot)) {
    throw "Android NDK not found: $ndkRoot"
}

$env:ANDROID_NDK_HOME = $ndkRoot
$env:ANDROID_NDK_ROOT = $ndkRoot

$targets = @(
    @{ Rust = "aarch64-linux-android"; Abi = "arm64-v8a"; Clang = "aarch64-linux-android29-clang.cmd"; Cxx = "aarch64-linux-android29-clang++.cmd" },
    @{ Rust = "x86_64-linux-android"; Abi = "x86_64"; Clang = "x86_64-linux-android29-clang.cmd"; Cxx = "x86_64-linux-android29-clang++.cmd" }
)

Push-Location $crateDir
try {
    foreach ($target in $targets) {
        & rustup target add $target.Rust
        if ($LASTEXITCODE -ne 0) { throw "rustup target add failed: $($target.Rust)" }
        $targetKey = $target.Rust.Replace("-", "_")
        Set-Item "Env:CC_$targetKey" (Join-Path $toolBin $target.Clang)
        Set-Item "Env:CXX_$targetKey" (Join-Path $toolBin $target.Cxx)
        Set-Item "Env:AR_$targetKey" (Join-Path $toolBin "llvm-ar.exe")
        Set-Item "Env:CARGO_TARGET_$($targetKey.ToUpper())_LINKER" (Join-Path $toolBin $target.Clang)
        & cargo build --release --target $target.Rust
        if ($LASTEXITCODE -ne 0) { throw "cargo build failed: $($target.Rust)" }
        $destination = Join-Path $jniRoot "$($target.Abi)\libp2premote_punch_jni.so"
        $source = Join-Path $cargoTargetDirectory "$($target.Rust)\release\libp2premote_punch_jni.so"
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "JNI library was not generated: $source"
        }
        New-Item -ItemType Directory -Force (Split-Path $destination) | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination -Force
        if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) {
            throw "JNI library was not copied: $destination"
        }
    }
} finally {
    Pop-Location
}
Write-Host "built Rust punch JNI libraries under $jniRoot"
