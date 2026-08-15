# Privacy

暖刻 is designed for personal, local-only use.

## Stored on the phone

- selected application package names and display labels;
- user-configured limits, cooldowns, and reminder copy;
- focus task labels, durations, completions, and interruption counts;
- per-day aggregate use time and blocked-attempt counts.

## Never collected

- screen content, accessibility nodes, chats, keystrokes, images, contacts, files, location, microphone, camera, advertising identifiers, or account credentials.

The accessibility service declares `canRetrieveWindowContent=false` and does not call APIs that inspect the UI hierarchy.

## Network behavior

There is no telemetry, analytics, advertising, account sync, or scheduled version check. The only network feature is the manual GitHub Releases updater. It runs only after the user taps “检查更新” (and later “下载更新”). As with any HTTPS request, GitHub can observe standard connection metadata such as IP address, time, and user agent; 暖刻 sends no study history, app list, rules, or device identifiers.

