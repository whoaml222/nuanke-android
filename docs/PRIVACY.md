# Privacy

暖刻 is designed for personal, local-only use.

## Stored on the phone

- selected application package names and display labels;
- user-configured limits, cooldowns, and reminder copy;
- focus task labels, durations, completions, and interruption counts;
- per-day aggregate use time and blocked-attempt counts.
- a bounded local service diagnostic log: connection timestamps, exception types/code frames without messages, and this app's recent process-exit reason codes. It is never uploaded automatically; copying it to the clipboard is an explicit user action.

## Never collected

- screen content, accessibility nodes, chats, keystrokes, images, contacts, files, location, microphone, camera, advertising identifiers, or account credentials.

The accessibility service declares `canRetrieveWindowContent=false` and does not call APIs that inspect the UI hierarchy.

## Focus reminders

Focus-start and focus-end reminders use Android's notification permission and a dedicated system notification channel. Vibration follows that channel and the phone's notification settings, so it can be muted or disabled by the user. No reminder content or timing data leaves the phone.

## Network behavior

There is no telemetry, analytics, advertising, account sync, or scheduled version check. The only network feature is the manual GitHub Releases updater. It runs only after the user taps “检查更新” (and later “下载更新”). As with any HTTPS request, GitHub can observe standard connection metadata such as IP address, time, and user agent; 暖刻 sends no study history, app list, rules, or device identifiers.
