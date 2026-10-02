#!/bin/bash
# Per-second uplink frame rate from the app's diagnostics for N seconds (default 60).
SERIAL="${CALLBRIDGE_SERIAL:-$(adb devices | awk 'NR==2{print $1}')}"
adb -s "$SERIAL" logcat -c; sleep "${1:-60}"
adb -s "$SERIAL" logcat -d -s callbridge 2>/dev/null | grep -E "rx [0-9]" | grep -oE "rx [0-9]+ f/s" | awk '{printf "%s ", $2} END {print ""}'
