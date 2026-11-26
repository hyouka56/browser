#!/usr/bin/env bash
set -Eeuo pipefail

APK="${1:?Debug APK path is required}"
EXPECTED_API="${2:?Expected Android API level is required}"
PACKAGE="com.search.browser"
ACTIVITY="$PACKAGE/.ui.MainActivity"
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
  adb shell dumpsys meminfo > "$ARTIFACTS/system-memory-dump.txt" 2>&1 || true
  adb shell dumpsys meminfo com.google.android.apps.nexuslauncher > "$ARTIFACTS/launcher-memory-dump.txt" 2>&1 || true
  adb shell dumpsys cpuinfo > "$ARTIFACTS/cpuinfo.txt" 2>&1 || true
  adb shell dumpsys connectivity > "$ARTIFACTS/connectivity-dump.txt" 2>&1 || true
  adb shell dumpsys dnsresolver > "$ARTIFACTS/dnsresolver-dump.txt" 2>&1 || true
  adb shell ip route show > "$ARTIFACTS/ip-routes.txt" 2>&1 || true
}
trap collect_evidence EXIT

adb wait-for-device
# Match OPPO A73 (CPH2095) screen geometry for this Android 12 reproduction.
# 408 dpi is a logical-density approximation, not a ColorOS firmware clone.
adb shell wm size 1080x2400
adb shell wm density 408
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
  echo "Screen size override: $(adb shell wm size | tr -d '\r' | tr '\n' ';')"
  echo "Screen density override: $(adb shell wm density | tr -d '\r' | tr '\n' ';')"
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
  if [[ -z "$coordinates" ]]; then
    echo "No clickable accessibility node matched $mode='$selector'; tap was not sent." >&2
    return 1
  fi
  local x y
  read -r x y <<< "$coordinates"
  if [[ ! "$x" =~ ^[0-9]+$ || ! "$y" =~ ^[0-9]+$ ]]; then
    echo "Invalid tap coordinates for $mode='$selector': $coordinates" >&2
    return 1
  fi
  adb shell input tap "$x" "$y"
  echo "ACTION: sent tap to accessibility node $mode='$selector' at $x,$y"
}


tap_external_anr_wait_action() {
  local window_dump
  window_dump="$(adb shell dumpsys window 2>/dev/null || true)"
  if ! grep -F "Application Not Responding" <<< "$window_dump" >/dev/null; then
    return 2
  fi

  local hierarchy="$ARTIFACTS/ui-hierarchy-anr-$RANDOM.xml"
  if ! adb shell uiautomator dump /sdcard/browser-anr.xml >/dev/null 2>&1; then
    window_dump="$(adb shell dumpsys window 2>/dev/null || true)"
    if ! grep -F "Application Not Responding" <<< "$window_dump" >/dev/null; then
      return 2
    fi
    echo "Could not inspect the active system ANR dialog." >&2
    return 1
  fi
  adb exec-out cat /sdcard/browser-anr.xml > "$hierarchy"

  local alert_title
  if ! alert_title="$(python3 - "$hierarchy" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
for node in root.iter("node"):
    if node.attrib.get("resource-id") == "android:id/alertTitle":
        print(node.attrib.get("text", ""))
        raise SystemExit(0)
raise SystemExit("No system ANR alert title was found")
PY
  )"; then
    window_dump="$(adb shell dumpsys window 2>/dev/null || true)"
    if ! grep -F "Application Not Responding" <<< "$window_dump" >/dev/null; then
      return 2
    fi
    echo "The ANR window is present, but its title could not be verified." >&2
    return 1
  fi

  if [[ "$alert_title" != *"isn't responding"* ]] || grep -qi "browser" <<< "$alert_title"; then
    echo "Refusing to dismiss an ANR dialog that is not confirmed to belong to another app: $alert_title" >&2
    return 1
  fi

  echo "External ANR dialog detected for '$alert_title'; choosing its accessible Wait action."
  if ! tap_accessibility_node text "Wait"; then
    window_dump="$(adb shell dumpsys window 2>/dev/null || true)"
    if ! grep -F "Application Not Responding" <<< "$window_dump" >/dev/null; then
      return 2
    fi
    echo "The external ANR dialog did not expose a clickable Wait action." >&2
    return 1
  fi
  return 0
}

browser_window_has_focus() {
  local window_dump="$1"
  ! grep -F "Application Not Responding" <<< "$window_dump" >/dev/null \
    && grep -Eq 'mCurrentFocus=.*com[.]search[.]browser|mFocusedApp=.*com[.]search[.]browser' <<< "$window_dump"
}

wait_for_browser_window_focus() {
  local timeout_seconds="${1:-15}"
  local required_samples="${2:-2}"
  local elapsed=0
  local stable_samples=0
  local anr_dismissals=0
  while (( elapsed < timeout_seconds )); do
    local window_dump
    window_dump="$(adb shell dumpsys window 2>/dev/null || true)"
    if grep -F "Application Not Responding" <<< "$window_dump" >/dev/null; then
      if (( anr_dismissals >= 3 )); then
        echo "External system ANR dialog kept recurring after $anr_dismissals recovery attempts." >&2
        adb shell dumpsys window >&2 || true
        return 1
      fi
      local tap_status=0
      tap_external_anr_wait_action || tap_status=$?
      if (( tap_status == 1 )); then
        echo "Could not safely recover the external system ANR dialog." >&2
        return 1
      fi
      if (( tap_status == 0 )); then
        anr_dismissals=$((anr_dismissals + 1))
      fi
      stable_samples=0
    elif browser_window_has_focus "$window_dump"; then
      stable_samples=$((stable_samples + 1))
      if (( stable_samples >= required_samples )); then
        return 0
      fi
    else
      stable_samples=0
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  echo "Browser did not hold foreground window focus for $required_samples consecutive samples (external ANR dialogs dismissed: $anr_dismissals)." >&2
  adb shell dumpsys window >&2 || true
  return 1
}

dismiss_external_anr_dialog_if_present() {
  local window_dump
  window_dump="$(adb shell dumpsys window 2>/dev/null || true)"
  if ! grep -F "Application Not Responding" <<< "$window_dump" >/dev/null; then
    return 0
  fi

  local tap_status=0
  tap_external_anr_wait_action || tap_status=$?
  if (( tap_status == 2 )); then
    return 0
  fi
  if (( tap_status != 0 )); then
    return 1
  fi

  # wait_for_browser_window_focus also clears a recurring external ANR overlay,
  # but only after verifying its title and using the system's Wait action.
  if ! wait_for_browser_window_focus 20 4; then
    echo "The external system ANR dialog was dismissed, but Browser did not regain stable window focus." >&2
    adb shell dumpsys window >&2 || true
    return 1
  fi
  echo "PASS: external system ANR dialog dismissed; Browser foreground remained stable."
  return 0
}

accessibility_desc_exists() {
  local selector="$1"
  local hierarchy="$ARTIFACTS/ui-hierarchy-state-$RANDOM.xml"
  adb shell uiautomator dump /sdcard/browser-state.xml >/dev/null 2>&1 || return 1
  adb exec-out cat /sdcard/browser-state.xml > "$hierarchy" || return 1
  python3 - "$hierarchy" "$selector" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
selector = sys.argv[2]
for node in root.iter("node"):
    if node.attrib.get("content-desc", "").startswith(selector) and node.attrib.get("clickable") == "true":
        raise SystemExit(0)
raise SystemExit(1)
PY
}

accessibility_text_exists() {
  local selector="$1"
  local hierarchy="$ARTIFACTS/ui-hierarchy-state-$RANDOM.xml"
  adb shell uiautomator dump /sdcard/browser-state.xml >/dev/null 2>&1 || return 1
  adb exec-out cat /sdcard/browser-state.xml > "$hierarchy" || return 1
  python3 - "$hierarchy" "$selector" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
selector = sys.argv[2]
for node in root.iter("node"):
    if selector in node.attrib.get("text", ""):
        raise SystemExit(0)
raise SystemExit(1)
PY
}

# A tap is only considered successful when both the app's transition log and
# the resulting accessible button state agree. If a system ANR steals the tap,
# recover the overlay and retry only while the original state is still visible.
wait_for_desktop_site_transition() {
  local before_desc="$1"
  local after_desc="$2"
  local expected_enabled="$3"
  local expected_count="$4"
  local timeout_seconds="${5:-30}"
  local elapsed=0
  local retries=0

  if ! dismiss_external_anr_dialog_if_present; then
    return 1
  fi
  if ! wait_for_browser_window_focus 15 2; then
    return 1
  fi
  tap_accessibility_node_with_system_recovery desc "$before_desc" || return 1
  while (( elapsed < timeout_seconds )); do
    local matching_count
    matching_count="$(adb logcat -d -s Browser:V 2>/dev/null | grep -F -c "DESKTOP_SITE_MODE window=1 enabled=$expected_enabled" || true)"
    if (( matching_count >= expected_count )); then
      if accessibility_desc_exists "$after_desc"; then
        echo "PASS: desktop-site transition confirmed by app log and accessibility state: $after_desc"
        return 0
      fi
    fi

    local window_dump
    window_dump="$(adb shell dumpsys window 2>/dev/null || true)"
    if grep -F "Application Not Responding" <<< "$window_dump" >/dev/null; then
      if ! dismiss_external_anr_dialog_if_present; then
        return 1
      fi
    elif ! browser_window_has_focus "$window_dump"; then
      # Wait for the app to own the input window before considering a retry.
      if ! wait_for_browser_window_focus 8 2; then
        return 1
      fi
    fi

    # Never repeat a toggle after its app event was logged: that could undo a
    # successful change. Retry only when the event is absent and the old state
    # is still visibly present after ANR/focus recovery.
    if (( matching_count < expected_count && retries < 2 && elapsed >= 4 && elapsed % 4 == 0 )); then
      if accessibility_desc_exists "$before_desc"; then
        if ! wait_for_browser_window_focus 8 2; then
          return 1
        fi
        echo "RETRY: desktop-site event not emitted; original control state is still present."
        if tap_accessibility_node desc "$before_desc"; then
          retries=$((retries + 1))
        fi
      fi
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done

  echo "Timed out waiting for desktop-site transition to '$after_desc' (expected event count: $expected_count; retries: $retries)." >&2
  adb shell dumpsys window >&2 || true
  adb logcat -d -s Browser:V >&2 || true
  adb shell uiautomator dump /sdcard/browser-transition-failure.xml >/dev/null 2>&1 || true
  adb exec-out cat /sdcard/browser-transition-failure.xml > "$ARTIFACTS/ui-hierarchy-transition-failure.xml" 2>/dev/null || true
  return 1
}

tap_accessibility_node_with_system_recovery() {
  local mode="$1"
  local selector="$2"
  for attempt in $(seq 1 4); do
    # Clear a verified external ANR overlay before inspecting app controls.
    if ! dismiss_external_anr_dialog_if_present; then
      return 1
    fi
    if tap_accessibility_node "$mode" "$selector"; then
      return 0
    fi
    # The overlay may appear between the preflight check and the UI hierarchy dump.
    if ! dismiss_external_anr_dialog_if_present; then
      return 1
    fi
    sleep 1
  done
  echo "Could not tap accessibility node after checking for external system ANR dialogs: $mode=$selector" >&2
  return 1
}

# Verify the actual PC toolbar button enables and disables desktop rendering mode.
adb logcat -c
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$ACTIVITY" \
  --es com.search.browser.TEST_URL "https://example.com/" \
  2>&1 | tee "$ARTIFACTS/desktop-site-launch.txt"
wait_for_log "PAGE_TITLE window=1 title=Example Domain" 1 120
wait_for_log "PAGE_LOAD_STATE loading=false" 1 120
wait_for_log "DESKTOP_SITE_INITIAL_STATE window=1 enabled=false" 1 30
wait_for_log "MOBILE_USER_AGENT_BASELINE_CAPTURED window=1" 1 30
wait_for_desktop_site_transition "Show desktop site" "Desktop site enabled. Tap to use mobile site" true 1 30
wait_for_log "DESKTOP_PAGE_SCALE_RESET window=1 success=true" 1 30
wait_for_log "DESKTOP_VIEWPORT_ZOOM_APPLIED window=1" 1 30
wait_for_log "PAGE_LOAD_STATE loading=false" 2 120
wait_for_log "PAGE_BITMAP_CAPTURED url=https://example.com/" 2 90
wait_for_log "DESKTOP_DOM_DIAGNOSTICS window=1" 1 30
if ! dismiss_external_anr_dialog_if_present; then
  echo "Could not safely dismiss an external system ANR before desktop-mode screenshot capture." >&2
  exit 1
fi
DESKTOP_ZOOM_LOG="$(adb logcat -d -s Browser:V 2>/dev/null | grep -F "DESKTOP_VIEWPORT_ZOOM_APPLIED window=1" | tail -n1 || true)"
if [[ ! "$DESKTOP_ZOOM_LOG" =~ targetCssWidth=1280 ]] || [[ ! "$DESKTOP_ZOOM_LOG" =~ appliedAfterLoad=true ]]; then
  echo "Expected native page zoom to be applied after navigation for a 1280 CSS-pixel desktop target; got: $DESKTOP_ZOOM_LOG" >&2
  adb logcat -d -s Browser:V >&2 || true
  exit 1
fi
DESKTOP_DOM_LOG="$(adb logcat -d -s Browser:V 2>/dev/null | grep -F "DESKTOP_DOM_DIAGNOSTICS window=1" | tail -n1 || true)"
python3 - "$DESKTOP_DOM_LOG" <<'PY' | tee -a "$ARTIFACTS/desktop-site-results.txt"
import json, re, sys
line = sys.argv[1]
match = re.search(r"result=(.*)$", line)
if not match:
    raise SystemExit(f"Desktop DOM diagnostics did not return JSON: {line}")
data = json.loads(match.group(1))
if isinstance(data, str):
    data = json.loads(data)
body = data.get("bodyText", "")
width = int(data.get("innerWidth", 0))
client = int(data.get("clientWidth", 0))
scroll = int(data.get("scrollWidth", 0))
visual = float(data.get("visualViewportWidth", 0))
visual_scale = float(data.get("visualViewportScale", 0))
dpr = float(data.get("devicePixelRatio", 0))
if "Example Domain" not in data.get("title", "") or "This domain is for use in documentation examples" not in body:
    raise SystemExit(f"Desktop page content is missing: {data}")
if width < 768 or client < 768:
    raise SystemExit(f"Desktop CSS viewport did not reach the wide layout breakpoint: {data}")
if abs(visual - width) > max(8, width * 0.02):
    raise SystemExit(f"Visual viewport is narrower than the CSS layout viewport: {data}")
if abs(visual_scale - 1.0) > 0.05:
    raise SystemExit(f"Page scale was not reset to 1.0: {data}")
if abs(dpr - 1.0) > 0.05:
    raise SystemExit(f"Desktop DPR is not the ordinary 1.0 scale: {data}")
if scroll > client + 8:
    raise SystemExit(f"Desktop page still overflows horizontally: {data}")
print(f"PASS: desktop page rendered at {width}px; visualViewport={visual}px, pageScale={visual_scale:.3f}, DPR={dpr:.3f}; no horizontal overflow")
PY
sleep 1
adb exec-out screencap -p > "$ARTIFACTS/desktop-site-scaled.png"
wait_for_desktop_site_transition "Desktop site enabled." "Show desktop site" false 1 30
wait_for_log "PAGE_LOAD_STATE loading=false" 3 120
wait_for_log "MOBILE_USER_AGENT_OVERRIDE_RESTORED window=1" 1 30
wait_for_log "MOBILE_VIEWPORT_ZOOM_RESET window=1" 1 30
wait_for_log "MOBILE_VIEWPORT_DIAGNOSTICS window=1 innerWidth=" 2 30
MOBILE_DOM_LOG="$(adb logcat -d -s Browser:V 2>/dev/null | grep -F "MOBILE_VIEWPORT_DIAGNOSTICS window=1" | tail -n1 || true)"
python3 - "$MOBILE_DOM_LOG" <<'PY' | tee -a "$ARTIFACTS/desktop-site-results.txt"
import re, sys
match = re.search(r"innerWidth=(\d+)", sys.argv[1])
if not match or int(match.group(1)) > 600:
    raise SystemExit(f"Mobile CSS viewport was not restored after disabling desktop mode: {sys.argv[1]}")
print(f"PASS: mobile CSS viewport restored to {match.group(1)}px")
PY
wait_for_log "MOBILE_USER_AGENT_DIAGNOSTICS window=1" 2 45
MOBILE_UA_LOG="$(adb logcat -d -s Browser:V 2>/dev/null | grep -F "MOBILE_USER_AGENT_DIAGNOSTICS window=1" | tail -n1 || true)"
python3 - "$MOBILE_UA_LOG" <<'PY' | tee -a "$ARTIFACTS/desktop-site-results.txt"
import json, re, sys
match = re.search(r"result=(.*)$", sys.argv[1])
if not match:
    raise SystemExit(f"Mobile user-agent diagnostics missing: {sys.argv[1]}")
data = json.loads(match.group(1))
if isinstance(data, str):
    data = json.loads(data)
ua = data.get("userAgent", "")
if "Windows NT" in ua or "Android" not in ua or data.get("mobile") is not True or data.get("platform") != "Android":
    raise SystemExit(f"Android mobile user-agent and metadata were not restored after disabling PC mode: {data}")
print("PASS: Android mobile user-agent, Android navigator.platform, and mobile UA metadata restored after disabling PC mode")
PY
# Verify the restored mobile identity against a real Google search page, then retain its screenshot.
GOOGLE_SEARCH_URL="https://www.google.com/search?q=hi"
# Reuse the current tab through MainActivity.onNewIntent instead of typing into the app address field.
adb shell am start --activity-single-top -W -n "$ACTIVITY" -a android.intent.action.VIEW -d "$GOOGLE_SEARCH_URL" 2>&1 | tee "$ARTIFACTS/google-mobile-search-launch.txt"
wait_for_log "PAGE_LOAD_STATE loading=false" 4 120
wait_for_log "MOBILE_USER_AGENT_DIAGNOSTICS window=1" 3 45
sleep 3
if ! dismiss_external_anr_dialog_if_present; then
  echo "Could not safely dismiss an external system ANR before Google screenshot capture." >&2
  exit 1
fi
adb exec-out screencap -p > "$ARTIFACTS/google-mobile-search.png"
adb shell uiautomator dump /sdcard/google-mobile-search.xml >/dev/null 2>&1
adb exec-out cat /sdcard/google-mobile-search.xml > "$ARTIFACTS/google-mobile-search-ui.xml"
if [[ ! -s "$ARTIFACTS/google-mobile-search.png" ]]; then
  echo "Google mobile search screenshot was not captured." >&2
  exit 1
fi
printf 'PASS: Google search page loaded in mobile mode before the PC-mode test; screenshot captured.\n' | tee -a "$ARTIFACTS/desktop-site-results.txt"

# Reproduce the user's exact flow: keep Google open, tap PC, and inspect the actual desktop page.
wait_for_desktop_site_transition "Show desktop site" "Desktop site enabled. Tap to use mobile site" true 2 30
wait_for_log "PAGE_LOAD_STATE loading=false" 5 120
wait_for_log "DESKTOP_PAGE_SCALE_RESET window=1 success=true" 2 45
wait_for_log "DESKTOP_VIEWPORT_ZOOM_APPLIED window=1" 2 45
wait_for_log "DESKTOP_DOM_DIAGNOSTICS window=1" 2 45
sleep 3
if ! dismiss_external_anr_dialog_if_present; then
  echo "Could not safely dismiss an external system ANR before Google screenshot capture." >&2
  exit 1
fi
adb exec-out screencap -p > "$ARTIFACTS/google-desktop-search.png"
adb shell uiautomator dump /sdcard/google-desktop-search.xml >/dev/null 2>&1
adb exec-out cat /sdcard/google-desktop-search.xml > "$ARTIFACTS/google-desktop-search-ui.xml"
GOOGLE_DESKTOP_DOM_LOG="$(adb logcat -d -s Browser:V 2>/dev/null | grep -F "DESKTOP_DOM_DIAGNOSTICS window=1" | tail -n1 || true)"
python3 - "$GOOGLE_DESKTOP_DOM_LOG" <<'PY' | tee -a "$ARTIFACTS/desktop-site-results.txt"
import json, re, sys
match = re.search(r"result=(.*)$", sys.argv[1])
if not match:
    raise SystemExit(f"Google PC-mode DOM diagnostics missing: {sys.argv[1]}")
data = json.loads(match.group(1))
if isinstance(data, str):
    data = json.loads(data)
title = data.get("title", "")
body = data.get("bodyText", "")
width = int(data.get("innerWidth", 0))
client = int(data.get("clientWidth", 0))
scroll = int(data.get("scrollWidth", 0))
visual = float(data.get("visualViewportWidth", 0))
visual_scale = float(data.get("visualViewportScale", 0))
dpr = float(data.get("devicePixelRatio", 0))
if "Google" not in title or not body.strip():
    raise SystemExit(f"Google page did not render in PC mode: {data}")
if width < 768 or client < 768:
    raise SystemExit(f"Google did not switch to a desktop-width CSS viewport: {data}")
if abs(visual - width) > max(8, width * 0.02):
    raise SystemExit(f"Google visual viewport is narrower than its layout viewport: {data}")
if abs(visual_scale - 1.0) > 0.05 or abs(dpr - 1.0) > 0.05:
    raise SystemExit(f"Google desktop page has unexpected zoom or DPR: {data}")
if scroll > client + 8:
    raise SystemExit(f"Google desktop page has horizontal overflow: {data}")
print(f"PASS: Google desktop search page rendered in PC mode at {width}px; visualViewport={visual}px, pageScale={visual_scale:.3f}, DPR={dpr:.3f}; no horizontal overflow.")
PY
if [[ ! -s "$ARTIFACTS/google-desktop-search.png" ]] || [[ "$(wc -c < "$ARTIFACTS/google-desktop-search.png")" -le 10000 ]]; then
  echo "Google PC-mode screenshot was not captured correctly." >&2
  exit 1
fi

# Turn PC mode off on the same Google tab and verify the mobile layout is restored.
wait_for_desktop_site_transition "Desktop site enabled. Tap to use mobile site" "Show desktop site" false 2 30
wait_for_log "PAGE_LOAD_STATE loading=false" 6 120
wait_for_log "MOBILE_USER_AGENT_OVERRIDE_RESTORED window=1" 2 45
wait_for_log "MOBILE_VIEWPORT_ZOOM_RESET window=1" 2 30
wait_for_log "MOBILE_VIEWPORT_DIAGNOSTICS window=1 innerWidth=" 4 30
wait_for_log "MOBILE_USER_AGENT_DIAGNOSTICS window=1" 4 45
sleep 3
if ! dismiss_external_anr_dialog_if_present; then
  echo "Could not safely dismiss an external system ANR before Google screenshot capture." >&2
  exit 1
fi
adb exec-out screencap -p > "$ARTIFACTS/google-mobile-search-restored.png"
MOBILE_GOOGLE_UA_LOG="$(adb logcat -d -s Browser:V 2>/dev/null | grep -F "MOBILE_USER_AGENT_DIAGNOSTICS window=1" | tail -n1 || true)"
python3 - "$MOBILE_GOOGLE_UA_LOG" <<'PY' | tee -a "$ARTIFACTS/desktop-site-results.txt"
import json, re, sys
match = re.search(r"result=(.*)$", sys.argv[1])
if not match:
    raise SystemExit(f"Google mobile user-agent diagnostics missing: {sys.argv[1]}")
data = json.loads(match.group(1))
if isinstance(data, str):
    data = json.loads(data)
ua = data.get("userAgent", "")
if "Windows NT" in ua or "Android" not in ua or data.get("mobile") is not True or data.get("platform") != "Android":
    raise SystemExit(f"Google mobile identity was not restored: {data}")
print("PASS: Google mobile user-agent, viewport and mobile metadata restored after PC mode.")
PY
if [[ ! -s "$ARTIFACTS/google-mobile-search-restored.png" ]] || [[ "$(wc -c < "$ARTIFACTS/google-mobile-search-restored.png")" -le 10000 ]]; then
  echo "Google restored mobile screenshot was not captured correctly." >&2
  exit 1
fi
printf 'PASS: Google search was tested in mobile and PC modes; desktop and restored-mobile screenshots captured.\n' | tee -a "$ARTIFACTS/desktop-site-results.txt"
printf 'PASS: PC control switched desktop mode on and back to mobile mode on Google.\n' | tee -a "$ARTIFACTS/desktop-site-results.txt"

# Google may place an optional feedback survey over the search results. Only
# dismiss it when its exact identifying prompt is present.
if accessibility_text_exists "Please help us improve Google."; then
  echo "INFO: Google feedback dialog detected; selecting its explicit No thanks action."
  tap_accessibility_node_with_system_recovery text "No thanks"
  for attempt in $(seq 1 10); do
    if ! accessibility_text_exists "Please help us improve Google."; then
      echo "PASS: Google feedback dialog dismissed; search controls are accessible again."
      break
    fi
    sleep 1
  done
  if accessibility_text_exists "Please help us improve Google."; then
    echo "Google feedback dialog remained visible after selecting No thanks." >&2
    exit 1
  fi
fi

# Focus the restored Google search field and capture the exact state in which its keyboard controls should be visible.
tap_accessibility_node_with_system_recovery text "hi"
sleep 2
if ! dismiss_external_anr_dialog_if_present; then
  echo "Could not safely dismiss an external system ANR before Google screenshot capture." >&2
  exit 1
fi
adb exec-out screencap -p > "$ARTIFACTS/google-mobile-search-focused.png"
adb shell uiautomator dump /sdcard/google-mobile-search-focused.xml >/dev/null 2>&1
adb exec-out cat /sdcard/google-mobile-search-focused.xml > "$ARTIFACTS/google-mobile-search-focused-ui.xml"
python3 - "$ARTIFACTS/google-mobile-search-focused-ui.xml" <<'PY' | tee -a "$ARTIFACTS/desktop-site-results.txt"
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
focused = any(node.attrib.get('class') == 'android.widget.EditText' and node.attrib.get('text') == 'hi' and node.attrib.get('focused') == 'true' for node in root.iter('node'))
if not focused:
    raise SystemExit('Google restored mobile search input did not receive focus')
print('PASS: Google mobile search field focused after PC mode restore; keyboard-control screenshot captured')
PY

# Reproduce the original wide-site failure on GitHub while the same tab is in desktop mode.
# The user's screenshots show GitHub Dashboard and a dictionary page cropped horizontally;
# Google alone is not a sufficient desktop-layout test.
adb shell input keyevent KEYCODE_BACK
GITHUB_DESKTOP_URL="https://github.com/hyouka56/browser"
adb shell am start --activity-single-top -W -n "$ACTIVITY" -a android.intent.action.VIEW -d "$GITHUB_DESKTOP_URL" 2>&1 | tee "$ARTIFACTS/github-mobile-repository-launch.txt"
wait_for_log "PAGE_LOAD_STATE loading=false" 7 120
sleep 3
if ! dismiss_external_anr_dialog_if_present; then
  echo "Could not safely dismiss an external system ANR before GitHub mobile screenshot capture." >&2
  exit 1
fi
adb exec-out screencap -p > "$ARTIFACTS/github-mobile-repository.png"
wait_for_desktop_site_transition "Show desktop site" "Desktop site enabled. Tap to use mobile site" true 3 30
wait_for_log "PAGE_LOAD_STATE loading=false" 8 120
wait_for_log "DESKTOP_DEVICE_METRICS_APPLIED window=1" 3 45
wait_for_log "DESKTOP_PAGE_SCALE_RESET window=1 success=true" 3 45
wait_for_log "DESKTOP_VIEWPORT_ZOOM_APPLIED window=1" 3 45
wait_for_log "DESKTOP_DOM_DIAGNOSTICS window=1" 3 45
sleep 4
if ! dismiss_external_anr_dialog_if_present; then
  echo "Could not safely dismiss an external system ANR before GitHub PC screenshot capture." >&2
  exit 1
fi
adb exec-out screencap -p > "$ARTIFACTS/github-desktop-repository.png"
adb shell uiautomator dump /sdcard/github-desktop-repository.xml >/dev/null 2>&1
adb exec-out cat /sdcard/github-desktop-repository.xml > "$ARTIFACTS/github-desktop-repository-ui.xml"
GITHUB_DESKTOP_DOM_LOG="$(adb logcat -d -s Browser:V 2>/dev/null | grep -F "DESKTOP_DOM_DIAGNOSTICS window=1" | tail -n1 || true)"
python3 - "$GITHUB_DESKTOP_DOM_LOG" "$ARTIFACTS/github-desktop-repository-ui.xml" <<'PY' | tee -a "$ARTIFACTS/desktop-site-results.txt"
import json, re, sys
match = re.search(r"result=(.*)$", sys.argv[1])
if not match:
    raise SystemExit(f"GitHub PC-mode DOM diagnostics missing: {sys.argv[1]}")
data = json.loads(match.group(1))
if isinstance(data, str):
    data = json.loads(data)
title = data.get("title", "")
body = data.get("bodyText", "")
inner = int(data.get("innerWidth", 0))
client = int(data.get("clientWidth", 0))
visual = float(data.get("visualViewportWidth", 0))
visual_scale = float(data.get("visualViewportScale", 0))
dpr = float(data.get("devicePixelRatio", 0))
doc_scroll = int(data.get("scrollWidth", 0))
body_client = int(data.get("bodyClientWidth", 0))
body_scroll = int(data.get("bodyScrollWidth", 0))
body_rect = int(data.get("bodyRectWidth", 0))
doc_rect = int(data.get("documentElementRectWidth", 0))
if not title.strip() or not body.strip() or "github" not in title.lower() and "browser" not in title.lower():
    print(f"FAIL: GitHub repository page did not render in PC mode: {data}")
else:
    print(f"PASS: GitHub repository page loaded in PC mode; title={title!r}, innerWidth={inner}px, visualViewport={visual}px, pageScale={visual_scale:.3f}, DPR={dpr:.3f}, client={client}px, documentScroll={doc_scroll}px, bodyScroll={body_scroll}px, bodyRect={body_rect}px, documentRect={doc_rect}px")
if not 1200 <= inner <= 1360 or not 1200 <= client <= 1360:
    print(f"FAIL: GitHub CSS viewport is not close to the requested 1280px: innerWidth={inner}, clientWidth={client}")
else:
    print(f"PASS: GitHub CSS viewport is {inner}px, close to the requested 1280px.")
if abs(visual - inner) > max(8, inner * 0.02):
    print(f"FAIL: GitHub visual viewport is narrower than its desktop layout viewport: visual={visual}, inner={inner}")
else:
    print(f"PASS: GitHub visual viewport spans the full layout viewport ({visual}px).")
if abs(visual_scale - 1.0) > 0.05:
    print(f"FAIL: GitHub page scale was not reset to 1.0: {visual_scale}")
else:
    print("PASS: GitHub page scale reset to 1.0.")
if abs(dpr - 1.0) > 0.05:
    print(f"FAIL: GitHub desktop DPR is not 1.0: {dpr}")
else:
    print("PASS: GitHub desktop DPR is 1.0.")
# A full-width desktop layout must keep the repo's main content near the left edge.
import xml.etree.ElementTree as ET
ui_root = ET.parse(sys.argv[2]).getroot()
repo_title = next((n for n in ui_root.iter("node") if n.attrib.get("resource-id") == "repo-title-component"), None)
if repo_title is None:
    print("FAIL: GitHub repo title container is missing from the visible hierarchy.")
else:
    bounds = re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", repo_title.attrib.get("bounds", ""))
    if not bounds:
        print("FAIL: GitHub repo title has no measurable screen bounds.")
    else:
        left = int(bounds.group(1))
        print(f"GitHub repo title starts at screen x={left}px.")
        if left > 100:
            print(f"FAIL: GitHub main content is shifted right ({left}px), reproducing the half-page layout.")
        else:
            print("PASS: GitHub main content begins near the left edge; no large blank left section.")
overflow = max(doc_scroll, body_scroll, body_rect, doc_rect) > max(client, body_client, inner) + 8
if overflow:
    print(f"FAIL: GitHub desktop layout extends beyond its viewport: inner={inner}, client={client}, docScroll={doc_scroll}, bodyClient={body_client}, bodyScroll={body_scroll}, bodyRect={body_rect}, documentRect={doc_rect}")
else:
    print("PASS: GitHub desktop repository page has no horizontal document/body overflow.")
PY
if [[ ! -s "$ARTIFACTS/github-desktop-repository.png" ]] || [[ "$(wc -c < "$ARTIFACTS/github-desktop-repository.png")" -le 10000 ]]; then
  echo "GitHub PC-mode screenshot was not captured correctly." >&2
  exit 1
fi
# Restore mobile mode on the GitHub tab as well, and keep the corresponding screenshot.
wait_for_desktop_site_transition "Desktop site enabled. Tap to use mobile site" "Show desktop site" false 3 30
wait_for_log "PAGE_LOAD_STATE loading=false" 9 120
wait_for_log "MOBILE_USER_AGENT_OVERRIDE_RESTORED window=1" 3 45
wait_for_log "MOBILE_VIEWPORT_ZOOM_RESET window=1" 3 30
wait_for_log "MOBILE_DEVICE_METRICS_RESTORED window=1" 1 30
sleep 3
if ! dismiss_external_anr_dialog_if_present; then
  echo "Could not safely dismiss an external system ANR before GitHub mobile restore screenshot." >&2
  exit 1
fi
adb exec-out screencap -p > "$ARTIFACTS/github-mobile-restored.png"
printf 'PASS: GitHub repository page was tested with PC enabled and then returned to mobile mode.\n' | tee -a "$ARTIFACTS/desktop-site-results.txt"

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
if tap_accessibility_node_with_system_recovery text "Cancel"; then
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
tap_accessibility_node_with_system_recovery desc "Ad blocker on"
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
tap_accessibility_node_with_system_recovery desc "Ad blocker off"
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
if ! dismiss_external_anr_dialog_if_present; then
  echo "Could not safely dismiss an external system ANR before the pre-recovery screenshot." >&2
  exit 1
fi
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
if ! dismiss_external_anr_dialog_if_present; then
  echo "Could not safely dismiss an external system ANR before the post-recovery screenshot." >&2
  exit 1
fi
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
  echo "System ANR dialog detected before download taps; validating it belongs to another app."
  dismiss_external_anr_dialog_if_present
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

# Remove the earlier public fixture, then prove an incognito download stays in
# app-private storage and is deleted when the last private window closes.
adb shell rm -f /sdcard/Download/download-test.txt
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$ACTIVITY" \
  --es com.search.browser.TEST_URL "https://example.com/" \
  --ez com.search.browser.TEST_DOWNLOAD_PAGE true \
  --ez com.search.browser.TEST_INCOGNITO_DOWNLOAD_PAGE true \
  --ez com.search.browser.TEST_CLOSE_PRIVATE_AFTER_DOWNLOAD true \
  2>&1 | tee "$ARTIFACTS/private-download-test-launch.txt"
wait_for_log "CEF_BROWSER_CREATED url=<private> incognito=true" 1 60
# The private browser-created event precedes page navigation and document-start
# JavaScript. Incognito titles are redacted, so wait for both the initial page
# title and the test fixture's title change before tapping its download button.
wait_for_log "PAGE_TITLE window=1 title=<private>" 2 60
wait_for_log "DOWNLOAD_HANDLER_REGISTERED" 2 30
if ! dismiss_external_anr_dialog_if_present; then
  echo "Could not safely dismiss an external system ANR before the private download tap." >&2
  exit 1
fi
if ! wait_for_browser_window_focus 15 2; then
  echo "Browser did not regain stable window focus before the private download tap." >&2
  exit 1
fi
adb exec-out screencap -p > "$ARTIFACTS/private-download-before-tap.png"
sleep 1
adb shell input tap 540 600
wait_for_log "DOWNLOAD_REQUEST_ACCEPTED name=Private download" 1 60
PRIVATE_DOWNLOAD_ID="$(adb logcat -d -s Browser:V 2>/dev/null | sed -nE 's/.*DOWNLOAD_REQUEST_ACCEPTED name=Private download id=([0-9]+).*/\1/p' | tail -n1)"
if [[ ! "$PRIVATE_DOWNLOAD_ID" =~ ^[0-9]+$ ]]; then
  echo "Could not identify the incognito download id." >&2
  adb logcat -d -s Browser:V >&2 || true
  exit 1
fi
wait_for_log "TEST_PRIVATE_DOWNLOAD_STAGED id=$PRIVATE_DOWNLOAD_ID exists=true" 1 90
wait_for_log "TEST_PRIVATE_DOWNLOAD_CLOSE_REQUESTED id=$PRIVATE_DOWNLOAD_ID" 1 30
wait_for_log "INCOGNITO_SESSION_CLOSED" 1 30
if adb shell test -e /sdcard/Download/download-test.txt; then
  echo "Incognito download leaked into the public Downloads directory." >&2
  exit 1
fi
PRIVATE_STAGING_FILES="$(adb shell run-as "$PACKAGE" ls files/private-downloads 2>/dev/null | tr -d '\r' || true)"
if grep -F "$PRIVATE_DOWNLOAD_ID-download-test.txt" <<< "$PRIVATE_STAGING_FILES" >/dev/null; then
  echo "Incognito staging file remained after the last private window closed." >&2
  printf '%s\n' "$PRIVATE_STAGING_FILES" >&2
  exit 1
fi
printf 'PASS: incognito download stayed in app-private storage, was not exported publicly, and was removed when the last private window closed.\n' | tee "$ARTIFACTS/private-download-results.txt"

# Relaunch the ordinary fixture before testing the keep-alive service.
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$ACTIVITY" \
  --es com.search.browser.TEST_URL "https://example.com/" \
  --ez com.search.browser.TEST_DOWNLOAD_PAGE true \
  2>&1 | tee "$ARTIFACTS/launch-background-download-test.txt"
wait_for_log "PAGE_TITLE window=1 title=Download Test" 3 60
wait_for_log "DOWNLOAD_HANDLER_REGISTERED" 3 30

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
desktop_site_results_path = folder / "desktop-site-results.txt"
desktop_site_results = desktop_site_results_path.read_text(errors="replace") if desktop_site_results_path.is_file() else ""
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
        "desktop_page_content_rendered_without_horizontal_overflow": "PASS: desktop page rendered at" in desktop_site_results and "no horizontal overflow" in desktop_site_results,
        "desktop_page_scale_reset_to_one": desktop_site_results.count("page scale reset to 1.0") >= 1,
        "desktop_visual_viewport_matches_layout_viewport": "visual viewport spans the full layout viewport" in desktop_site_results,
        "desktop_device_pixel_ratio_is_one": "desktop DPR is 1.0" in desktop_site_results,
        "mobile_css_viewport_restored_after_disabling_desktop_mode": "PASS: mobile CSS viewport restored to" in desktop_site_results,
        "mobile_user_agent_restored_after_disabling_desktop_mode": "PASS: Android mobile user-agent, Android navigator.platform, and mobile UA metadata restored after disabling PC mode" in desktop_site_results,
        "google_desktop_search_page_rendered_in_pc_mode": (
            "PASS: Google desktop search page rendered in PC mode" in desktop_site_results and
            (folder / "google-desktop-search.png").is_file() and
            (folder / "google-desktop-search-ui.xml").is_file() and
            (folder / "google-desktop-search.png").stat().st_size > 10000
        ),
        "google_mobile_search_page_rendered_after_desktop_restore": (
            "PASS: Google mobile user-agent, viewport and mobile metadata restored after PC mode." in desktop_site_results and
            (folder / "google-mobile-search-restored.png").is_file() and
            (folder / "google-mobile-search-restored.png").stat().st_size > 10000
        ),
        "google_mobile_search_field_focused_after_pc_restore": (
            "PASS: Google mobile search field focused after PC mode restore; keyboard-control screenshot captured" in desktop_site_results and
            (folder / "google-mobile-search-focused.png").is_file() and
            (folder / "google-mobile-search-focused-ui.xml").is_file() and
            (folder / "google-mobile-search-focused.png").stat().st_size > 10000
        ),
        "github_desktop_repository_rendered_in_pc_mode": (
            "PASS: GitHub repository page loaded in PC mode" in desktop_site_results and
            (folder / "github-desktop-repository.png").is_file() and
            (folder / "github-desktop-repository-ui.xml").is_file() and
            (folder / "github-desktop-repository.png").stat().st_size > 10000
        ),
        "github_desktop_repository_has_no_horizontal_overflow": (
            "PASS: GitHub desktop repository page has no horizontal document/body overflow." in desktop_site_results
        ),
        "github_desktop_css_viewport_is_near_1280px": (
            "PASS: GitHub CSS viewport is" in desktop_site_results and
            "close to the requested 1280px" in desktop_site_results
        ),
        "github_main_content_starts_near_left_edge": (
            "PASS: GitHub main content begins near the left edge" in desktop_site_results
        ),
        "github_pc_mode_returns_to_mobile": (
            "PASS: GitHub repository page was tested with PC enabled and then returned to mobile mode." in desktop_site_results and
            (folder / "github-mobile-restored.png").is_file() and
            (folder / "github-mobile-restored.png").stat().st_size > 10000
        ),
        "desktop_site_toggle_returns_to_mobile_mode": "PASS: PC control switched desktop mode on and back to mobile mode on Google." in desktop_site_results,
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
        "incognito_download_stayed_private_and_was_deleted": (
            (folder / "private-download-results.txt").is_file() and
            "PASS: incognito download stayed in app-private storage, was not exported publicly, and was removed when the last private window closed." in
                (folder / "private-download-results.txt").read_text()
        ),
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
