#!/bin/bash
# Full-path echo experiment: far end (pulse tester) -> server -> app -> dongle -> phone SCO echo
# -> dongle -> app -> server -> pulse tester.
#   scripts/fullpath.sh [keepalive_ms] [batch] [pulses] [interval] [echo]
# echo: sco (default: phone echoes SCO, the full path) or link (dongle sends downlink straight back up)
# PROBE=<bytes> env: instead of tone pulses, time every frame with marker frames of that size (link echo only)
# Needs: server on :8765, adb reverse tcp:8080 tcp:8765, dongle HFP-connected to the phone.
set -u
KA="${1:-20}"; BATCH="${2:-3}"; N="${3:-12}"; IV="${4:-2.5s}"; ECHO="${5:-sco}"
HERE="$(cd "$(dirname "$0")" && pwd)"; C="$HERE/cmd.sh"
export CALLBRIDGE_SERIAL="${CALLBRIDGE_SERIAL:-45211FDAP00047}"
adb -s "$CALLBRIDGE_SERIAL" reverse tcp:8080 tcp:8765 >/dev/null
# the relay service lives in the app: wake the phone, keep it awake on USB, bring the app up
adb -s "$CALLBRIDGE_SERIAL" shell svc power stayon usb
adb -s "$CALLBRIDGE_SERIAL" shell input keyevent KEYCODE_WAKEUP
adb -s "$CALLBRIDGE_SERIAL" shell am start -n callbridge.android/.MainActivity >/dev/null 2>&1
for i in $(seq 1 20); do adb -s "$CALLBRIDGE_SERIAL" shell pidof callbridge.android >/dev/null && break; sleep 0.5; done
sleep 2
$C unrelay 1 >/dev/null; $C unsco 1 >/dev/null; $C disconnect 2 >/dev/null
$C "ka$KA" 1 >/dev/null
echo "== keepalive ${KA} ms, batch ${BATCH}, echo at ${ECHO}"
$C connect 8 | sed 's/^ *callbridge: //'
$C sco 6 >/dev/null; $C unloopback 2 >/dev/null; $C "batch$BATCH" 2 >/dev/null
if [ "$ECHO" = link ]; then $C linkecho 2 >/dev/null; else $C unlinkecho 1 >/dev/null; $C scoecho 2 >/dev/null; fi
$C room 3 >/dev/null
adb -s "$CALLBRIDGE_SERIAL" logcat -c
if [ -n "${PROBE:-}" ]; then
  (cd "$HERE/../server" && go run ./cmd/pulse -room demo -probe "$PROBE" -dur "${DUR:-25s}") | grep -E "probe|echoed|sent"
else
  (cd "$HERE/../server" && go run ./cmd/pulse -room demo -count "$N" -interval "$IV") | grep -E "^pulse|pulses"
fi
echo -n "uplink f/s: "; adb -s "$CALLBRIDGE_SERIAL" logcat -d -s callbridge 2>/dev/null | grep -E "rx [0-9]" | grep -oE "rx [0-9]+ f/s" | awk '{printf "%s ", $2} END {print ""}'
