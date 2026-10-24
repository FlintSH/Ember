#!/usr/bin/env bash
# Runs Ember's end-to-end journey on a booted emulator and collects screenshots,
# a screen recording, and logs. CI calls this from android-emulator-runner.
#
#   FLARE_URL=http://10.0.2.2:3000 FLARE_TOKEN=flr_... EMBER_PACKAGE=dev.fl1nt.ember \
#   E2E_LABEL=api36 scripts/ci/run-e2e.sh app.apk e2e.apk out/
#
# `am instrument` exits 0 even when tests fail, so the result is read from its output.
set -euo pipefail

app_apk=$1
test_apk=$2
out=$3
: "${FLARE_URL:?}" "${FLARE_TOKEN:?}" "${EMBER_PACKAGE:?}" "${E2E_LABEL:?}"
mkdir -p "$out"

adb wait-for-device
# Booting reports done before the package manager and shared storage settle, and
# Android 17 images can restart their system services once right after boot.
wait_ready() {
  for _ in $(seq 1 60); do
    if adb shell '[ "$(getprop sys.boot_completed)" = 1 ] && pm path android >/dev/null 2>&1 && ls /sdcard/Download >/dev/null 2>&1'; then
      return 0
    fi
    sleep 3
  done
  echo "::error::The emulator didn't finish starting"
  return 1
}
wait_ready
install() {
  for attempt in 1 2 3 4; do
    adb install -r "$1" && return 0
    echo "Installing $1 failed (attempt $attempt). Retrying."
    sleep 10
  done
  return 1
}
# A localhost address reaches the host's Flare through adb. The emulator's own
# alias for the host, 10.0.2.2, needs nothing extra.
case "$FLARE_URL" in
  *://localhost:*) adb reverse tcp:3000 tcp:3000 ;;
esac
install "$app_apk"
install "$test_apk"
wait_ready
adb shell rm -rf /sdcard/Download/ember-e2e /sdcard/Download/ember-journey.mp4

adb shell screenrecord --bit-rate 6000000 --time-limit 180 /sdcard/Download/ember-journey.mp4 &
recorder=$!

status=0
adb shell am instrument -w -r \
  -e flareUrl "$FLARE_URL" \
  -e token "$FLARE_TOKEN" \
  -e emberPackage "$EMBER_PACKAGE" \
  -e label "$E2E_LABEL" \
  dev.fl1nt.ember.e2e/androidx.test.runner.AndroidJUnitRunner > "$out/instrumentation.txt" || status=$?

adb shell pkill -INT screenrecord || true
wait "$recorder" || true
sleep 3
adb pull /sdcard/Download/ember-e2e "$out/" || true
adb pull /sdcard/Download/ember-journey.mp4 "$out/" || true
adb logcat -d > "$out/logcat.txt" || true

# The fixture token is disposable, but keep it out of uploaded logs anyway.
for file in "$out"/*.txt; do sed -i "s|$FLARE_TOKEN|[redacted]|g" "$file"; done

if [ "$status" -ne 0 ] || ! grep -q "^OK (" "$out/instrumentation.txt"; then
  echo "::error::End-to-end tests failed on $E2E_LABEL"
  grep -E "INSTRUMENTATION_STATUS: (test|stack)=|FAILURES|Tests run" "$out/instrumentation.txt" | head -80 || true
  exit 1
fi
grep "^OK (" "$out/instrumentation.txt"
