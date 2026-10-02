#!/bin/bash
# Drive the Android app from the Mac (debug builds only):
#   scripts/cmd.sh <cmd> [wait-seconds] [ws-url]
# cmds: connect | connectble | disconnect | sco | unsco | measure | relay | relayroom | unrelay |
#       e2e | loopback | unloopback | batch1..4 | ka0|ka20|ka50|ka100 | lowlat | nolowlat
SERIAL="${CALLBRIDGE_SERIAL:-$(adb devices | awk 'NR==2{print $1}')}"
URL="${3:-ws://127.0.0.1:8080/ws}"
adb -s "$SERIAL" shell am broadcast -a callbridge.CMD -p callbridge.android --es cmd "$1" --es url "$URL" >/dev/null 2>&1
sleep "${2:-2}"
adb -s "$SERIAL" logcat -d -s callbridge 2>/dev/null | grep "cmd $1 ->" | tail -1 | cut -c33-300
