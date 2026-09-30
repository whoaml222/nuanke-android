# Changelog

## 0.3.0

- Add private diary with optional titles, backdating, moods/tags, search, month/day filters and focus-stat snapshots.
- Add up to six user-selected photos per entry, local encrypted storage, autosave with failure/retry state and 30-day trash.
- Protect the entire diary with system biometric/device-credential authentication and secure-window screenshot/preview protection; relock on background/exit.
- Add password-encrypted manual backup/merge restore; no account, cloud sync, analytics or automatic networking.
- Keep four bottom destinations (Today, Limits, Diary, Review); move Settings to a top-bar action.

## 0.2.1

- Repair guard lifecycle recovery, overlay deduplication/retry, foreground timing, cooldown release and focus persistence.
- Add local-only sanitized diagnostics and actual service connection state; harden manual update validation.

## 0.2.0

- Add validated custom focus, break, and round values with a responsive narrow-screen layout.
- Add focus-start and focus-end notifications with short vibration reminders.
- Count and show a restricted-app block once per real foreground entry, ignoring overlay and System UI event noise.
- Refine Today and Review screen density and explain blocked-entry statistics more clearly.
- Replace the launcher, themed, fallback, and notification artwork with the selected timer-ring and sprout design.
- Add foreground-entry and focus-cycle regression tests.

## 0.1.1

- Harden startup on vivo Android 16 and add an entirely local startup-recovery report.
- Keep the release build unminified while the physical-device startup path is validated.

## 0.1.0

- Add user-selected application rules with continuous-session and daily cumulative limits.
- Add customizable warm reminders, return-to-Home action, and cooldown re-entry interception.
- Add configurable focus/break cycles, local focus totals, seven-day review, and blocked-attempt statistics.
- Add a privacy-limited accessibility service with window-content retrieval disabled.
- Add manual-only GitHub Releases update checks with SHA-256 and APK-certificate verification.
- Add the `暖刻` warm Material 3 visual system and adaptive launcher icon.
