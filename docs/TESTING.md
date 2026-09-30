# Verification and remaining device checks

## Automated coverage

Run `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` with JDK 17.

The suite contains the original 40 guard/focus cases (22 pure JVM and 18 Robolectric framework cases), plus 26 diary cases (8 pure JVM cases and 9 framework scenarios on each of API 28 and API 34):

- system authorization matching, including shortened component names;
- accessibility connect, unbind, reconnect, and pending-write teardown;
- actual reminder view attachment, 66 noisy events counted once, cooldown expiry, own-activity and screen-off handling;
- deliberately failed overlay attachment: no false count, service survives and retry works;
- focus start, duplicate start, manual stop, partial-work persistence, and notification;
- atomic focus state/statistics updates with duplicate completion rejected;
- activity startup and sanitized local diagnostics;
- limit precedence, entry reset, elapsed-clock gaps, midnight/DST boundaries, version and update-URL validation.
- randomized diary encryption with record-bound associated data, wrong-key/tamper rejection, password-encrypted backup round trip including photos and trash;
- malformed date/ID/path, repeated ID, text/photo limits, search and 30-day trash boundary;
- encrypted SQLite blobs, transaction rollback, 100 rapid edits retaining the latest value, failed-save retry and expired-photo cleanup;
- lost-key fail-closed behavior, backup merge preserving local edits and duplicate import idempotence;
- diary locked startup, secure-device requirement, FLAG_SECURE, no explicit saved-state content/unlock flag and relocking on stop.
- multi-activity visibility handoff: returning to the diary must stop timing the previously used restricted app, just like returning to Today.

These are framework-backed JVM checks, not physical phone or emulator tests. They do not prove vivo Android 16 background survival or notification delivery under all system settings. The APK targets API 36, but the local framework harness uses API 28/34.

## Phone acceptance checklist

1. Cover-install the same-key release without uninstalling or clearing data; old rules and history should remain.
2. Open the guard in system accessibility settings, return to 暖刻 Settings and check for “守护运行中”. Repeat leaving/re-entering settings.
3. Set a test app to a one-minute session and one-minute cooldown. Keep it visible until a single reminder appears. Repeated window events must not flash or increase the count.
4. Confirm Home, reopen once during cooldown, then check availability after expiry. A daily limit or active focus period can still block it.
5. Lock the phone and switch to 暖刻: neither should keep counting the previous restricted app. Home, system settings and the default phone must remain accessible.
6. Start and stop a one-minute focus round. Check reminders, channel vibration settings, partial focus statistics and repeated taps. Repeat with notification permission denied and while locked.
7. If the system switch still turns itself off, copy the local diagnostic report from Settings. It is never sent automatically and contains no selected-app list or screen/chat content.
8. Open 随记. Without a lock-screen password it must stay locked; with one configured, try fingerprint/password, cancel, failed authentication, background/resume, screen off/on, rotation and recreation. Confirm screenshots, casting and recents are blocked on the physical vivo. No diary content may appear before authentication.
9. Write Chinese composing text and long paragraphs, backdate, set optional mood/tags and insert today's focus snapshot. Rapidly switch pages and background, then reopen after the status says saved. Simulate storage failure and verify retry retains the draft. Abrupt termination before “已保存” may lose the most recent unsaved keystrokes.
10. Select 1–6 photos including HEIC/large/corrupt/inaccessible images, remove one and return from the picker (re-authenticate if backgrounded). Check no duplicate imports, correct orientation/readability and absent EXIF metadata; originals must remain unchanged. The framework suite tests encryption/storage, not the physical picker/decoder or visual layout.
11. Test month/day filters, cross-month backdating, all-date search, trash/restore and confirmed permanent deletion. Check 30-day expiration on reopening, small screens, large fonts, keyboard insets and photo previews.
12. Export locally and restore with wrong/correct passwords and truncated/tampered files, then re-import to check no duplicates/overwrites. Verify photos/text/trash on a separate test install. Simulate insufficient space and the 64 MiB limit; a failed/partial file must not import. Never uninstall the user's real copy for this test.

Do not reset the phone, grant device-admin privileges or disable system-wide security to run these checks. Users can always disable the guard in system settings.
