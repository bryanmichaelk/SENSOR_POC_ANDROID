# Android Sensor API PoC

Native Android feasibility-testing app for smartphone sensors and behavioral APIs.

## Current scope

- Capability inventory for motion, step, light, proximity, screen, battery, usage, and call-log candidates.
- Foreground tests for available sensors, screen events, and battery state.
- Runtime Activity Recognition request for step sensors.
- Usage Access settings deep link and 24-hour aggregate usage query.
- User-started foreground-service tests for eligible motion, step, ambient, proximity, and location sources.
- Optional foreground and foreground-service location test with precise-location permission.
- One-time WorkManager probe for inexact background UsageStats snapshots.
- Local JSONL session data and Android document-picker export.
- CSV conversion, per-session summary, per-session deletion, and delete-all controls.
- Explicit call-log restriction status. No `READ_CALL_LOG` request.

## Limits to verify on physical devices

- Foreground-service sensor availability and delivery vary by Android version, service type, OEM, battery optimization, and device hardware.
- WorkManager timing is intentionally inexact. It proves scheduled query behavior, not continuous collection.
- Background location permission is declared for feasibility research but not requested by this UI. Background location must be tested only after clear user consent and documented need.
- Direct Activity tests stop when the Activity backgrounds. Use explicit foreground-service mode for background feasibility experiments.

## Build

```powershell
.\gradlew.bat :app:assembleDebug
```

SDK location is configured in `local.properties` for this workstation. Do not commit machine-specific SDK paths in shared repositories.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

## UI design decisions

- Utility-light theme: supports high-contrast reading of sensor metadata and logs during long tests.
- Forest green: reserved for ready states and primary actions, so availability remains clear without turning every element into status color.
- Flat list rows: source selection is primary dashboard task; cards and decoration would weaken scan speed.
- No motion: live values update from sensor events; decorative animation would obscure timing behavior under test.
