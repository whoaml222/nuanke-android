# Releasing

The stable update chain depends on one private release-signing key. Never commit or upload the key outside an encrypted secret store.

## Local signing

The development workspace keeps the key under ignored `.local-signing/`. Its password is stored with Windows DPAPI (`Export-Clixml`), so only the same Windows account on this computer can decrypt it. Run:

```powershell
$env:JAVA_HOME='path-to-jdk-17'
./scripts/build-release.ps1
```

Back up the keystore and its password securely. Losing them means future APKs cannot update existing installations; leaking them means an attacker could sign a malicious update.

## GitHub Actions secrets

Before creating a `v*` tag, configure:

- `NUANKE_KEYSTORE_BASE64`
- `NUANKE_KEYSTORE_PASSWORD`
- `NUANKE_KEY_ALIAS` (`nuanke-release`)
- `NUANKE_KEY_PASSWORD`

The workflow runs tests and lint, signs the release APK, creates an `.apk.sha256` sidecar, and publishes both through GitHub's own CLI. No signing material is written to the repository.

