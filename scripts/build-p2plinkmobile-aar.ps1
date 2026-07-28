param(
    [string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$NdkVersion = "27.0.12077973"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$goncDir = if ($env:P2PREMOTE_PUNCH_DIR) {
    Resolve-Path $env:P2PREMOTE_PUNCH_DIR
} else {
    Resolve-Path (Join-Path $repoRoot "..\p2premote-punch")
}
$outDir = Join-Path $repoRoot "app\libs"
$outFile = Join-Path $outDir "p2plinkmobile.aar"
$gomobile = Join-Path (go env GOPATH) "bin\gomobile.exe"

if (!(Test-Path $gomobile)) {
    go install golang.org/x/mobile/cmd/gomobile@latest
}

$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_NDK_HOME = Join-Path $AndroidSdk "ndk\$NdkVersion"

if (!(Test-Path $env:ANDROID_NDK_HOME)) {
    throw "Android NDK not found: $env:ANDROID_NDK_HOME"
}

New-Item -ItemType Directory -Force $outDir | Out-Null

Push-Location $goncDir
try {
    & $gomobile bind `
        -target=android `
        -androidapi 23 `
        -o $outFile `
        ./mobile/p2plinkmobile
} finally {
    Pop-Location
}

Write-Host "built $outFile"
