param([string]$Repository = 'p2premote/p2premote-android-client')
$ErrorActionPreference = 'Stop'
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw 'Install GitHub CLI and run gh auth login before configuring signing secrets.'
}
& gh auth status
if ($LASTEXITCODE -ne 0) { throw 'Run gh auth login first.' }
$projectRoot = Split-Path $PSScriptRoot -Parent
$properties = ConvertFrom-StringData (Get-Content -LiteralPath (Join-Path $projectRoot 'signing.properties') -Raw)
foreach ($name in @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')) {
    if (-not $properties[$name]) { throw "Missing signing property: $name" }
}
$keystore = $properties.storeFile
if (-not [IO.Path]::IsPathRooted($keystore)) { $keystore = Join-Path $projectRoot $keystore }
$secrets = @{
    ANDROID_KEYSTORE_BASE64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore))
    ANDROID_KEYSTORE_PASSWORD = $properties.storePassword
    ANDROID_KEY_ALIAS = $properties.keyAlias
    ANDROID_KEY_PASSWORD = $properties.keyPassword
}
foreach ($name in $secrets.Keys) {
    $secrets[$name] | & gh secret set $name --repo $Repository
    if ($LASTEXITCODE -ne 0) { throw "Failed to configure $name" }
}
Write-Host "Fixed signing secrets configured for $Repository."
