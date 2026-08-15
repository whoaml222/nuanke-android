# Architecture

## Components

- Compose UI: onboarding, rules, focus timer, statistics, privacy, and manual update controls.
- `FocusGuardAccessibilityService`: receives only foreground package transitions, runs a local monotonic timer, shows an accessibility overlay, and invokes the system Home action.
- Local stores: DataStore-backed rules, focus state, and daily aggregates. No cloud storage or usage-access permission.
- `FocusTimerService`: a visible, user-started foreground timer; it does not schedule network work.
- Manual updater: created and invoked only by an explicit UI action.

## Rule precedence

1. Safety allowlist and the 暖刻 package are never blocked.
2. An active focus session blocks its selected distraction apps.
3. An unexpired cooldown blocks the app.
4. A daily limit blocks until the next local midnight.
5. A continuous-session limit starts the configured cooldown.

The service uses elapsed realtime for session durations so changing the wall clock cannot grant or consume session time. Local-date boundaries are used only for daily buckets.

## Recovery

Disabling 暖刻's accessibility service immediately stops interception. Uninstalling the app removes all local rules and data. The app never obtains device-admin or device-owner privileges.
