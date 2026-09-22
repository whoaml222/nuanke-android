# Verification and remaining device checks

## Automated coverage

Run `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` with JDK 17.

The 40-case suite contains 22 pure JVM cases and 18 Robolectric framework cases (nine scenarios on each of API 28 and API 34):

- system authorization matching, including shortened component names;
- accessibility connect, unbind, reconnect, and pending-write teardown;
- actual reminder view attachment, 66 noisy events counted once, cooldown expiry, own-activity and screen-off handling;
- deliberately failed overlay attachment: no false count, service survives and retry works;
- focus start, duplicate start, manual stop, partial-work persistence, and notification;
- atomic focus state/statistics updates with duplicate completion rejected;
- activity startup and sanitized local diagnostics;
- limit precedence, entry reset, elapsed-clock gaps, midnight/DST boundaries, version and update-URL validation.

These are framework-backed JVM checks, not physical phone or emulator tests. They do not prove vivo Android 16 background survival or notification delivery under all system settings. The APK targets API 36, but the local framework harness uses API 28/34.

## Phone acceptance checklist

1. Cover-install the same-key release without uninstalling or clearing data; old rules and history should remain.
2. Open the guard in system accessibility settings, return to 暖刻 Settings and check for “守护运行中”. Repeat leaving/re-entering settings.
3. Set a test app to a one-minute session and one-minute cooldown. Keep it visible until a single reminder appears. Repeated window events must not flash or increase the count.
4. Confirm Home, reopen once during cooldown, then check availability after expiry. A daily limit or active focus period can still block it.
5. Lock the phone and switch to 暖刻: neither should keep counting the previous restricted app. Home, system settings and the default phone must remain accessible.
6. Start and stop a one-minute focus round. Check reminders, channel vibration settings, partial focus statistics and repeated taps. Repeat with notification permission denied and while locked.
7. If the system switch still turns itself off, copy the local diagnostic report from Settings. It is never sent automatically and contains no selected-app list or screen/chat content.

Do not reset the phone, grant device-admin privileges or disable system-wide security to run these checks. Users can always disable the guard in system settings.
