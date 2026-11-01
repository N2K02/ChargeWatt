# chargeWatt

chargeWatt is a minimal Android 1×1 home-screen charging and discharging widget.

## Features

- Shows charging (green) and discharging (amber) automatically, including wireless Qi charging. Current sign comes from BatteryManager, so a connected charger with net drain is correctly displayed as negative.
- Shows battery-side watts (W), current (mA), voltage (V), and battery percentage.
- Dark or transparent widget themes.
- Choose W or mA as the main value.
- User-selectable refresh 1–10 seconds, default 1 second.
- One consistent compact header, Home, Settings (Details, Logs, Updates), and Quit.
- No notifications, notification permission, foreground service, wake locks, continuous animation, or network telemetry.
- Android 8+ (minSdk 26), Android 17 compile/target API 37.

## Important timing and measurement limits

The 1-second interval is implemented when the dashboard is resumed. The UI lifecycle cancels polling when the screen is paused; only value changes cause RemoteViews widget redraw. In the background, Android cannot guarantee second-by-second widget updates without a foreground-running service. The widget is updated by power connection events and a WorkManager periodic fallback (minimum 15 minutes). The launcher may delay those updates.

Watts = absolute battery current in microamperes multiplied by battery voltage in millivolts, divided by 1,000,000,000. The sign is determined by charging status. This is **battery-side power**, not the power delivered by the wall charger, USB brick, or Qi pad. Unsupported battery current reports as unavailable rather than a fake value.

## CPU and GPU review

- Foreground listener caches battery broadcasts for voltage, temperature, level and state; only signed instantaneous current is queried on a dedicated I/O coroutine every 1–10 seconds while RESUMED.
- The 1-second setting remains the default. UI state is updated only when readings change.
- Widget redraws are fingerprint-cached in memory rather than persistently written to SharedPreferences every second. Lightweight text-only partial RemoteViews are sent for changes, with a periodic full refresh to bound system_server history.
- Background charge/discharge transitions are shown at the next permitted periodic update. Android 8+ restrictions prevent relying on manifest-declared implicit power broadcasts for immediate notification-free updates.
- No frame-driven graphics or continuous Compose/GPU animation.
- WorkManager is canceled when the last widget is removed; Quit persistently pauses widget polling until the app is reopened or a new widget is placed. No widget-host update can silently restart the background job after Quit. No receiver for charging/battery power changes is left enabled when the app is backgrounded.
- Status logs are written only upon charging source or state changes; entries older than 7 days are pruned on the next change.
- Real CPU/GPU/power results require an APK installed on a device, and cannot be inferred from static code review. Measure with Android Studio Profiler, adb shell dumpsys gfxinfo dev.n2k02.chargewatt, and adb shell dumpsys batterystats --charged dev.n2k02.chargewatt.

## Build channels and signing

Same toolchain as other N2K02 apps: Gradle 9.6, Android Gradle Plugin 9.4, Kotlin 2.4, Java 17, API 37, pinned n2k02theme 0.1.4 and n2k02updater 0.1.19.

Github Actions requires existing repository secrets N2K02_PACKAGES_TOKEN and SIGNING_PASSPHRASE. Signing uses the verified encrypted MRadio bundle, pinned to Git blob SHA 5203c1095062ad22099f76d319605ae5b79c0720. Both experimental PR and stable main release APKs are built from the release variant and signed by the same identity with application ID dev.n2k02.chargewatt. Experimental builds publish prerelease APKs; main builds publish stable APKs. Build fails closed when credentials are missing.

For local compilation, use Gradle 9.6 with Android SDK and N2K02_PACKAGES_TOKEN, then run: gradle testDebugUnitTest lintDebug assembleDebug. Local debug builds are not signed for in-place updates over release APKs.

The pinned shared updater dependency is included; the current Updates screen opens Github Releases rather than pretending that downloading/installing in-app is already implemented.

## Startup crash diagnostics

AndroidLogger tag: `ChargeWattStartup`. The dashboard no longer schedules WorkManager during `Activity.onCreate`; scheduling happens when a widget is enabled. Optional widget-manager, battery receiver registration, and device telemetry failures are isolated and logged rather than terminating the Activity. If a crash still occurs, enable monitoring in McpAdbAndroidLogger and relaunch chargeWatt to capture the full `AndroidRuntime` FATAL EXCEPTION stack trace. Passing CI cannot prove a physical-device startup succeeds.
