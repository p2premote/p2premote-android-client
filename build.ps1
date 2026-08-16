param(
    [Parameter(Mandatory = $true)]
    [Alias('v')]
    [string]$Version
)

$ErrorActionPreference = 'Stop'

if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "version must be in semver format like 1.2.3"
}

if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
$env:Path += ";$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin"

$projectRoot = $PSScriptRoot
$gitCommitOutput = & git -C $projectRoot rev-parse --short=6 HEAD
if ($LASTEXITCODE -ne 0) {
    throw "failed to resolve the current git commit id"
}
$gitCommit = $gitCommitOutput.Trim()
if ($gitCommit -notmatch '^[0-9a-f]{6}$') {
    throw "failed to resolve the current git commit id"
}
$buildVersion = "$Version-$gitCommit"

if (-not $env:ANDROID_HOME) {
    throw "ANDROID_HOME must be set to the Android SDK directory"
}
if (-not (Test-Path -LiteralPath $env:ANDROID_HOME -PathType Container)) {
    throw "ANDROID_HOME does not exist: $env:ANDROID_HOME"
}

Write-Host "Building Android client version $buildVersion"

Push-Location $projectRoot
try {
    & .\gradlew.bat assembleDebug --no-daemon "-Pp2premoteClientVersion=$buildVersion"
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }

    $apkDirectory = Join-Path $projectRoot 'app\build\outputs\apk\debug'
    $sourceApk = Join-Path $apkDirectory 'app-debug.apk'
    $versionedApk = Join-Path $apkDirectory "p2pRemote-$buildVersion.apk"
    if (-not (Test-Path -LiteralPath $sourceApk)) {
        throw "debug APK was not generated: $sourceApk"
    }
    Move-Item -LiteralPath $sourceApk -Destination $versionedApk
    Write-Host "Generated APK: $versionedApk"
}
finally {
    Pop-Location
}

# 在此处查看apk
#   D:\work\p2premote-android-client\client-android\app\build\outputs\apk\debug
