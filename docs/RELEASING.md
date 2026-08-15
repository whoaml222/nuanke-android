# Releasing

The stable update chain depends on one private release-signing key. Never commit or upload the key. 暖刻 uses local-only signing so the keystore and password stay on the maintainer's computer.

## Local signing

The development workspace keeps the key under ignored `.local-signing/`. Its password is stored with Windows DPAPI (`Export-Clixml`), so only the same Windows account on this computer can decrypt it. Run:

```powershell
$env:JAVA_HOME='path-to-jdk-17'
./scripts/build-release.ps1
```

Back up the keystore and its password securely. Losing them means future APKs cannot update existing installations; leaking them means an attacker could sign a malicious update.

## Publish a release

After the local build and signature checks succeed, publish only these generated files from the ignored `dist/` directory:

- `nuanke-vX.Y.Z.apk`
- `nuanke-vX.Y.Z.apk.sha256`

Create an immutable public GitHub Release with the matching `vX.Y.Z` tag. Never upload `.local-signing/`, the keystore, its password, or an unencrypted backup. The repository CI runs source tests and lint only; release signing deliberately remains local.
