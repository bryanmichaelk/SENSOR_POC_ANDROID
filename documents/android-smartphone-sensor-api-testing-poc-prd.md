# Product Requirements Document: Android Smartphone Sensor API Testing PoC

## 1. Objective and Scope

### Objective

Build a minimal native Android application that determines, through documented checks and physical-device testing:

- Which selected sensors and behavioral APIs are available.
- Which runtime permissions, special access settings, roles, or foreground services are required.
- Which fields, timestamps, units, and event types each source returns.
- Whether collection works while visible, backgrounded, screen-off, or under power restrictions.
- How behavior differs across Android versions, devices, and OEM implementations.
- Which errors, interruptions, sampling gaps, and policy restrictions occur.

### Scope

- Kotlin, Android Studio, native Android.
- Physical phones running Android 12 through Android 17, API 31 through API 37.
- Local-only data storage.
- JSON and CSV export.
- Short, user-initiated experiments.
- No backend, authentication, analytics, machine learning, participant management, advanced charts, or production monitoring.
- No attempt to publish call-log functionality unless the application qualifies under Google Play policy.
- Optional location testing is phase 2 and must remain isolated from the core sensor tests.

The feasibility statuses below are the documented baseline. Device-dependent conclusions must be confirmed and recorded per physical device.

## 2. Candidate API List

1. Aggregated application usage statistics.
2. Application usage events.
3. Phone call logs and call metadata.
4. Accelerometer.
5. Gyroscope.
6. Linear acceleration.
7. Rotation vector.
8. Step counter.
9. Step detector.
10. Screen interactive state and screen on/off events.
11. Device unlock or keyguard-hidden events.
12. Battery level and charging state.
13. Ambient light.
14. Proximity.
15. Optional location updates.

## 3. API Feasibility Matrix

**FG** means the application UI is visible. **BG** means the UI is not visible. A user-visible foreground service is identified separately because Android does not treat it as an ordinary background process.

| API or data source | Data obtainable | Android API or class | Permissions or settings | Minimum relevant Android version | Hardware requirements | Foreground availability | Background availability | Continuous collection feasibility | Limitations and known restrictions | Test procedure | Expected outcome | Final feasibility status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Aggregated application usage | Package name, foreground time, last-used time, visible time, foreground-service time where supported | `UsageStatsManager.queryUsageStats()` | Manifest `PACKAGE_USAGE_STATS`; user must grant Usage Access in Settings | API 21 | None | Yes, after special access grant | Periodic queries can run through WorkManager or an active service while the user is unlocked | No real-time stream; query snapshots only | Special access cannot be granted through a normal runtime dialog. Results may be empty. Time ranges can be expanded to interval boundaries. Avoid `QUERY_ALL_PACKAGES` merely to resolve labels. | Query a known interval before and after opening selected apps for measured periods; repeat denied, granted, locked, and unlocked | Empty or denied before access; package-level aggregates after access; OEM and timing differences recorded | **Accessible with restrictions** |
| Application usage events | Package, class where available, event type, event timestamp, activity resumed/paused, screen/keyguard events where recorded | `UsageStatsManager.queryEvents()` and `UsageEvents.Event` | Same Usage Access setting | API 21; several event types were added later | None | Yes | Retrospective queries are possible while process runs; WorkManager execution is deferred and not continuous | No observer API; poll recent intervals and deduplicate | Events are retained only for a few days. Queries return `null` while the user is locked from Android 11 onward. Event coverage varies by release and OEM. Deprecated foreground/background event constants must not be treated as current. | Generate controlled app switches, screen changes, and unlocks; query by timestamp window; compare actions with returned event types | Ordered historical events when access is granted and device is unlocked; possible missing or OEM-specific events | **Accessible with restrictions** |
| Phone call logs | Call date, duration, type, number, presentation, country ISO, and other available columns | `ContentResolver` with `CallLog.Calls` | `READ_CALL_LOG`; dangerous and hard-restricted; installer allowlisting and eligible default Phone or Assistant role or policy exception | API 1; modern restrictions apply on current releases | Telephony-capable device and actual call records | Only if permission is grantable | A later query is technically possible if permission remains valid; no need for continuous collection | Content observation is not reliable when the process is stopped; unsuitable for this PoC | Google Play normally prohibits call-log permission for a sensor-testing PoC. The app must not request default-dialer status solely to bypass policy. Raw numbers and names are sensitive. | Show restriction state; request only in an approved controlled build; query a test call if permission is legitimately available; otherwise record denial | Standard PoC installation is expected to be denied, restricted, or ineligible. Approved controlled environments may return rows | **Not accessible for the intended approach** |
| Accelerometer | X, Y, Z acceleration including gravity in m/s²; accuracy; sensor timestamp | `SensorManager`, `TYPE_ACCELEROMETER` | None at normal rates; `HIGH_SAMPLING_RATE_SENSORS` for rates above 200 Hz when targeting API 31+ | API 3 | Accelerometer | Yes | Ordinary BG process does not receive continuous events reliably on API 28+; test only with a user-started foreground service | Feasible during FG or a valid FGS, subject to process, power, and OEM behavior | Rate limiting applies on Android 12+. Sensor timestamps use elapsed realtime, not Unix time. API 34+ requires a valid FGS type and use case. | Test normal and fastest requested rates for five minutes; background, lock screen, and return; compare requested and observed rate and gaps | Regular three-axis events in FG; rate may be capped; BG behavior depends on valid FGS and device | **Accessible with restrictions** |
| Gyroscope | Angular velocity X, Y, Z in rad/s; accuracy; timestamp | `SensorManager`, `TYPE_GYROSCOPE` | Same high-rate rule as accelerometer | API 3 | Gyroscope, absent on some lower-cost devices | Yes when present | Same restrictions as accelerometer | Same as accelerometer | Hardware is not guaranteed; calibration drift and rate limits apply. | Rotate device around each axis; repeat FG and FGS tests; record observed maximum rate | Axis changes when rotated, or explicit unsupported result | **Device-dependent** |
| Linear acceleration | Gravity-excluded X, Y, Z acceleration in m/s² | `SensorManager`, `TYPE_LINEAR_ACCELERATION` | None at normal rates; high-rate rules may apply | API 9 | Hardware or software-derived virtual sensor | Yes when exposed | Same continuous-sensor restrictions | FG or valid FGS only | Availability and filtering algorithm vary by device; values can lag raw acceleration. | Move and stop the device; compare against accelerometer; repeat screen-off test | Near-zero at rest with movement peaks, or unsupported result | **Device-dependent** |
| Rotation vector | Device orientation represented by rotation vector components; optional estimated heading accuracy | `SensorManager`, `TYPE_ROTATION_VECTOR` | None at normal rates; high-rate rules may apply | API 9 | Usually sensor fusion using accelerometer, gyroscope, and magnetometer | Yes when exposed | Same continuous-sensor restrictions | FG or valid FGS only | Sensor fusion and component count vary; magnetic interference affects results. | Rotate through known orientations; log vector length, values, and observed rate | Stable orientation changes, or unsupported result | **Device-dependent** |
| Step counter | Cumulative steps since reboot while the sensor is active | `SensorManager`, `TYPE_STEP_COUNTER` | `ACTIVITY_RECOGNITION` runtime permission on API 29+ | API 19 | Hardware or low-power step counter implementation | Yes after permission | May operate through an active listener and valid health FGS; delivery and wake-up behavior vary | Long-running low-rate collection is plausible but not guaranteed | Counter resets after reboot; events can have latency up to about 10 seconds; does not directly return session-only steps. | Record baseline, walk a counted route, compare delta, background app, lock screen, reboot if practical | Counter delta approximately matches counted steps; unsupported or delayed on some devices | **Device-dependent** |
| Step detector | One event per detected step | `SensorManager`, `TYPE_STEP_DETECTOR` | `ACTIVITY_RECOGNITION` on API 29+ | API 19 | Step detector sensor | Yes after permission | Same FGS and device restrictions as step counter | Event-based collection is possible while listener survives | Expected latency is lower than step counter but false or missed detections are device-dependent. | Walk a counted route at different speeds; test FG and FGS; compare event count | Approximately one event per step, or unsupported result | **Device-dependent** |
| Screen activity | Current interactive state and screen on/off transitions | `PowerManager.isInteractive()`; `ACTION_SCREEN_ON`; `ACTION_SCREEN_OFF` | None | `isInteractive()` API 20; broadcasts predate test range | None | Yes | Context-registered receiver works while process is alive; a valid FGS can extend the experiment | Partially feasible; not durable after process death | Manifest receivers cannot be relied upon for these implicit broadcasts. Android 14 may defer less important broadcasts while an app is cached. | Start session, turn screen off/on repeatedly, background app, run with and without FGS, compare event times | Accurate current state; transitions while receiver is alive; possible deferred or missing BG events | **Accessible with restrictions** |
| Unlock or keyguard-hidden events | User-present broadcast; keyguard hidden/shown usage events where available | `ACTION_USER_PRESENT`; `UsageEvents.Event.KEYGUARD_HIDDEN` and `KEYGUARD_SHOWN`; `KeyguardManager` for current state | None for broadcast; Usage Access for historical usage events | Broadcast is longstanding; keyguard usage events require newer APIs | Configured lock screen for meaningful testing | Yes | Dynamic receiver requires a living process; Usage Stats can provide retrospective records | Not guaranteed as an exact continuous unlock log | Does not expose PIN, biometric type, or authentication details. Some wake events do not represent an unlock. OEM delivery can differ. | Lock and unlock using PIN and biometrics; compare broadcast, current keyguard state, and usage events | General unlock/keyguard transitions are detectable, but not complete authentication telemetry | **Accessible with restrictions** |
| Battery and charging | Level, scale, status, health, temperature, voltage, plugged source, charging state | `BatteryManager`; `ACTION_BATTERY_CHANGED`; power connected/disconnected broadcasts | None | API 1 | Battery-powered device | Yes | Charging connection changes can be received by manifest receiver; level snapshots require query or active receiver | Event-driven charging plus periodic battery snapshots; not high-frequency continuous data | `ACTION_BATTERY_CHANGED` is sticky but not a general manifest-delivered continuous feed. Some fields are unavailable on particular devices. | Capture baseline, connect and disconnect charger, observe level change, background and restart app | Immediate battery snapshot and charging transitions with device-provided fields | **Accessible** |
| Ambient light | Illuminance in lux | `SensorManager`, `TYPE_LIGHT` | None | API 3 | Ambient-light sensor | Yes when present | Same process and FGS limitations as continuous sensors | Usually FG only for reliable testing | Sensor may stop or behave differently with display off; physical placement and OEM smoothing affect results. | Cover and uncover sensor under different lighting; test display off and FGS | Lux changes with lighting, or explicit unsupported result | **Device-dependent** |
| Proximity | Distance in cm, often binary near/far using maximum range | `SensorManager`, `TYPE_PROXIMITY` | None | API 3 | Proximity sensor | Yes when present | Same process and FGS limitations | Usually FG only for reliable testing | Many devices return only 0 and maximum range. Foldables and devices using virtual proximity can behave differently. | Cover and uncover sensor near earpiece; repeat during screen-off test | Near/far transitions or distance values, or explicit unsupported result | **Device-dependent** |
| Optional location | Latitude, longitude, altitude, accuracy, speed, bearing, provider, timestamp | Native `LocationManager`; optional comparison with fused location provider | `ACCESS_COARSE_LOCATION` or `ACCESS_FINE_LOCATION`; `ACCESS_BACKGROUND_LOCATION` for true BG access on API 29+; location FGS permissions and type | API 1; modern BG permission API 29 | GNSS or network location provider | Yes after permission and location services enabled | Location FGS can provide user-visible collection; ordinary BG updates are throttled to a few times per hour | Continuous only with a valid user-started location FGS; otherwise limited | Approximate location may be selected on API 31+. Background permission and Google Play approval require strong justification. Provider availability varies. | Test denied, approximate, precise, location-off, indoor/outdoor, FG, FGS, and ordinary BG | FG fixes with declared accuracy; BG frequency and precision depend on grant and service mode | **Accessible with restrictions** |

## 4. Minimal Functional Requirements

- **FR-01 Capability inventory:** Enumerate requested sensors and report name, vendor, version, reporting mode, wake-up capability, minimum delay, maximum delay, maximum range, resolution, and power where exposed.
- **FR-02 Access state:** Show whether each runtime permission, special access setting, location service, or required hardware is available.
- **FR-03 Access actions:** Request runtime permissions or open the relevant system Settings page. Do not represent Usage Access as a normal runtime permission.
- **FR-04 Test control:** Provide explicit Start and Stop controls per source. Only one high-frequency test session is required at a time.
- **FR-05 Live output:** Show latest values, units, event count, observed frequency, session duration, and latest error.
- **FR-06 Timestamps:** Store receipt time in UTC epoch milliseconds and ISO 8601, plus source monotonic timestamps where supplied.
- **FR-07 Session metadata:** Record device manufacturer/model, Android version, API level, app version, permission state, requested sampling configuration, service mode, start/end reason, and sensor metadata.
- **FR-08 Gap detection:** Record sequence gaps, time since previous event, process/service restarts, device reboot indicators, and permission revocation where detectable.
- **FR-09 Local storage:** Persist results in app-private storage. High-frequency writes must be buffered.
- **FR-10 Export:** Export one session as JSON and source-specific CSV through the Storage Access Framework or Android Sharesheet without broad storage permission.
- **FR-11 Error capture:** Persist unsupported, denied, restricted, empty-result, `SecurityException`, service-start, export, and unexpected termination outcomes.
- **FR-12 Background modes:** Clearly distinguish visible Activity, ordinary BG, foreground service, and WorkManager execution.
- **FR-13 Privacy:** Redact phone numbers and contact names. If an approved call-log test is performed, persist only call type, duration, date, presentation state, and a locally salted identifier.
- **FR-14 Data deletion:** Allow deletion of individual sessions or all locally stored test data.
- **FR-15 No hidden collection:** Collection starts only after an explicit user action and displays an ongoing notification whenever a foreground service is active.

## 5. Basic App Screen Requirements

### 5.1 Capability Dashboard

- List every candidate source.
- Show status: available, missing hardware, permission required, setting required, restricted, running, stopped, or error.
- Show the latest session result and feasibility status.
- Identify call logs as unavailable for a normal PoC installation.

### 5.2 Test Detail

- Source description and returned units.
- Hardware and API metadata.
- Required permission or Settings action.
- Sampling rate or query interval where applicable.
- Start, Stop, Clear, and Export actions.
- Background test option only when technically applicable.

### 5.3 Live Session

- Latest values or events.
- Event count and observed events per second.
- Start time, elapsed time, and latest event time.
- Gap and interruption indicator.
- Current lifecycle mode: FG, ordinary BG, FGS, or worker.
- Visible error and permission state.

### 5.4 Sessions and Export

- Sessions grouped by source and timestamp.
- Summary including device, Android version, duration, sample count, gaps, and end reason.
- JSON and CSV export.
- Delete session and delete all data.

## 6. Simple Implementation Approach

- Use one Kotlin Android application module.
- Set `minSdk` to API 31 to cover approximately the previous five Android release years, and compile and target the current stable API 37.
- Use a simple native UI with one Activity and four screens.
- Represent each source through a small test adapter with common operations: capability check, access check, start, stop, event stream, and error reporting.
- Use `SensorManager` callbacks for physical and virtual sensors.
- Use direct platform managers and dynamic `BroadcastReceiver` registrations for usage, screen, keyguard, and battery tests.
- Store each session as append-only JSON Lines plus a small metadata JSON document in app-private storage.
- Convert stored records to CSV or JSON during export using `ACTION_CREATE_DOCUMENT` or a temporary `FileProvider` URI.
- Use a bounded in-memory buffer and background writer for high-frequency sensor data.
- Use a user-started foreground service only for experiments that have a valid service type and use case:
  - Motion and step tests: evaluate the Android 14+ `health` type and prerequisites.
  - Location: use the `location` type.
  - Ambient-light or proximity experiments without a matching standard type: evaluate `specialUse` only for internal testing, with the required subtype explanation. Do not assume Google Play approval.
- Request `POST_NOTIFICATIONS` on API 33+ for clear FGS visibility. A denied notification permission does not remove the Android requirement to run the service visibly through system surfaces.
- Use WorkManager only for delayed snapshot queries and persistence checks. Periodic WorkManager execution is inexact and unsuitable for continuous sensor sampling.
- Do not use an accessibility service, VPN, device administrator, root access, or private APIs.

## 7. API Testing Procedures

### 7.1 Device Matrix

Test on at least three physical phones from at least two OEMs:

1. Android 12 or 12L, API 31 or 32.
2. Android 14 or 15, API 34 or 35.
3. Android 16 or 17, API 36 or 37.

Record model, OEM build, security patch, battery-optimization state, and supported sensor list.

### 7.2 Standard Procedure for Each Source

1. Run capability detection before requesting access.
2. Run with permission or special access denied.
3. Grant access and repeat.
4. Start a foreground test and perform a controlled action.
5. Press Home and continue without an FGS where meaningful.
6. Repeat with a user-started FGS where technically valid.
7. Turn the screen off and leave the device idle.
8. Enable battery saver and repeat a shortened run.
9. Return to the app and inspect counts, gaps, errors, and end reason.
10. Revoke access while stopped, then verify that the next run fails clearly.
11. Export JSON and CSV and compare them with the live session.
12. Record observed behavior separately from documented expectations.

### 7.3 Controlled Actions

- Usage: open three known apps for timed intervals and switch between them.
- Call log: place one controlled test call only when access is legitimately available.
- Accelerometer and linear acceleration: rest, shake, and move along each axis.
- Gyroscope and rotation vector: rotate around each device axis.
- Steps: walk a manually counted route of at least 50 steps.
- Screen and unlock: perform five screen-off/on and lock/unlock cycles.
- Battery: connect and disconnect wired or wireless charging.
- Light: cover the sensor and expose it to several lighting conditions.
- Proximity: cover and uncover the sensor near the earpiece.
- Location: test outdoors, indoors, location disabled, approximate permission, and precise permission.

### 7.4 Background Test Durations

- Continuous sensors: 15 minutes FG and 15 minutes with the UI backgrounded.
- Step counter: at least 30 minutes including walking and idle periods.
- Usage statistics: query after at least 30 minutes of controlled app usage.
- WorkManager: allow at least one periodic scheduling window and record actual execution time.
- Location: 15 minutes each for FG, location FGS, and ordinary BG where permitted.

## 8. Test Cases and Acceptance Criteria

| Test case | Acceptance criteria |
|---|---|
| TC-01 Capability inventory | Every candidate displays either detected metadata or an explicit unsupported result. No missing sensor causes a crash. |
| TC-02 Permission denied | Test does not start silently. UI and stored result identify the denied permission or setting. |
| TC-03 Permission granted | Supported source produces correctly typed records with timestamps and units. |
| TC-04 Foreground sensor collection | At least one valid event is recorded for each present sensor after the corresponding controlled action. |
| TC-05 Background behavior | Result identifies whether collection continued, stopped, was deferred, or developed gaps. FGS and ordinary BG results are not combined. |
| TC-06 Screen-off behavior | Screen-off interval and first post-screen event are identifiable. Missing events are recorded as a gap, not treated as zero values. |
| TC-07 Interruption handling | Permission revocation, service stop, process restart, sensor error, or user stop produces a recorded end reason. |
| TC-08 Usage access | Denied state returns no claimed usage result. Granted and unlocked state returns aggregates or events for controlled app use. |
| TC-09 Call-log restriction | Standard build clearly reports that call-log access is restricted and does not repeatedly prompt for an ungrantable permission. |
| TC-10 Step validation | Counter delta or detector count is compared with the manual count and includes absolute and percentage difference. |
| TC-11 Battery validation | Current level is returned and charging transitions are captured after connection and disconnection. |
| TC-12 Export | JSON parses successfully; CSV has stable headers; exported count equals stored session count; units and timestamps are included. |
| TC-13 Compatibility | App launches and completes capability inventory on every test device from API 31 through API 37 represented in the test set. |
| TC-14 Privacy | Export contains no raw phone number or contact name. All data remains local unless the user explicitly exports it. |

### Status Assignment

- **Accessible:** Works without special access and meets FG and applicable BG expectations.
- **Accessible with restrictions:** Works only with permissions, Settings access, FGS, throttling, or documented limitations.
- **Device-dependent:** Availability or behavior primarily depends on hardware or OEM implementation.
- **Requires further testing:** Documentation is insufficient or physical-device results conflict.
- **Not accessible for the intended approach:** Requires an ineligible role, restricted permission, private API, root access, or policy-incompatible behavior.

The PoC is accepted when all test cases have pass, fail, unsupported, or restricted evidence on every device. Unsupported and restricted outcomes are valid findings, not PoC failures.

## 9. Risks and Limitations

- Call logs are highly sensitive and hard-restricted. A sensor-testing PoC is not a valid reason to become the default dialer.
- Usage history, package names, location, and call metadata can reveal sensitive behavior even when stored only locally.
- Sensor presence, precision, maximum rate, batching, and power behavior vary by chipset and OEM.
- Android background behavior changes by OS version, target SDK, cached state, battery saver, Doze, and manufacturer power management.
- Foreground service use requires a legitimate type and user-visible purpose. `specialUse` is not a general bypass and can require Play review.
- Force-stopping the application prevents scheduled and broadcast-driven work until the user launches it again.
- WorkManager is inexact and cannot validate continuous sampling.
- `SensorEvent.timestamp` is monotonic time since boot, while many behavioral APIs use Unix epoch time. Reboot boundaries must be recorded.
- Step-counter values reset after reboot and are not session-relative.
- Usage events have limited retention, can be absent while locked, and are not a real-time subscription.
- Unlock events indicate keyguard transitions, not authentication method or proof of user identity.
- Screen, light, and proximity events can stop when the display is off or the process is cached.
- High-frequency sensors can create large files, battery drain, thermal load, and dropped samples.
- Background location is permission-sensitive, throttled without an FGS, and subject to Google Play review.
- The latest Android release should receive extra validation because OEM adoption and behavior may still vary.
- Results establish feasibility only for tested OS builds and devices, not every Android phone.

## 10. Official Android Documentation References

1. [UsageStatsManager API reference](https://developer.android.com/reference/android/app/usage/UsageStatsManager)
2. [UsageEvents.Event API reference](https://developer.android.com/reference/android/app/usage/UsageEvents.Event)
3. [CallLog.Calls API reference](https://developer.android.com/reference/android/provider/CallLog.Calls)
4. [Permissions used only in default handlers](https://developer.android.com/guide/topics/permissions/default-handlers)
5. [Google Play SMS and Call Log permissions policy](https://support.google.com/googleplay/android-developer/answer/10208820)
6. [Android sensors overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
7. [Android motion sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion)
8. [Sensor API reference](https://developer.android.com/reference/android/hardware/Sensor)
9. [Broadcasts overview](https://developer.android.com/develop/background-work/background-tasks/broadcasts)
10. [Implicit broadcast exceptions](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions)
11. [Battery level and charging-state monitoring](https://developer.android.com/training/monitoring-device-state/battery-monitoring)
12. [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
13. [Android 14 foreground service type requirements](https://developer.android.com/about/versions/14/changes/fgs-types-required)
14. [WorkManager work requests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
15. [Location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions)
16. [Background location limits](https://developer.android.com/about/versions/oreo/background-location-limits)
17. [Data and file storage overview](https://developer.android.com/training/data-storage)
18. [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)
19. [Notification runtime permission](https://developer.android.com/develop/ui/views/notifications/notification-permission)
20. [Android 17 release notes](https://developer.android.com/about/versions/17/release-notes)
