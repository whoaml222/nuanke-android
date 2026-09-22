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

The guard separates system authorization from its actual connection/loaded-rule state. Connection, event, screen-state and overlay errors are recorded in a bounded local diagnostic log. A failed overlay is not counted or marked as handled and is retried with backoff. Screen-off and own-activity transitions stop restricted-app counting. Home, system settings and the default phone are excluded from restriction.

DataStore has one process-lifetime shared read stream; timer commands read authoritative persisted state. Accepted usage writes outlive service teardown. Focus state and aggregate statistics commit in one transaction with an expected-state check, preventing duplicate completion and cancelled stop writes. Local-date usage is split at midnight.

Disabling 暖刻's accessibility service immediately stops interception. Uninstalling the app removes all local rules and data. The app never obtains device-admin or device-owner privileges.
