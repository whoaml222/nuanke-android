# Privacy

暖刻 is designed for personal, local-only use.

## Stored on the phone

- selected application package names and display labels;
- user-configured limits, cooldowns, and reminder copy;
- focus task labels, durations, completions, and interruption counts;
- per-day aggregate use time and blocked-attempt counts.
- a bounded local service diagnostic log: connection timestamps, exception types/code frames without messages, and this app's recent process-exit reason codes. It is never uploaded automatically; copying it to the clipboard is an explicit user action.
- diary text, optional title/date/mood/tags, and only photos explicitly selected by the user. Diary entry and photo blobs are encrypted with AES-256-GCM using a non-exportable Android Keystore key. Random record IDs are authenticated as associated data; text and image plaintext never enter SQLite or its journal.

## Never collected

- other apps' screen content, accessibility nodes, chats, keystrokes, unselected images/files, contacts, location, microphone, camera, advertising identifiers, or account credentials.

The accessibility service declares `canRetrieveWindowContent=false` and does not call APIs that inspect the UI hierarchy.

## Private diary

The non-exported diary Activity requires system biometric/device-credential authentication each time it opens or returns from the background. The app never receives biometric templates or the phone password. A device without a secure lock cannot open the diary. The entire diary window uses FLAG_SECURE, including dialogs, so screenshots/screen casting and recent-task previews may appear blank; exact OEM behavior needs physical-device validation. No diary content or password is placed in Android saved-instance state, analytics, diagnostic logs, notifications, or network requests. Cleartext is necessarily present in app memory while editing; the lock is an application access gate, not an authentication-bound Keystore key and not protection against a rooted/compromised device or a trusted keyboard.

The system photo picker grants access only to chosen images. No camera or broad storage/media permission is requested. Up to six photos per entry are decoded to at most 1600 pixels on the longest edge, encoded as JPEG (removing EXIF/location metadata) and encrypted in private storage. Original gallery images are not modified or deleted. Removed/orphan attachments are cleaned on next successful diary opening. Trash is retained for 30 days and cleaned on opening, not by a background job. Deletion is logical deletion, not a guarantee of forensic secure erasure.

Manual backups include diary entries, photos and trash. They use PBKDF2-HMAC-SHA256 (210,000 iterations, independent random 16-byte salt) and AES-256-GCM with a fresh IV and an independent 8–128-character user password. Authentication is verified before parsing/restoring. The password cannot be recovered by the app. Backups are limited to 64 MiB; the UI reports failure if the limit is exceeded. Restore merges absent entry IDs transactionally, never overwrites existing entries, and rejects malformed/oversized content. The selected system file provider may be a cloud provider; choosing that destination explicitly hands the encrypted backup to that provider. The app does not upload it itself.

Uninstalling/clearing app data removes the local diary/key. Android cloud backup/device transfer are disabled. Users must manually back up before uninstalling, resetting or moving devices. Backups can be imported on a new phone with their separate backup password. If a local Keystore key becomes inaccessible, existing diary data is not silently reset.

## Focus reminders

Focus-start and focus-end reminders use Android's notification permission and a dedicated system notification channel. Vibration follows that channel and the phone's notification settings, so it can be muted or disabled by the user. No reminder content or timing data leaves the phone.

## Network behavior

There is no telemetry, analytics, advertising, account sync, or scheduled version check. The only network feature is the manual GitHub Releases updater. It runs only after the user taps “检查更新” (and later “下载更新”). As with any HTTPS request, GitHub can observe standard connection metadata such as IP address, time, and user agent; 暖刻 sends no study history, app list, rules, or device identifiers.
