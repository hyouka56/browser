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
  adb shell dumpsys notification --noredact > "$ARTIFACTS/notification-dump.txt" 2>&1 || true
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

# Verify Android registers Browser as an Open with target for HTML and XHTML files.
for html_mime in text/html application/xhtml+xml; do
  resolver_output="$(adb shell cmd package query-activities --brief \
    -a android.intent.action.VIEW -t "$html_mime" 2>&1 | tr -d '\r')"
  printf '%s\n' "$resolver_output" > "$ARTIFACTS/open-with-${html_mime//\//-}.txt"
  if ! grep -F "$PACKAGE" <<< "$resolver_output" >/dev/null; then
    echo "Browser is not registered in Android Open with for $html_mime." >&2
    echo "$resolver_output" >&2
    exit 1
  fi
done
echo "PASS: Android Open with resolver includes Browser for HTML and XHTML files." | tee "$ARTIFACTS/open-with-results.txt"

# Verify Browser is an eligible HTTP and HTTPS handler for Android's browser role.
for web_scheme in http https; do
  resolver_output="$(adb shell cmd package query-activities --brief \
    -a android.intent.action.VIEW \
    -c android.intent.category.DEFAULT \
    -c android.intent.category.BROWSABLE \
    -d "${web_scheme}://example.com/" \
    -p "$PACKAGE" 2>&1 | tr -d '\r')"
  printf '%s\n' "$resolver_output" > "$ARTIFACTS/default-browser-${web_scheme}.txt"
  if ! grep -F "$PACKAGE" <<< "$resolver_output" >/dev/null; then
    echo "Browser is not registered as a $web_scheme handler." >&2
    echo "$resolver_output" >&2
    exit 1
  fi
done
echo "PASS: Android resolves Browser for both HTTP and HTTPS VIEW intents." | tee "$ARTIFACTS/default-browser-results.txt"

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

wait_for_app_log() {
  local pattern="$1"
  local expected_count="$2"
  local timeout_seconds="$3"
  local elapsed=0
  while (( elapsed < timeout_seconds )); do
    local count
    count="$(adb logcat -d -s BrowserApp:V 2>/dev/null | grep -F -c "$pattern" || true)"
    if (( count >= expected_count )); then
      return 0
    fi
    if [[ -z "$(adb shell pidof "$PACKAGE" | tr -d '\r')" ]]; then
      echo "Browser process exited while waiting for app log '$pattern'." >&2
      return 1
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  echo "Timed out waiting for BrowserApp log '$pattern'." >&2
  adb logcat -d -s BrowserApp:V >&2 || true
  return 1
}

tap_accessibility_node() {
  local mode="$1"
  local selector="$2"
  local hierarchy="$ARTIFACTS/ui-hierarchy-$RANDOM.xml"
  adb shell uiautomator dump /sdcard/browser-window.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/browser-window.xml > "$hierarchy"
  local coordinates
  coordinates="$(python3 - "$hierarchy" "$mode" "$selector" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

path, mode, selector = sys.argv[1:]
root = ET.parse(path).getroot()
for node in root.iter("node"):
    value = node.attrib.get("content-desc" if mode == "desc" else "text", "")
    matches = value.startswith(selector) if mode == "desc" else value == selector
    if not matches or node.attrib.get("clickable") != "true":
        continue
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
    if not match:
        continue
    x1, y1, x2, y2 = map(int, match.groups())
    print((x1 + x2) // 2, (y1 + y2) // 2)
    raise SystemExit(0)
raise SystemExit(f"No clickable accessibility node matched {mode}={selector!r}")
PY
)"
  local x y
  read -r x y <<< "$coordinates"
  adb shell input tap "$x" "$y"
}

# Verify the actual PC toolbar button enables and disables desktop rendering mode.
adb logcat -c
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$ACTIVITY" \
  --es com.search.browser.TEST_URL "https://example.com/" \
  2>&1 | tee "$ARTIFACTS/desktop-site-launch.txt"
wait_for_log "PAGE_TITLE window=1 title=Example Domain" 1 120
wait_for_log "PAGE_LOAD_STATE loading=false" 1 120
tap_accessibility_node desc "Show desktop site"
wait_for_log "DESKTOP_SITE_MODE window=1 enabled=true" 1 30
wait_for_log "DESKTOP_PAGE_SCALE_APPLIED window=1" 1 30
wait_for_log "PAGE_LOAD_STATE loading=false" 2 120
DESKTOP_SCALE_LOG="$(adb logcat -d -s Browser:V 2>/dev/null | grep -F "DESKTOP_PAGE_SCALE_APPLIED window=1" | tail -n1 || true)"
if [[ ! "$DESKTOP_SCALE_LOG" =~ pageScaleFactor=0\.[0-9]+ ]]; then
  echo "Expected desktop mode to scale the 1280px viewport to the phone surface; got: $DESKTOP_SCALE_LOG" >&2
  adb logcat -d -s Browser:V >&2 || true
  exit 1
fi
adb exec-out screencap -p > "$ARTIFACTS/desktop-site-scaled.png"
tap_accessibility_node desc "Desktop site enabled."
wait_for_log "DESKTOP_SITE_MODE window=1 enabled=false" 1 30
wait_for_log "PAGE_LOAD_STATE loading=false" 3 120
printf 'PASS: PC control switched desktop mode on and back to mobile mode.\n' | tee "$ARTIFACTS/desktop-site-results.txt"

# A normal launch must open Android's default-browser chooser without a toolbar button.
adb logcat -c
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$ACTIVITY" 2>&1 | tee "$ARTIFACTS/default-browser-prompt-launch.txt"
wait_for_log "DEFAULT_BROWSER_PROMPT_REQUESTED" 1 30
ROLE_PICKER_DUMP="$ARTIFACTS/default-browser-role-picker-before-cancel.txt"
ROLE_PICKER_VISIBLE=false
role_picker_is_resumed() {
  grep -E 'topResumedActivity=|ResumedActivity:|mResumedActivity:|Resumed:' "$1" | \
    grep -F "RequestRoleActivity" >/dev/null
}
for attempt in $(seq 1 30); do
  adb shell dumpsys activity activities > "$ROLE_PICKER_DUMP" 2>&1
  if role_picker_is_resumed "$ROLE_PICKER_DUMP"; then
    ROLE_PICKER_VISIBLE=true
    break
  fi
  sleep 1
done
if [[ "$ROLE_PICKER_VISIBLE" != "true" ]]; then
  echo "Android's browser-role chooser did not become visible after the app requested it." >&2
  cat "$ROLE_PICKER_DUMP" >&2
  exit 1
fi
# Dismiss the real system dialog through its visible Cancel action. Some Android versions
# keep RequestRoleActivity resumed when BACK is sent, even though the dialog exposes Cancel.
ROLE_PICKER_DISMISSED=false
if tap_accessibility_node text "Cancel"; then
  for attempt in $(seq 1 15); do
    sleep 1
    adb shell dumpsys activity activities > "$ARTIFACTS/default-browser-role-picker-after-cancel.txt" 2>&1
    if ! role_picker_is_resumed "$ARTIFACTS/default-browser-role-picker-after-cancel.txt"; then
      ROLE_PICKER_DISMISSED=true
      break
    fi
  done
fi
# Retain a bounded BACK fallback for system UI variants without an accessible Cancel node.
if [[ "$ROLE_PICKER_DISMISSED" != "true" ]]; then
  for attempt in $(seq 1 5); do
    adb shell input keyevent KEYCODE_BACK
    sleep 1
    adb shell dumpsys activity activities > "$ARTIFACTS/default-browser-role-picker-after-cancel.txt" 2>&1
    if ! role_picker_is_resumed "$ARTIFACTS/default-browser-role-picker-after-cancel.txt"; then
      ROLE_PICKER_DISMISSED=true
      break
    fi
  done
fi
if [[ "$ROLE_PICKER_DISMISSED" != "true" ]]; then
  echo "Android's browser-role chooser remained in the foreground after BACK." >&2
  cat "$ARTIFACTS/default-browser-role-picker-after-cancel.txt" >&2
  exit 1
fi
DEFAULT_PROMPT_COUNT="$(adb logcat -d -s Browser:V 2>/dev/null | grep -F -c "DEFAULT_BROWSER_PROMPT_REQUESTED" || true)"
if [[ "$DEFAULT_PROMPT_COUNT" != "1" ]]; then
  echo "Expected exactly one browser-role prompt during the app launch; found $DEFAULT_PROMPT_COUNT." >&2
  adb logcat -d -s Browser:V >&2 || true
  exit 1
fi
adb shell am force-stop "$PACKAGE"
echo "PASS: default-browser chooser appears on app launch and can be dismissed cleanly." | tee "$ARTIFACTS/default-browser-prompt-results.txt"


run_adblock_phase() {
  local phase="$1"
  local expected_enabled="$2"
  local expected_blocked="$3"
  local create_second_window="${4:-false}"

  adb logcat -d -v threadtime > "$ARTIFACTS/adblock-$phase-previous-logcat.txt" 2>&1 || true
  adb logcat -c
  adb shell am force-stop "$PACKAGE"
  adb shell am start -W -n "$ACTIVITY" \
    --es com.search.browser.TEST_URL "https://example.com/" \
    --ez com.search.browser.TEST_ADBLOCK_PAGE true \
    --ez com.search.browser.TEST_ADBLOCK_MULTIWINDOW "$create_second_window" \
    2>&1 | tee "$ARTIFACTS/adblock-$phase-launch.txt"

  wait_for_log "CEF_ENGINE version=0.21.0 chromium=156.0.8078.96" 1 90
  wait_for_log "PAGE_TITLE window=1 title=Example Domain" 1 120
  wait_for_log "AD_BLOCK_CONFIG enabled=$expected_enabled tvLite=false" 1 60
  wait_for_app_log "EasyList loaded:" 1 180
  wait_for_app_log "YouTube ad rules loaded" 1 60

  local rule_count
  rule_count="$(adb logcat -d -s BrowserApp:V 2>/dev/null | sed -nE 's/.*EasyList loaded: ([0-9]+) rules.*/\1/p' | tail -n1)"
  if [[ ! "$rule_count" =~ ^[1-9][0-9]*$ ]]; then
    echo "EasyList did not report a positive rule count: '$rule_count'." >&2
    adb logcat -d -s BrowserApp:V >&2 || true
    exit 1
  fi

  wait_for_log "AD_BLOCK_RUNTIME_RESULT" 1 90
  local result_line blocked_count
  result_line="$(adb logcat -d -s Browser:V 2>/dev/null | grep -F "AD_BLOCK_RUNTIME_RESULT" | tail -n1)"
  if ! grep -F "allowed=true" <<< "$result_line" >/dev/null; then
    echo "The allowed same-origin request failed in ad-block phase '$phase': $result_line" >&2
    exit 1
  fi
  blocked_count="$(sed -nE 's/.*blockedRequests=([-0-9]+).*/\1/p' <<< "$result_line")"
  if [[ ! "$blocked_count" =~ ^[0-9]+$ ]]; then
    echo "Could not read Cefrium blocked-request count in phase '$phase': $result_line" >&2
    exit 1
  fi

  if [[ "$expected_blocked" == "yes" ]]; then
    if ! grep -F "adFailed=true" <<< "$result_line" >/dev/null || (( blocked_count < 1 )); then
      echo "Ad request was not blocked with EasyList enabled in phase '$phase': $result_line" >&2
      exit 1
    fi
  else
    if (( blocked_count != 0 )); then
      echo "Requests were still blocked while the ad blocker was disabled in phase '$phase': $result_line" >&2
      exit 1
    fi
  fi

  adb logcat -d -v threadtime > "$ARTIFACTS/adblock-$phase-logcat.txt" 2>&1
  printf 'PASS: adblock phase=%s enabled=%s EasyListRules=%s blockedRequests=%s allowedSameOrigin=true\n' \
    "$phase" "$expected_enabled" "$rule_count" "$blocked_count" | tee -a "$ARTIFACTS/adblock-results.txt"
}

# The actual EasyList host rule must block the ad endpoint without blocking
# a normal same-origin request. The test-only extra opens a second window.
run_adblock_phase "enabled-default" "true" "yes" "true"
wait_for_log "AD_BLOCK_TEST_MULTIWINDOW_CREATED windows=2" 1 30
wait_for_log "AD_BLOCK_CONFIG enabled=true tvLite=false" 2 90

# Use the actual shield control, not a synthetic state change.
tap_accessibility_node desc "Ad blocker on"
wait_for_log "AD_BLOCK_TOGGLED enabled=false windows=2" 1 30
WINDOW_TOGGLE_COUNT="$(adb logcat -d -s Browser:V 2>/dev/null | grep -F "AD_BLOCK_WINDOW_TOGGLED" | grep -F "enabled=false" | wc -l | tr -d ' ')"
if [[ "$WINDOW_TOGGLE_COUNT" != "2" ]]; then
  echo "Expected the shield to update both open windows; found $WINDOW_TOGGLE_COUNT successful updates." >&2
  adb logcat -d -s Browser:V >&2 || true
  exit 1
fi
echo "PASS: shield toggle updated both open browser windows." | tee -a "$ARTIFACTS/adblock-results.txt"
sleep 2

# Reboot the app process to prove the disabled preference is persisted.
run_adblock_phase "disabled-persisted" "false" "no"

# Re-enable it through the actual shield and verify the enabled setting survives restart.
tap_accessibility_node desc "Ad blocker off"
wait_for_log "AD_BLOCK_TOGGLED enabled=true windows=1" 1 30
sleep 2
run_adblock_phase "enabled-restored" "true" "yes"

# Verify an imported HTML file is rendered as an isolated local document by Chromium.
adb logcat -c
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$ACTIVITY" \
  --ez com.search.browser.TEST_OPEN_HTML_FILE true \
  2>&1 | tee "$ARTIFACTS/local-html-open-launch.txt"
wait_for_log "LOCAL_HTML_TEST_FIXTURE_CREATED" 1 30
wait_for_log "LOCAL_HTML_TEST_FILE_IMPORTED" 1 30
wait_for_log "LOCAL_HTML_FILE_OPENED" 1 30
wait_for_log "LOCAL_HTML_DOCUMENT_VERIFIED success=true" 1 60
wait_for_log "LOCAL_HTML_DOM_DIAGNOSTICS" 1 60
wait_for_log "PAGE_LOAD_STATE loading=false" 1 60
adb logcat -d -v threadtime > "$ARTIFACTS/local-html-open-logcat.txt" 2>&1
if ! grep -F "Local HTML Test" "$ARTIFACTS/local-html-open-logcat.txt" >/dev/null || \
   ! grep -F "Local HTML file rendered successfully" "$ARTIFACTS/local-html-open-logcat.txt" >/dev/null; then
  echo "Local HTML document title/body verification failed." >&2
  grep -F "LOCAL_HTML_DOM_DIAGNOSTICS" "$ARTIFACTS/local-html-open-logcat.txt" >&2 || true
  exit 1
fi
printf 'PASS: imported local HTML document title and body rendered by Chromium.\n' | tee "$ARTIFACTS/local-html-results.txt"

# Continue with the original renderer-recovery suite using a normal launch.
adb logcat -d -v threadtime > "$ARTIFACTS/adblock-enabled-restored-final-logcat.txt" 2>&1
adb logcat -c
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$ACTIVITY" \
  --es com.search.browser.TEST_URL "https://example.com/" \
  --ez com.search.browser.TEST_CRASH_RENDERER true \
  2>&1 | tee "$ARTIFACTS/launch.txt"

wait_for_log "CEF_ENGINE version=0.21.0 chromium=156.0.8078.96" 1 90
wait_for_log "PAGE_TITLE window=1 title=Example Domain" 1 120
wait_for_log "PAGE_LOAD_STATE loading=false" 1 120
wait_for_log "DARK_APP_CHROME_ENABLED websiteColorsUntouched=true" 1 60
wait_for_log "PAGE_BITMAP_CAPTURED url=https://example.com/" 1 60
wait_for_log "TEST_RENDERER_CRASH_ARMED" 1 30
sleep 1
adb exec-out screencap -p > "$ARTIFACTS/page-before-renderer-restart.png"

# Debug-only test: chrome://crash terminates the Chromium renderer. The app process
# must survive, receive Cefrium's callback, and replace the failed browser instance.
wait_for_log "CHROMIUM_RENDERER_TERMINATED" 1 60
wait_for_log "CHROMIUM_RECOVERY_STARTED" 1 30
wait_for_log "PAGE_TITLE window=1 title=Example Domain" 2 120
wait_for_log "CHROMIUM_RECOVERY_COMPLETED" 1 120
wait_for_log "DARK_APP_CHROME_ENABLED websiteColorsUntouched=true" 2 60
wait_for_log "PAGE_BITMAP_CAPTURED url=https://example.com/" 2 60
sleep 1
adb exec-out screencap -p > "$ARTIFACTS/page-after-renderer-recovery.png"

# Verify actual Chromium downloads and Android notifications using a trusted HTTPS origin.
DOWNLOAD_TEST_URL="https://example.com/"

if (( EXPECTED_API >= 33 )); then
  adb shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS
fi
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$ACTIVITY" \
  --es com.search.browser.TEST_URL "$DOWNLOAD_TEST_URL" \
  --ez com.search.browser.TEST_DOWNLOAD_PAGE true \
  2>&1 | tee "$ARTIFACTS/launch-download-test.txt"
wait_for_log "PAGE_TITLE window=1 title=Download Test" 1 60
wait_for_log "DOWNLOAD_HANDLER_REGISTERED" 1 30
sleep 1

# Android 35's emulator may show a system ANR dialog for Pixel Launcher after
# renderer recovery. It is not the Browser process. Choose Wait so the modal
# dialog does not swallow the test-page taps; never close the Browser under test.
if adb shell dumpsys window 2>/dev/null | grep -F "Application Not Responding" >/dev/null; then
  echo "System ANR dialog detected for another app; selecting Wait before download taps."
  adb shell input tap 360 1340
  for attempt in $(seq 1 10); do
    if ! adb shell dumpsys window 2>/dev/null | grep -F "Application Not Responding" >/dev/null; then
      break
    fi
    sleep 1
  done
  if adb shell dumpsys window 2>/dev/null | grep -F "Application Not Responding" >/dev/null; then
    echo "System ANR dialog remained after choosing Wait." >&2
    exit 1
  fi
fi

# The app must be resumed after renderer recovery while the download page is still visible.
# This assertion belongs before the later intentional removal of the task stack.
adb shell dumpsys activity activities > "$ARTIFACTS/activity-dump-before-task-removal.txt"
if ! grep -E "mResumedActivity|topResumedActivity" "$ARTIFACTS/activity-dump-before-task-removal.txt" | grep -F "$PACKAGE" >/dev/null; then
  echo "Browser is not the resumed Activity after renderer recovery and before task removal." >&2
  exit 1
fi

# Test buttons fill large fixed portions of the viewport.
adb shell input tap 540 600
wait_for_log "DOWNLOAD_BEFORE_CALLBACK id=" 1 60
wait_for_log "DOWNLOAD_REQUEST_ACCEPTED name=download-test.txt" 1 60
wait_for_log "DOWNLOAD_FILE_SAVED name=download-test.txt" 1 90
TEXT_DOWNLOAD_FILE="/sdcard/Download/download-test.txt"
adb shell ls -l "$TEXT_DOWNLOAD_FILE" > "$ARTIFACTS/media-download-test.txt.txt" 2>&1 || true
if ! adb shell test -s "$TEXT_DOWNLOAD_FILE"; then
  echo "Downloaded text file was not found in public Downloads or is empty." >&2
  adb shell ls -la /sdcard/Download >&2 || true
  exit 1
fi
adb shell dumpsys notification --noredact > "$ARTIFACTS/notification-download-test.txt.txt"
if ! grep -F "download-test.txt" "$ARTIFACTS/notification-download-test.txt.txt" >/dev/null; then
  echo "The completed text-file notification was not posted." >&2
  exit 1
fi

adb shell input tap 540 1120
wait_for_log "DOWNLOAD_REQUEST_ACCEPTED name=download-test.apk" 1 60
wait_for_log "DOWNLOAD_FILE_SAVED name=download-test.apk" 1 90
APK_DOWNLOAD_FILE="/sdcard/Download/download-test.apk"
adb shell ls -l "$APK_DOWNLOAD_FILE" > "$ARTIFACTS/media-download-test.apk.txt" 2>&1 || true
if ! adb shell test -s "$APK_DOWNLOAD_FILE"; then
  echo "Downloaded APK fixture was not found in public Downloads or is empty." >&2
  adb shell ls -la /sdcard/Download >&2 || true
  exit 1
fi
adb shell dumpsys notification --noredact > "$ARTIFACTS/notification-download-test.apk.txt"
if ! grep -F "download-test.apk" "$ARTIFACTS/notification-download-test.apk.txt" >/dev/null; then
  echo "The completed APK notification was not posted." >&2
  exit 1
fi
if ! grep -F "pkg=$PACKAGE" "$ARTIFACTS/notification-download-test.apk.txt" >/dev/null; then
  echo "Download notification was not registered for $PACKAGE." >&2
  exit 1
fi

# Start a delayed debug download, then remove the Activity task while the
# transfer is pending. The foreground service must keep Cefrium alive until
# the file is copied into the public Downloads directory. The API35 emulator
# has a taller WebView viewport, placing the 90vh button lower on screen.
if (( EXPECTED_API >= 35 )); then
  adb shell input tap 540 2025
else
  adb shell input tap 540 1900
fi
wait_for_log "DOWNLOAD_BACKGROUND_ALLOW_DELAYED" 1 30
# While the first request is deliberately waiting for its CEF allow callback,
# start another file from the same browser window. Both must finish and be saved.
adb shell input tap 540 1550
wait_for_log "DOWNLOAD_REQUEST_ACCEPTED name=download-concurrent-test.txt" 1 60
wait_for_log "DOWNLOAD_FILE_SAVED name=download-concurrent-test.txt" 1 90
CONCURRENT_DOWNLOAD_FILE="/sdcard/Download/download-concurrent-test.txt"
adb shell ls -l "$CONCURRENT_DOWNLOAD_FILE" > "$ARTIFACTS/concurrent-download-test.txt.txt" 2>&1 || true
if ! adb shell test -s "$CONCURRENT_DOWNLOAD_FILE"; then
  echo "Concurrent download was not found in public Downloads or is empty." >&2
  adb shell ls -la /sdcard/Download >&2 || true
  exit 1
fi
printf 'PASS: a second download completed while the same browser had a pending download.\n' | tee "$ARTIFACTS/concurrent-download-results.txt"
wait_for_log "DOWNLOAD_KEEP_ALIVE_SERVICE_STARTED" 1 30
wait_for_log "DOWNLOAD_KEEP_ALIVE_REQUESTED" 1 30
STACK_ID="$(adb shell dumpsys activity activities | grep -F "$PACKAGE" | sed -nE 's/.*StackId=([0-9]+).*/\1/p; s/.*Task\{[^#]*#([0-9]+).*/\1/p' | head -n1 | tr -d '\r')"
if [[ -z "$STACK_ID" ]]; then
  echo "Could not determine the Browser stack/task ID for the close-app download test." >&2
  adb shell dumpsys activity activities >&2 || true
  exit 1
fi
echo "Removing Browser task stack $STACK_ID while download is pending."
adb shell am stack remove "$STACK_ID"
wait_for_log "BROWSER_CLOSE_DEFERRED_FOR_DOWNLOAD" 1 30
wait_for_log "DOWNLOAD_KEEP_ALIVE_TASK_REMOVED" 1 30
wait_for_log "DOWNLOAD_BACKGROUND_ALLOWING" 1 30
wait_for_log "DOWNLOAD_FILE_SAVED name=download-background-test.txt" 1 90
BACKGROUND_DOWNLOAD_FILE="/sdcard/Download/download-background-test.txt"
adb shell ls -l "$BACKGROUND_DOWNLOAD_FILE" > "$ARTIFACTS/background-download-after-task-removal.txt" 2>&1 || true
if ! adb shell test -s "$BACKGROUND_DOWNLOAD_FILE"; then
  echo "Background download did not finish after the Browser task was removed." >&2
  adb shell ls -la /sdcard/Download >&2 || true
  exit 1
fi
wait_for_log "DOWNLOAD_KEEP_ALIVE_SERVICE_STOPPED" 1 30
wait_for_log "BROWSER_CLOSED_AFTER_DOWNLOAD" 1 30

PAGE_LOG="$(adb logcat -d -s Browser:V 2>/dev/null)"
for required in \
  "CEF_ENGINE version=0.21.0 chromium=156.0.8078.96" \
  "PAGE_TITLE window=1 title=Example Domain" \
  "PAGE_LOAD_STATE loading=false" \
  "DARK_APP_CHROME_ENABLED websiteColorsUntouched=true" \
  "CHROMIUM_RENDERER_TERMINATED" \
  "CHROMIUM_RECOVERY_STARTED" \
  "CHROMIUM_RECOVERY_COMPLETED"; do
  if ! grep -F "$required" <<<"$PAGE_LOG" >/dev/null; then
    echo "Missing runtime assertion: $required" >&2
    exit 1
  fi
done

DARK_APP_CHROME_COUNT="$(grep -F -c "DARK_APP_CHROME_ENABLED websiteColorsUntouched=true" <<<"$PAGE_LOG" || true)"
if (( DARK_APP_CHROME_COUNT < 2 )); then
  echo "Dark app chrome was not configured for both the original and recovered browser tabs." >&2
  exit 1
fi


adb logcat -d -v threadtime > "$ARTIFACTS/logcat.txt" 2>&1
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
# No resumed Browser Activity is expected here: the test deliberately removed
# its task. The app process must nevertheless remain alive until the transfer
# and public Downloads copy have finished.
if [[ -z "$(adb shell pidof "$PACKAGE" | tr -d '\r')" ]]; then
  echo "Browser process exited before the background download assertions completed." >&2
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
    visible = dark = light = total = 0
    for y in range(y0, y1):
        row = rows[y]
        for x in range(x0, x1):
            i = x * channels
            total += 1
            rgb = row[i], row[i+1], row[i+2]
            if max(rgb) > 35:
                visible += 1
            if max(rgb) <= 40:
                dark += 1
            if min(rgb) >= 180:
                light += 1
    denominator = max(total, 1)
    return visible / denominator, dark / denominator, light / denominator

before = folder / "page-before-renderer-restart.png"
after = folder / "page-after-renderer-recovery.png"
ratio_before, dark_ratio_before, light_ratio_before = visible_content_ratio(before) if before.is_file() else (0.0, 0.0, 0.0)
ratio_after, dark_ratio_after, light_ratio_after = visible_content_ratio(after) if after.is_file() else (0.0, 0.0, 0.0)
adblock_results_path = folder / "adblock-results.txt"
adblock_result_lines = adblock_results_path.read_text().splitlines() if adblock_results_path.is_file() else []
local_html_log_path = folder / "local-html-open-logcat.txt"
local_html_log = local_html_log_path.read_text(errors="replace") if local_html_log_path.is_file() else ""

def phase_result(name, enabled):
    return next(
        (line for line in adblock_result_lines if f"phase={name} " in line and f"enabled={enabled}" in line),
        ""
    )

def field_int(line, field):
    marker = field + "="
    if marker not in line:
        return -1
    try:
        return int(line.split(marker, 1)[1].split()[0])
    except (ValueError, IndexError):
        return -1

enabled_default = phase_result("enabled-default", "true")
disabled_persisted = phase_result("disabled-persisted", "false")
enabled_restored = phase_result("enabled-restored", "true")

report = {
    "application_id": "com.search.browser",
    "engine": "Chromium via Cefrium",
    "cefrium_version": "0.21.0",
    "chromium_version": "156.0.8078.96",
    "android_api": int(api),
    "process_id_after_test": pid,
    "screenshot_metrics": {
        "before_recovery_visible_pixel_ratio": round(ratio_before, 4),
        "after_recovery_visible_pixel_ratio": round(ratio_after, 4),
        "before_recovery_dark_pixel_ratio": round(dark_ratio_before, 4),
        "after_recovery_dark_pixel_ratio": round(dark_ratio_after, 4),
        "before_recovery_light_pixel_ratio": round(light_ratio_before, 4),
        "after_recovery_light_pixel_ratio": round(light_ratio_after, 4),
        "minimum_required_after_recovery_visible_ratio": 0.01,
        "minimum_required_native_website_light_pixel_ratio": 0.55,
    },
    "checks": {
        "apk_installed": (folder / "install.txt").exists(),
        "cefrium_chromium_engine_logged": "CEF_ENGINE version=0.21.0 chromium=156.0.8078.96" in log,
        "page_title_verified": "PAGE_TITLE window=1 title=Example Domain" in log,
        "page_load_completed": "PAGE_LOAD_STATE loading=false" in log,
        "android_open_with_lists_browser_for_html_and_xhtml": (
            (folder / "open-with-results.txt").is_file() and
            "PASS: Android Open with resolver includes Browser for HTML and XHTML files." in
                (folder / "open-with-results.txt").read_text()
        ),
        "local_html_file_opened_and_rendered": (
            "LOCAL_HTML_TEST_FIXTURE_CREATED" in local_html_log and
            "LOCAL_HTML_TEST_FILE_IMPORTED" in local_html_log and
            "LOCAL_HTML_DOM_DIAGNOSTICS" in local_html_log and
            "Local HTML Test" in local_html_log and
            "Local HTML file rendered successfully" in local_html_log and
            "PAGE_LOAD_STATE loading=false" in local_html_log
        ),
        "dark_app_chrome_enabled_for_both_renderers": log.count("DARK_APP_CHROME_ENABLED websiteColorsUntouched=true") >= 2,
        "website_native_colors_not_forced_dark": light_ratio_before >= 0.55 and light_ratio_after >= 0.55,
        "surface_bitmap_captured_before_crash": log.count("PAGE_BITMAP_CAPTURED url=https://example.com/") >= 1,
        "renderer_termination_callback": "CHROMIUM_RENDERER_TERMINATED" in log,
        "fresh_browser_created": "CHROMIUM_RECOVERY_STARTED" in log,
        "page_rendered_after_recovery": "CHROMIUM_RECOVERY_COMPLETED" in log and log.count("PAGE_BITMAP_CAPTURED url=https://example.com/") >= 2,
        "app_process_survived": bool(pid),
        "before_recovery_screenshot_saved": before.is_file(),
        "after_recovery_screenshot_saved": after.is_file(),
        "before_recovery_screenshot_captured": before.is_file(),
        "after_recovery_screenshot_visibly_rendered": ratio_after >= 0.01,
        "text_file_download_saved": "DOWNLOAD_FILE_SAVED name=download-test.txt" in log,
        "apk_file_download_saved": "DOWNLOAD_FILE_SAVED name=download-test.apk" in log,
        "concurrent_download_saved_while_another_was_pending": (
            "DOWNLOAD_FILE_SAVED name=download-concurrent-test.txt" in log and
            (folder / "concurrent-download-test.txt.txt").is_file() and
            "PASS: a second download completed while the same browser had a pending download." in
                (folder / "concurrent-download-results.txt").read_text()
        ),
        "public_download_copy_was_verified_before_publish": log.count("DOWNLOAD_PUBLIC_SAVE_VERIFIED") >= 4,
        "download_notification_posted": "DOWNLOAD_NOTIFICATION_POSTED name=download-test.apk" in log,
        "download_handler_registered": "DOWNLOAD_HANDLER_REGISTERED" in log,
        "download_callback_invoked": "DOWNLOAD_BEFORE_CALLBACK id=" in log,
        "foreground_download_service_started": "DOWNLOAD_KEEP_ALIVE_SERVICE_STARTED" in log,
        "activity_close_deferred_for_download": "BROWSER_CLOSE_DEFERRED_FOR_DOWNLOAD" in log,
        "browser_task_removed_during_download": "DOWNLOAD_KEEP_ALIVE_TASK_REMOVED" in log,
        "background_download_finished_after_task_removal": "DOWNLOAD_FILE_SAVED name=download-background-test.txt" in log and (folder / "background-download-after-task-removal.txt").is_file(),
        "foreground_service_stopped_after_download": "DOWNLOAD_KEEP_ALIVE_SERVICE_STOPPED" in log,
        "browser_closed_after_download": "BROWSER_CLOSED_AFTER_DOWNLOAD" in log,
        "easylist_loaded_with_positive_rule_count": (
            field_int(enabled_default, "EasyListRules") > 0 and
            field_int(disabled_persisted, "EasyListRules") > 0 and
            field_int(enabled_restored, "EasyListRules") > 0
        ),
        "known_ad_request_blocked_when_enabled": (
            field_int(enabled_default, "blockedRequests") > 0 and
            "allowedSameOrigin=true" in enabled_default
        ),
        "normal_same_origin_request_remains_allowed": (
            "allowedSameOrigin=true" in enabled_default and
            "allowedSameOrigin=true" in disabled_persisted and
            "allowedSameOrigin=true" in enabled_restored
        ),
        "disabled_ad_block_state_persists_and_does_not_block": (
            field_int(disabled_persisted, "blockedRequests") == 0
        ),
        "ad_blocking_restored_after_reenable": (
            field_int(enabled_restored, "blockedRequests") > 0
        ),
        "shield_toggle_updates_every_open_window": (
            "PASS: shield toggle updated both open browser windows." in adblock_result_lines
        ),
    },
}
report["result"] = "PASS" if all(report["checks"].values()) else "FAIL"
(folder / "report.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
if report["result"] != "PASS":
    raise SystemExit(1)
PY

echo "PASS: API $EXPECTED_API rendered Example Domain with native website colors and dark app chrome, recovered Chromium, and completed download checks." | tee "$ARTIFACTS/result.txt"
echo "PASS: API $EXPECTED_API - readable dark app chrome, unchanged website colors, Chromium recovery, downloads, notifications, and background download continuation." >> "$GITHUB_STEP_SUMMARY"
