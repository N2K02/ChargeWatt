# chargeWatt

chargeWatt is a minimal Android 1×1 home-screen charging and discharging widget.

## Features

- Shows charging (green) and discharging (amber) automatically, including wireless Qi charging. Current sign comes from BatteryManager, so a connected charger with net drain is correctly displayed as negative.
- Shows battery-side watts (W), current (mA), voltage (V), and battery percentage.
- Dark, transparent, and compact widget styles, with an optional outline border.
- A sticky foreground service samples battery state for the installed widget at the saved interval and updates the widget from each sample.
- Choose W or mA as the main value.
- User-selectable refresh 1–10 seconds, default 2 seconds.
- One consistent mRadio-style header above the status bar, Home, Settings (Widget display, Battery details, About & updates), and Quit. Settings uses compact icon-aligned ListItem rows; Android Back returns from each submenu.
- No notification permission, wake locks, continuous animation, or network telemetry. Android requires the foreground service to provide an ongoing notification while the widget sampler is active.
- Android 8+ (minSdk 26), Android 17 compile/target API 37.

## Important timing and measurement limits

The selected 1–10 second interval drives the sticky foreground service while at least one widget is installed, including while the app dashboard is paused. Power-change broadcasts trigger immediate refreshes, and WorkManager remains a periodic fallback (minimum 15 minutes). Android and the launcher still control scheduling during deep idle; the service cannot guarantee exact second-by-second timing in every power state.

Watts = signed battery current in microamperes multiplied by battery voltage in millivolts, divided by 1,000,000,000. Current direction determines the sign. This is **battery-side power**, not the power delivered by the wall charger, USB brick, or Qi pad. Unsupported battery current reports as unavailable rather than a fake value.

## CPU and GPU review

- The foreground widget service caches battery broadcasts for voltage, temperature, level and state; it queries signed instantaneous current on a dedicated I/O coroutine at the saved 1–10 second interval while a widget is installed.
- The 1-second setting remains the default. UI state is updated only when readings change.
- Widget redraws are fingerprint-cached in memory rather than persistently written to SharedPreferences every second. Lightweight text-only partial RemoteViews are sent for changes, with a periodic full refresh to bound system_server history.
- Background charge/discharge transitions are shown at the next permitted periodic update. Android 8+ restrictions prevent relying on manifest-declared implicit power broadcasts for immediate notification-free updates.
- No frame-driven graphics or continuous Compose/GPU animation.
- WorkManager is canceled when the last widget is removed; Quit persistently pauses widget polling until the app is reopened or a new widget is placed. No widget-host update can silently restart the background job after Quit. No receiver for charging/battery power changes is left enabled when the app is backgrounded.
- No in-app Logs menu or persistent charging-event history. A one-time migration clears any legacy chargewatt_events preferences.
- Real CPU/GPU/power results require an APK installed on a device, and cannot be inferred from static code review. Measure with Android Studio Profiler, adb shell dumpsys gfxinfo dev.n2k02.chargewatt, and adb shell dumpsys batterystats --charged dev.n2k02.chargewatt.

## Build channels and signing

Same toolchain as other N2K02 apps: Gradle 9.6, Android Gradle Plugin 9.4, Kotlin 2.4, Java 17, API 37, pinned n2k02theme 0.1.4 and n2k02updater 0.1.19.

Github Actions requires existing repository secrets N2K02_PACKAGES_TOKEN and SIGNING_PASSPHRASE. Signing uses the verified encrypted MRadio bundle, pinned to Git blob SHA 5203c1095062ad22099f76d319605ae5b79c0720. Both experimental PR and stable main release APKs are built from the release variant and signed by the same identity with application ID dev.n2k02.chargewatt. Experimental builds publish prerelease APKs; main builds publish stable APKs. Build fails closed when credentials are missing.

For local compilation, use Gradle 9.6 with Android SDK and N2K02_PACKAGES_TOKEN, then run: gradle testDebugUnitTest lintDebug assembleDebug. Local debug builds are not signed for in-place updates over release APKs.

The live `dev.n2k02:updater:0.1.34` controller and complete `N2K02UpdateScreen` now match mRadio: stable/experimental channel toggle, update checks, download progress, cryptographic APK verification, and Android system installer. Stable and experimental updates are read directly from this app’s public GitHub releases; no distribution credential or website manifest is required.

## Startup crash diagnostics

AndroidLogger tag: `ChargeWattStartup`. The dashboard no longer schedules WorkManager during `Activity.onCreate`; scheduling happens when a widget is enabled. Optional widget-manager, battery receiver registration, and device telemetry failures are isolated and logged rather than terminating the Activity. If a crash still occurs, enable monitoring in McpAdbAndroidLogger and relaunch chargeWatt to capture the full `AndroidRuntime` FATAL EXCEPTION stack trace. Passing CI cannot prove a physical-device startup succeeds.

## WorkManager release startup fix
This app uses the exact LocalAdb WorkDatabase_Impl constructor preservation rules so R8 cannot strip the public no-arg constructor used by AndroidX Startup before MainActivity is created. CI validates the **minified release DEX** using `scripts/verify_release_workmanager.py` before signing, and launches the signed APK twice in an emulator using `scripts/verify_startup_emulator.sh`. Never replace these gates with only a debug APK build. The experimental and stable channels use the same rules, R8 minification, and signing identity.

## Shared UI contract
mRadio is the canonical source for a single page header, Settings submenus, divider placement and system-bar insets. The power-source tile says **Battery** when Android reports the phone unplugged (plugged=0); charger labels distinguish AC, USB and Qi. No Logs screen or local charging-event history is included.

## Glass widget design
The 1×1 widget has three lightweight appearance styles: Dark, Transparent, and Compact. It places a charging-state icon on the left of the top row and two-digit battery temperature plus battery percentage on the right, followed by auto-sizing current, power, and voltage. The optional outline border can be disabled. Android RemoteViews cannot provide general live frosted wallpaper blur, so the backgrounds use static gradients and shapes without bitmaps or animation loops.

## Expressive theme and Qi state
The app wraps the complete screen in `N2K02Theme` (from the pinned 0.1.4 package), using Material 3 Expressive motion/shapes and system dynamic colors, as mRadio does. Battery current sign is preserved and never misrepresented as charger input power. When Android reports Wireless Qi attached, the widget badge displays **Qi**, even when the net battery current remains negative; the signed wattage and the dashboard explicitly state net battery drain. The 1×1 widget uses narrow, condensed, auto-sized text with shorter unit formatting to avoid Pixel 8 Pro clipping.

## Public update publishing (matches mRadio)
CI publishes signed APKs to this app’s public GitHub releases. Stable releases and experimental prereleases include their Android version code; the updater downloads the release asset directly without an embedded credential.

## Home, Battery details, and compact widget redesign
Both pages share a single statically drawn glass battery hero, a progress ring, and green/amber state colors. Home displays four compact status cards and one clear details action. Details shows grouped electrical metrics and a four-row battery information card. Both keep the canonical mRadio header and bottom navigation, use the shared Material 3 Expressive theme, and add no notifications, services, animations, or in-app logs. Launcher icon and widget use matching green #5BF19C, amber #FFB443, cyan #9BD6DE and purple #C7A9FC. To avoid clipping in 1×1 cells, current >=1000mA uses A (−1331mA→−1.33A) for both primary and secondary widget readings.

## Compact widget design update
The 1×1 widget uses a small state icon instead of charging-status text or a badge. AC charging uses a plug, wireless Qi uses radio-wave/charging marks, discharging uses a battery with a downward arrow, and full/idle use battery icons. Temperature and charge percentage update through the same data fingerprint as the electrical readings. Dark, Transparent, and Compact styles share consistent widget fields, and the border can be disabled. Temperature and percentage refreshes are included in the widget fingerprint so their visible changes are never skipped. **Foreground refresh defaults to 1 second**, configurable from 1 to 10 seconds; the explicitly saved interval drives each sampling cycle. Quit uses the same rounded PowerSettingsNew icon as mRadio.

## Background widget updates

The foreground battery-sampling service runs only while at least one ChargeWatt widget is installed. It reads current, voltage and charge state and updates the widget at the saved 1–10 second interval; the launcher and Android idle policies can still delay individual cycles. When the last widget is removed, the service stops.

Android requires the service to provide an ongoing notification. ChargeWatt does not request notification permission; when permission is denied, Android can hide the notification from the drawer while still showing the running service in system active-app controls. The service uses START_STICKY to request a restart if Android recreates its process and rechecks whether a widget still exists before sampling.

On USB power connect/disconnect, the receiver requests forced widget refreshes from new sticky battery snapshots rather than a cached activity broadcast, with delayed rereads to catch asynchronous power-state changes. The Wireless Qi icon uses charging green (#5BF19C), while the discharging battery icon uses amber.
