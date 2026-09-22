param(
    [Parameter(Mandatory = $true)][string]$UnsignedApk,
    [Parameter(Mandatory = $true)][string]$OutputApk,
    [Parameter(Mandatory = $true)][string]$ApkSigner
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$signingRoot = Join-Path $projectRoot '.local-signing'
if (-not (Test-Path -LiteralPath $UnsignedApk -PathType Leaf)) { throw 'Unsigned APK is missing.' }
if (Test-Path -LiteralPath $OutputApk) { throw 'Output already exists; refusing to replace an artifact.' }

# Run under the Windows identity that encrypted the local password. No password files
# or command-line secrets are created, even if compilation used a different identity.
$securePassword = Import-Clixml -LiteralPath (Join-Path $signingRoot 'password.clixml')
$credential = [pscredential]::new('nuanke', $securePassword)
try {
    $env:NUANKE_SIGNING_PASSWORD = $credential.GetNetworkCredential().Password
    & $ApkSigner sign --ks (Join-Path $signingRoot 'nuanke-release.jks') --ks-key-alias nuanke-release --ks-pass env:NUANKE_SIGNING_PASSWORD --key-pass env:NUANKE_SIGNING_PASSWORD --out $OutputApk $UnsignedApk
    if ($LASTEXITCODE -ne 0) { throw "APK signing failed: $LASTEXITCODE" }
}
finally {
    Remove-Item Env:NUANKE_SIGNING_PASSWORD -ErrorAction SilentlyContinue
    $credential = $null
    $securePassword = $null
}
& $ApkSigner verify --verbose --print-certs $OutputApk
if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $LASTEXITCODE" }
