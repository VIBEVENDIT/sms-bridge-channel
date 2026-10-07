#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

package="com.hotelatgangnam.smsbridge"
apk="${1:-app/build/outputs/apk/debug/app-debug.apk}"

command -v adb >/dev/null || {
  echo "adb is required (Android platform-tools)." >&2
  exit 1
}

device_count="$(adb devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count+0 }')"
if [[ "$device_count" != "1" ]]; then
  echo "Connect exactly one unlocked Android phone with USB debugging enabled." >&2
  adb devices
  exit 1
fi
test -s "$apk" || {
  echo "APK not found: $apk (run ./scripts/qa.sh first)." >&2
  exit 1
}

adb install -r "$apk"
adb shell pm grant "$package" android.permission.RECEIVE_SMS
adb shell pm grant "$package" android.permission.SEND_SMS
adb shell pm grant "$package" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell cmd appops set "$package" RUN_IN_BACKGROUND allow 2>/dev/null || true
adb shell cmd appops set "$package" RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
adb shell dumpsys deviceidle whitelist "+$package" >/dev/null 2>&1 || true
adb shell am start -n "$package/.MainActivity"

echo
echo "Phone prepared; configure Slack tokens in the on-device screen."
echo "Verify the manufacturer's Battery setting also says Unrestricted / 제한 없음."
echo
adb shell dumpsys package "$package" \
  | rg 'android.permission.(RECEIVE_SMS|SEND_SMS|POST_NOTIFICATIONS): granted='
