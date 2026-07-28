param(
    [string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$NdkVersion = "27.0.12077973"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$crateDir = Join-Path $repoRoot "riperf3-native"
$jniRoot = Join-Path $repoRoot "app\src\main\jniLibs"
$ndkRoot = Join-Path $AndroidSdk "ndk\$NdkVersion"
$toolBin = Join-Path $ndkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin"

if (!(Get-Command cargo -ErrorAction SilentlyContinue) -or !(Get-Command rustup -ErrorAction SilentlyContinue)) {
    throw "Rust toolchain is required: install rustup and cargo first"
}
if (!(Test-Path $ndkRoot)) {
    throw "Android NDK not found: $ndkRoot"
}

$targets = @(
    @{ Rust = "aarch64-linux-android"; Abi = "arm64-v8a"; Clang = "aarch64-linux-android29-clang.cmd"; Cxx = "aarch64-linux-android29-clang++.cmd" },
    @{ Rust = "x86_64-linux-android"; Abi = "x86_64"; Clang = "x86_64-linux-android29-clang.cmd"; Cxx = "x86_64-linux-android29-clang++.cmd" }
)

$env:ANDROID_NDK_HOME = $ndkRoot
$env:ANDROID_NDK_ROOT = $ndkRoot
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
        $destination = Join-Path $jniRoot "$($target.Abi)\libp2premote_riperf3_jni.so"
        New-Item -ItemType Directory -Force (Split-Path $destination) | Out-Null
        Copy-Item "target\$($target.Rust)\release\libp2premote_riperf3_jni.so" $destination -Force
    }
} finally {
    Pop-Location
}

Write-Host "built Rust riperf3 JNI libraries under $jniRoot"
