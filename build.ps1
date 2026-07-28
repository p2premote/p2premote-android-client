param(
    [Parameter(Mandatory = $true)]
    [Alias('v')]
    [string]$Version
)

$ErrorActionPreference = 'Stop'

if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "version must be in semver format like 1.2.3"
}

$projectRoot = $PSScriptRoot
$gitCommit = (git -C $projectRoot rev-parse --short=6 HEAD).Trim()
if ($gitCommit -notmatch '^[0-9a-f]{6}$') {
    throw "failed to resolve the current git commit id"
}
$buildVersion = "$Version-$gitCommit"

if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = "C:\Users\LN\AppData\Local\Android\Sdk"
}

Write-Host "Building Android client version $buildVersion"

Push-Location $projectRoot
try {
    .\gradlew.bat assembleDebug --no-daemon "-Pp2premoteClientVersion=$buildVersion"

    $apkDirectory = Join-Path $projectRoot 'app\build\outputs\apk\debug'
    $sourceApk = Join-Path $apkDirectory 'app-debug.apk'
    $versionedApk = Join-Path $apkDirectory "p2pRemote-$buildVersion.apk"
    if (-not (Test-Path -LiteralPath $sourceApk)) {
        throw "debug APK was not generated: $sourceApk"
    }
    Move-Item -LiteralPath $sourceApk -Destination $versionedApk -Force
    Write-Host "Generated APK: $versionedApk"
}
finally {
    Pop-Location
}

# 在此处查看apk
#   D:\work\p2premote-android-client\client-android\app\build\outputs\apk\debug
