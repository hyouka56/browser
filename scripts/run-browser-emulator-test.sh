#!/usr/bin/env bash
set -Eeuo pipefail

APK="${1:?Debug APK path is required}"
EXPECTED_API="${2:?Expected Android API level is required}"
PACKAGE="com.search.browser"
ACTIVITY="$PACKAGE/.MainActivity"
ARTIFACTS="artifacts/api-$EXPECTED_API"
mkdir -p "$ARTIFACTS"

if [[ ! -f "$APK" ]]; then
  echo "Debug APK was not found at: $APK" >&2
  find "$(dirname "$APK")" -maxdepth 3 -type f -print >&2 || true
  exit 1
fi

collect_evidence() {
  adb logcat -d -v threadtime > "$ARTIFACTS/logcat.txt" 2>&1 || true
  adb exec-out screencap -p > "$ARTIFACTS/final-screen.png" 2>/dev/null || true
  adb shell getprop > "$ARTIFACTS/device-properties.txt" 2>&1 || true
  adb shell dumpsys package "$PACKAGE" > "$ARTIFACTS/package-dump.txt" 2>&1 || true
  adb shell dumpsys activity activities > "$ARTIFACTS/activity-dump.txt" 2>&1 || true
  adb shell dumpsys meminfo "$PACKAGE" > "$ARTIFACTS/memory-dump.txt" 2>&1 || true
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
    count="$(adb logcat -d -s Browser:V 2>/dev/null | grep -F -c "$pattern" || true)"
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

wait_for_log "CEF_ENGINE version=0.20.0 chromium=156.0.8078.88" 1 90
wait_for_log "PAGE_TITLE title=Example Domain" 1 120
wait_for_log "PAGE_LOAD_STATE loading=false" 1 120
wait_for_log "PAGE_BITMAP_CAPTURED url=https://example.com/" 1 60
wait_for_log "TEST_RENDERER_CRASH_ARMED" 1 30
sleep 1
adb exec-out screencap -p > "$ARTIFACTS/page-before-renderer-restart.png"

# Debug-only test: chrome://crash terminates the Chromium renderer. The app process
# must survive, receive Cefrium's callback, and replace the failed browser instance.
wait_for_log "CHROMIUM_RENDERER_TERMINATED" 1 60
wait_for_log "CHROMIUM_RECOVERY_STARTED" 1 30
wait_for_log "PAGE_TITLE title=Example Domain" 2 120
wait_for_log "CHROMIUM_RECOVERY_COMPLETED" 1 120
wait_for_log "PAGE_BITMAP_CAPTURED url=https://example.com/" 2 60
sleep 1
adb exec-out screencap -p > "$ARTIFACTS/page-after-renderer-recovery.png"

PAGE_LOG="$(adb logcat -d -s Browser:V 2>/dev/null)"
for required in \
  "CEF_ENGINE version=0.20.0 chromium=156.0.8078.88" \
  "PAGE_TITLE title=Example Domain" \
  "PAGE_LOAD_STATE loading=false" \
  "CHROMIUM_RENDERER_TERMINATED" \
  "CHROMIUM_RECOVERY_STARTED" \
  "CHROMIUM_RECOVERY_COMPLETED"; do
  if ! grep -F "$required" <<<"$PAGE_LOG" >/dev/null; then
    echo "Missing runtime assertion: $required" >&2
    exit 1
  fi
done

if grep -F "FATAL EXCEPTION" "$ARTIFACTS/logcat.txt" >/dev/null; then
  echo "A fatal application exception was found in Logcat." >&2
  exit 1
fi

APP_PID="$(adb shell pidof "$PACKAGE" | tr -d '\r')"
if [[ -z "$APP_PID" ]]; then
  echo "Browser process did not remain alive after Chromium renderer recovery." >&2
  exit 1
fi

adb shell dumpsys activity activities > "$ARTIFACTS/activity-dump-after-test.txt"
if ! grep -E "mResumedActivity|topResumedActivity" "$ARTIFACTS/activity-dump-after-test.txt" | grep -F "$PACKAGE" >/dev/null; then
  echo "Browser is not the resumed Activity after renderer recovery." >&2
  exit 1
fi

adb logcat -d -v threadtime > "$ARTIFACTS/logcat.txt" 2>&1
test -s "$ARTIFACTS/page-before-renderer-restart.png"
test -s "$ARTIFACTS/page-after-renderer-recovery.png"

python3 - "$EXPECTED_API" "$APP_PID" "$ARTIFACTS" <<'PY'
import json
import struct
import sys
import zlib
from pathlib import Path

api, pid, folder = sys.argv[1], sys.argv[2], Path(sys.argv[3])
log = (folder / "logcat.txt").read_text(errors="replace")

def visible_content_ratio(path: Path) -> float:
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"Not a PNG: {path}")
    pos = 8
    width = height = bit_depth = color_type = None
    compressed = bytearray()
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos+4])[0]
        kind = data[pos+4:pos+8]
        payload = data[pos+8:pos+8+length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, bit_depth, color_type, compression, filtering, interlace = struct.unpack(">IIBBBBB", payload)
        elif kind == b"IDAT":
            compressed.extend(payload)
        elif kind == b"IEND":
            break
    if bit_depth != 8 or color_type not in (2, 6) or interlace != 0:
        raise ValueError(f"Unsupported PNG: depth={bit_depth}, type={color_type}, interlace={interlace}")
    channels = 3 if color_type == 2 else 4
    stride = width * channels
    raw = zlib.decompress(compressed)
    rows = []
    offset = 0
    previous = bytearray(stride)
    for _ in range(height):
        filt = raw[offset]
        scan = bytearray(raw[offset+1:offset+1+stride])
        offset += stride + 1
        for i in range(stride):
            left = scan[i-channels] if i >= channels else 0
            up = previous[i]
            upper_left = previous[i-channels] if i >= channels else 0
            if filt == 1:
                scan[i] = (scan[i] + left) & 255
            elif filt == 2:
                scan[i] = (scan[i] + up) & 255
            elif filt == 3:
                scan[i] = (scan[i] + ((left + up)//2)) & 255
            elif filt == 4:
                p = left + up - upper_left
                pa, pb, pc = abs(p-left), abs(p-up), abs(p-upper_left)
                predictor = left if pa <= pb and pa <= pc else (up if pb <= pc else upper_left)
                scan[i] = (scan[i] + predictor) & 255
            elif filt != 0:
                raise ValueError(f"Unknown PNG filter {filt}")
        rows.append(scan)
        previous = scan

    y0, y1 = height // 10, height * 9 // 10
    x0, x1 = width // 20, width * 19 // 20
    visible = total = 0
    for y in range(y0, y1):
        row = rows[y]
        for x in range(x0, x1):
            i = x * channels
            total += 1
            if max(row[i], row[i+1], row[i+2]) > 35:
                visible += 1
    return visible / max(total, 1)

before = folder / "page-before-renderer-restart.png"
after = folder / "page-after-renderer-recovery.png"
ratio_before = visible_content_ratio(before) if before.is_file() else 0.0
ratio_after = visible_content_ratio(after) if after.is_file() else 0.0
report = {
    "application_id": "com.search.browser",
    "engine": "Chromium via Cefrium",
    "cefrium_version": "0.20.0",
    "chromium_version": "156.0.8078.88",
    "android_api": int(api),
    "process_id_after_test": pid,
    "screenshot_metrics": {
        "before_recovery_visible_pixel_ratio": round(ratio_before, 4),
        "after_recovery_visible_pixel_ratio": round(ratio_after, 4),
        "minimum_required_visible_ratio": 0.08,
    },
    "checks": {
        "apk_installed": (folder / "install.txt").exists(),
        "cefrium_chromium_engine_logged": "CEF_ENGINE version=0.20.0 chromium=156.0.8078.88" in log,
        "page_title_verified": "PAGE_TITLE title=Example Domain" in log,
        "page_load_completed": "PAGE_LOAD_STATE loading=false" in log,
        "surface_bitmap_captured_before_crash": log.count("PAGE_BITMAP_CAPTURED url=https://example.com/") >= 1,
        "renderer_termination_callback": "CHROMIUM_RENDERER_TERMINATED" in log,
        "fresh_browser_created": "CHROMIUM_RECOVERY_STARTED" in log,
        "page_rendered_after_recovery": "CHROMIUM_RECOVERY_COMPLETED" in log and log.count("PAGE_BITMAP_CAPTURED url=https://example.com/") >= 2,
        "app_process_survived": bool(pid),
        "before_recovery_screenshot_saved": before.is_file(),
        "after_recovery_screenshot_saved": after.is_file(),
        "before_recovery_screenshot_visibly_rendered": ratio_before >= 0.08,
        "after_recovery_screenshot_visibly_rendered": ratio_after >= 0.08,
    },
}
report["result"] = "PASS" if all(report["checks"].values()) else "FAIL"
(folder / "report.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
if report["result"] != "PASS":
    raise SystemExit(1)
PY

echo "PASS: API $EXPECTED_API drew Example Domain with Chromium and recovered after renderer termination." | tee "$ARTIFACTS/result.txt"
echo "PASS: API $EXPECTED_API - Chromium surface rendering and renderer recovery." >> "$GITHUB_STEP_SUMMARY"
