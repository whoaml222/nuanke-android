# Changelog

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
