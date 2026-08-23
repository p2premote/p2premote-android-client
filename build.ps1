param(
    [Parameter(Mandatory = $true)]
    [Alias('v')]
    [string]$Version,

    [Alias('c')]
    [string]$Configuration = 'Debug',

    [switch]$Release
)

$ErrorActionPreference = 'Stop'

# Windows PowerShell may treat `--release` as a positional argument for
# Configuration, while newer PowerShell versions may leave it unbound. Accept
# both cases for a short CLI:
#   .\build.ps1 -v 1.6.4 --release
#   .\build.ps1 -v 1.6.4 -Release
$doubleDashReleaseBound = $Configuration -eq '--release'
if ($doubleDashReleaseBound) {
    $Configuration = 'Release'
}

$remainingArguments = @($args)
$unsupportedArguments = @($remainingArguments | Where-Object { $_ -and $_ -ne '--release' })
if ($unsupportedArguments.Count -gt 0) {
    throw "unsupported argument(s): $($unsupportedArguments -join ', ')"
}
if ($remainingArguments -contains '--release') {
    $Release = $true
}
if ($Release) {
    if ($PSBoundParameters.ContainsKey('Configuration') -and $Configuration -ne 'Release') {
        throw "-Release/--release cannot be combined with -Configuration $Configuration"
    }
    $Configuration = 'Release'
}
if ($Configuration -notin @('Debug', 'Release')) {
    throw "configuration must be Debug or Release"
}

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

Write-Host "Building Android client version $buildVersion ($Configuration)"

Push-Location $projectRoot
try {
    & .\gradlew.bat "assemble$Configuration" --no-daemon "-Pp2premoteClientVersion=$buildVersion"
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }

    $configurationDirectory = $Configuration.ToLowerInvariant()
    $apkDirectory = Join-Path $projectRoot "app\build\outputs\apk\$configurationDirectory"
    $sourceApkName = switch ($Configuration) {
        'Debug' { 'app-debug.apk' }
        'Release' { 'app-release-unsigned.apk' }
    }
    $sourceApk = Join-Path $apkDirectory $sourceApkName
    $versionedApk = Join-Path $apkDirectory "p2pRemote-$buildVersion.apk"
    if (-not (Test-Path -LiteralPath $sourceApk)) {
        throw "$Configuration APK was not generated: $sourceApk"
    }
    Move-Item -LiteralPath $sourceApk -Destination $versionedApk -Force
    Write-Host "Generated APK: $versionedApk"
}
finally {
    Pop-Location
}
