# Wristspacer

Bridges your **work calendar** (Android work profile) to a **Pixel Watch 3 complication**.

When a company-enforced work profile is active, the personal profile loses access to the work
calendar. Android's At A Glance (AAG) still shows work events because it has cross-profile
access at the system level. Wristspacer taps into that same system Smartspace feed and
forwards the next calendar event to the watch as a complication — no IT admin involvement,
no cross-profile permissions required.

---

## How it works

```
Work calendar (work profile)
        │
        ▼ (system cross-profile access)
SmartspaceManager  ──── SmartspaceBridgeService (phone, foreground)
        │                       │
        │  onTargetsAvailable   │  pushes via Wearable Data Layer
        └──────────────────────►│
                                ▼
                    PhoneDataListenerService (watch)
                                │  writes SharedPreferences
                                ▼
                    NextEventComplicationService (watch)
                                │  renders on watch face
                                ▼
                         ┌─────────────┐
                         │  HH:MM  ●   │
                         │ My Meeting  │
                         └─────────────┘
```

- **Phone app** — a minimal foreground service that subscribes to `SmartspaceManager` via
  reflection (hidden API), filters for the next calendar event, deduplicates mirrored events
  (`[Mirror] X` is dropped when `X` is also present), and pushes the result to the watch
  every time Smartspace updates (roughly once per minute).
- **Watch app** — a `WearableListenerService` that receives the pushed `DataItem` and a
  `ComplicationDataSourceService` that renders it. Supports `SHORT_TEXT` (small slots) and
  `LONG_TEXT` (wide slots).
- When no calendar event is imminent, the complication falls back to whatever Smartspace has
  (weather, etc.) rather than going blank.

---

## Prerequisites

| Requirement | Notes |
|---|---|
| Android phone with work profile | Tested on Pixel 9 Pro / Android 16 |
| Pixel Watch 3 | Wear OS 4, connected via WiFi or Bluetooth |
| [Shizuku](https://shizuku.rikka.app/) | Must be running on the phone — grants the `ACCESS_SMARTSPACE` permission at runtime |

Shizuku can be activated via wireless ADB (developer options) or via root. You only need to
set it up once; Shizuku persists across reboots.

---

## First-time setup

1. Install the **phone APK** (`phone-debug.apk`) on your phone.
2. Install the **watch APK** (`wear-debug.apk`) on your Pixel Watch 3.
3. Open **Wristspacer** on the phone. The status screen walks you through three steps:
   - **Grant Shizuku Permission** — tap once, confirm in Shizuku.
   - **Disable Battery Optimisation** — tap to open the system dialog, select "Don't optimise".
     This prevents Doze mode from deferring Data Layer pushes.
   - **Start Bridge** — appears automatically once the above are done; or the bridge
     starts itself if all conditions are already met.
4. Add the **Next Calendar Event** complication to any watch face slot that supports
   `SHORT_TEXT` or `LONG_TEXT`.

After the first setup the bridge starts automatically on boot and whenever you open the app.

---

## Building from source

### Requirements

- Android Studio Meerkat or later (ships with JBR 21)
- Android SDK 35+, Wear OS SDK

### Build

```bash
# Phone
./gradlew :phone:assembleDebug

# Watch
./gradlew :wear:assembleDebug
```

### Install

```bash
# Phone (replace <serial> with your phone's ADB serial)
adb -s <serial> install -r phone/build/outputs/apk/debug/phone-debug.apk

# Watch (replace <ip:port> with your watch's ADB-over-WiFi address)
adb -s <ip:port> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

---

## Project structure

```
wristspacer/
├── phone/                          # Phone-side Android app
│   └── src/main/kotlin/…/phone/
│       ├── WristspacerApp.kt       # Application class; VMRuntime hidden-API bypass
│       ├── MainActivity.kt         # Status screen (Compose); auto-starts bridge
│       ├── SmartspaceBridgeService.kt  # Foreground service; Smartspace → Data Layer
│       ├── ShizukuHelper.kt        # Shizuku permission + pm grant helper
│       ├── PhoneWearListenerService.kt # Handles /request_update from watch
│       ├── BootReceiver.kt         # Auto-starts bridge on BOOT_COMPLETED
│       └── AppState.kt             # Shared state (BridgeStatus, DataKeys)
└── wear/                           # Watch-side Wear OS app
    └── src/main/kotlin/…/wear/
        ├── PhoneDataListenerService.kt     # WearableListenerService; stores event
        └── NextEventComplicationService.kt # Renders complication; requests update on activate
```

---

## Known limitations

- **Smartspace API is hidden / unsupported.** The integration uses double-reflection and
  Shizuku to access `SmartspaceManager`. Google could change or remove this API in any OS
  update.
- **Bridge must be started once after Shizuku setup.** On a brand-new install Shizuku
  permission must be granted manually before the bridge can run. Subsequent reboots are
  handled automatically.
- **Watch-only, no standalone mode.** The watch app requires the phone bridge to be running.
  If the bridge is stopped the watch complication shows the last pushed event until the Data
  Layer TTL expires.
