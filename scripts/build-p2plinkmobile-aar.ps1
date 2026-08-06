param(
    [Parameter(Mandatory = $true)]
    [string]$PunchSource,
    [Parameter(Mandatory = $true)]
    [string]$AndroidSdk,
    [Parameter(Mandatory = $true)]
    [string]$NdkVersion
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$goncDir = Resolve-Path $PunchSource
$outDir = Join-Path $repoRoot "app\libs"
$outFile = Join-Path $outDir "p2plinkmobile.aar"
$goCommand = Get-Command go -ErrorAction Stop
$goPath = (& $goCommand.Source env GOPATH).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "go env GOPATH failed with exit code $LASTEXITCODE"
}
$gomobile = Join-Path $goPath "bin\gomobile.exe"

if (!(Test-Path $gomobile)) {
    throw "gomobile is not installed: $gomobile"
}

if (Test-Path -LiteralPath $outFile) {
    throw "output already exists; remove it before rebuilding: $outFile"
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
    if ($LASTEXITCODE -ne 0) {
        throw "gomobile bind failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $outFile -PathType Leaf)) {
    throw "AAR was not generated: $outFile"
}

Write-Host "built $outFile"
