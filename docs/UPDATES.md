# Manual update protocol

暖刻 does not register periodic work, alarms, or background jobs for updates.

1. The user taps “检查更新”.
2. The app requests the latest public release metadata from `api.github.com`.
3. If a newer version exists, the app shows version and release notes.
4. Only after the user taps “下载更新” does it download the APK and its `.sha256` asset.
5. The app verifies the exact SHA-256 digest, matching package identity and release version, a higher version code, and the same non-empty signing certificate set as the installed app.
6. Android's package installer is opened; installation requires visible user confirmation.

If any integrity check fails, the file is deleted and installation is not offered. A release must never replace assets after publishing; use immutable GitHub Releases when available.

Every HTTP redirect is checked before its request is sent. Only HTTPS on the approved GitHub release/API/asset hosts is accepted. Remote version text is never used as a local path. Downloads have time/size limits and are removed if verification fails or cancellation is observed.

## Release signing

The public repository and GitHub Actions contain no signing key or password. Releases are signed locally, then only the signed APK and its `.sha256` sidecar are uploaded. This keeps the signing identity off remote infrastructure while preserving the same-certificate check used by the app.
