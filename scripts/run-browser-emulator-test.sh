#!/usr/bin/env bash
set -Eeuo pipefail

APK="${1:?Debug APK path is required}"
EXPECTED_API="${2:?Expected Android API level is required}"
PACKAGE="com.search.browser"
ACTIVITY="$PACKAGE/.MainActivity"
ARTIFACTS="artifacts/api-$EXPECTED_API"
mkdir -p "$ARTIFACTS"

collect_evidence() {
  adb logcat -d -v threadtime > "$ARTIFACTS/logcat.txt" 2>&1 || true
  adb exec-out screencap -p > "$ARTIFACTS/final-screen.png" 2>/dev/null || true
  adb shell getprop > "$ARTIFACTS/device-properties.txt" 2>&1 || true
  adb shell dumpsys package "$PACKAGE" > "$ARTIFACTS/package-dump.txt" 2>&1 || true
  adb shell dumpsys activity activities > "$ARTIFACTS/activity-dump.txt" 2>&1 || true
  adb shell dumpsys webviewupdate > "$ARTIFACTS/webview-provider.txt" 2>&1 || true
}
trap collect_evidence EXIT

adb wait-for-device
ACTUAL_API="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$ACTUAL_API" != "$EXPECTED_API" ]]; then
  echo "Expected Android API $EXPECTED_API but emulator reports $ACTUAL_API" >&2
  exit 1
fi

{
  echo "Expected API: $EXPECTED_API"
  echo "Actual API: $ACTUAL_API"
  echo "ABI list: $(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"
  echo "Device: $(adb shell getprop ro.product.model | tr -d '\r')"
} | tee "$ARTIFACTS/device.txt"

adb logcat -c
adb install --no-streaming -r "$APK" | tee "$ARTIFACTS/install.txt"
adb shell pm clear "$PACKAGE" >/dev/null
adb shell am force-stop "$PACKAGE"

adb shell am start -W -n "$ACTIVITY" \
  --es com.search.browser.TEST_URL "https://example.com/" \
  --ez com.search.browser.TEST_CRASH_RENDERER true \
  2>&1 | tee "$ARTIFACTS/launch.txt"

wait_for_log() {
  local pattern="$1"
  local expected_count="${2:-1}"
  local timeout_seconds="${3:-60}"
  local elapsed=0
  while (( elapsed < timeout_seconds )); do
    local count
    count="$(adb logcat -d -s Browser:I Browser:W Browser:E 2>/dev/null | grep -F -c "$pattern" || true)"
    if (( count >= expected_count )); then
      return 0
    fi
    if [[ -z "$(adb shell pidof "$PACKAGE" | tr -d '\r')" ]]; then
      echo "Browser process exited while waiting for '$pattern'." >&2
      return 1
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  echo "Timed out waiting for '$pattern' (expected count: $expected_count)." >&2
  return 1
}

wait_for_log "PAGE_DOM_READY" 1 90
wait_for_log "PAGE_VISUAL_STATE_READY" 1 30
adb exec-out screencap -p > "$ARTIFACTS/page-before-renderer-restart.png"

wait_for_log "RENDERER_GONE" 1 30
wait_for_log "RENDERER_RECOVERY_STARTED" 1 30
wait_for_log "RENDERER_RECOVERY_COMPLETED" 1 60
wait_for_log "PAGE_VISUAL_STATE_READY" 2 30

adb exec-out screencap -p > "$ARTIFACTS/page-after-renderer-recovery.png"

PAGE_LOG="$(adb logcat -d -s Browser:I Browser:W Browser:E 2>/dev/null)"
if ! grep -F "Example Domain" <<<"$PAGE_LOG" >/dev/null; then
  echo "The loaded page did not report the expected Example Domain title." >&2
  exit 1
fi
if ! grep -F "WEBVIEW_PROVIDER" <<<"$PAGE_LOG" >/dev/null; then
  echo "The app did not report the active Android WebView provider." >&2
  exit 1
fi
if grep -F "FATAL EXCEPTION" "$ARTIFACTS/logcat.txt" >/dev/null; then
  echo "A fatal application exception was found in Logcat." >&2
  exit 1
fi

APP_PID="$(adb shell pidof "$PACKAGE" | tr -d '\r')"
if [[ -z "$APP_PID" ]]; then
  echo "Browser process did not remain alive after renderer recovery." >&2
  exit 1
fi

adb shell dumpsys activity activities > "$ARTIFACTS/activity-dump-after-test.txt"
if ! grep -E "mResumedActivity|topResumedActivity" "$ARTIFACTS/activity-dump-after-test.txt" | grep -F "$PACKAGE" >/dev/null; then
  echo "Browser is not the resumed Activity after renderer recovery." >&2
  exit 1
fi

# Persist the current logs before the report reads them; the EXIT trap refreshes them again.
adb logcat -d -v threadtime > "$ARTIFACTS/logcat.txt" 2>&1

test -s "$ARTIFACTS/page-before-renderer-restart.png"
test -s "$ARTIFACTS/page-after-renderer-recovery.png"

python3 - "$EXPECTED_API" "$APP_PID" "$ARTIFACTS" <<'PY'
import json
import sys
from pathlib import Path

api, pid, folder = sys.argv[1], sys.argv[2], Path(sys.argv[3])
log = (folder / "logcat.txt").read_text(errors="replace")
report = {
    "application_id": "com.search.browser",
    "android_api": int(api),
    "process_id_after_test": pid,
    "checks": {
        "apk_installed": (folder / "install.txt").exists(),
        "webview_dom_ready": "PAGE_DOM_READY" in log and "Example Domain" in log,
        "first_visual_frame": "PAGE_VISUAL_STATE_READY" in log,
        "renderer_termination_callback": "RENDERER_GONE" in log,
        "renderer_recreated": "RENDERER_RECOVERY_STARTED" in log,
        "page_rendered_after_recovery": "RENDERER_RECOVERY_COMPLETED" in log,
        "app_process_survived": bool(pid),
        "active_webview_provider_logged": "WEBVIEW_PROVIDER" in log,
    },
}
report["result"] = "PASS" if all(report["checks"].values()) else "FAIL"
(folder / "report.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
if report["result"] != "PASS":
    raise SystemExit(1)
PY

echo "PASS: API $EXPECTED_API loaded Example Domain, drew a visual frame, recovered from a renderer termination, and remained foregrounded." | tee "$ARTIFACTS/result.txt"
echo "PASS: API $EXPECTED_API - WebView page rendering and renderer recovery." >> "$GITHUB_STEP_SUMMARY"
