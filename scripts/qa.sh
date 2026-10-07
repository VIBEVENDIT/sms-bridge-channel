#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if rg --hidden \
  --glob '!.git/**' \
  --glob '!scripts/qa.sh' \
  --glob '!docs/**' \
  --glob '!README.md' \
  'xox[baprs]-[A-Za-z0-9-]{20,}|xapp-[A-Za-z0-9-]{20,}' .; then
  echo "Refusing to build: a Slack credential-shaped value is tracked in the tree." >&2
  exit 1
fi

./gradlew --no-daemon \
  testDebugUnitTest \
  lintDebug \
  assembleDebug

apk="app/build/outputs/apk/debug/app-debug.apk"
test -s "$apk"

echo "Automated QA passed."
echo "APK: $apk"
echo "Real-phone RECEIVE_SMS/SEND_SMS and carrier delivery remain a deployment gate."
