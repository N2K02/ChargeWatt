#!/usr/bin/env bash
set -euo pipefail
PKG=dev.n2k02.chargewatt
adb install -r "$APK_NAME.apk"
for attempt in 1 2; do
  echo "chargeWatt release startup attempt $attempt"
  adb shell am force-stop "$PKG" || true
  adb logcat -c
  # am start can exit successfully even when AndroidX Startup then crashes.
  adb shell am start -W -n "$PKG/.MainActivity"
  sleep 8
  adb logcat -d -v time > startup-logcat.txt
  if grep -A20 "FATAL EXCEPTION" startup-logcat.txt | grep -F "Process: $PKG"; then
    echo "::error::Release APK crashed on startup"
    grep -A35 "FATAL EXCEPTION" startup-logcat.txt | tail -80 || true
    exit 1
  fi
  if ! adb shell pidof "$PKG" >/dev/null; then
    echo "::error::chargeWatt process died on launch"
    grep -E 'AndroidRuntime|FATAL EXCEPTION|WorkDatabase_Impl|ChargeWattStartup|dev.n2k02.chargewatt' startup-logcat.txt | tail -100 || true
    exit 1
  fi
done
echo "PASS: Release-signed APK launched twice and remained alive (API 35)"
