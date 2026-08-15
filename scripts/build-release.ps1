$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$signingRoot = Join-Path $projectRoot '.local-signing'
$passwordFile = Join-Path $signingRoot 'password.clixml'
$keystoreFile = Join-Path $signingRoot 'nuanke-release.jks'

if (-not (Test-Path -LiteralPath $passwordFile) -or -not (Test-Path -LiteralPath $keystoreFile)) {
    throw 'Local release signing material is missing. See docs/RELEASING.md.'
}

$securePassword = Import-Clixml -LiteralPath $passwordFile
$credential = [pscredential]::new('nuanke', $securePassword)
$plainPassword = $credential.GetNetworkCredential().Password

try {
    $env:ANDROID_KEYSTORE_PATH = $keystoreFile
    $env:ANDROID_KEYSTORE_PASSWORD = $plainPassword
    $env:ANDROID_KEY_ALIAS = 'nuanke-release'
    $env:ANDROID_KEY_PASSWORD = $plainPassword
    & (Join-Path $projectRoot 'gradlew.bat') testDebugUnitTest lintDebug assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
}
finally {
    Remove-Item Env:ANDROID_KEYSTORE_PATH -ErrorAction SilentlyContinue
    Remove-Item Env:ANDROID_KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:ANDROID_KEY_ALIAS -ErrorAction SilentlyContinue
    Remove-Item Env:ANDROID_KEY_PASSWORD -ErrorAction SilentlyContinue
    $plainPassword = $null
}

