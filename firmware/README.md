# firmware

ESP-IDF 5.4, ESP32-WROVER-E, Bluedroid dual mode. Started from
`examples/bluetooth/bluedroid/classic_bt/hfp_hf`.

```bash
export IDF_PYTHON_ENV_PATH=~/.espressif/python_env/idf5.4_py3.13_env  # py3.9 env's pyserial is broken on this macOS
. ~/esp/esp-idf/export.sh
idf.py build
idf.py -p /dev/cu.usbserial-11120 flash monitor
```

If the port rejects every `stty`/`tcsetattr` with `Invalid argument`, the
CP2102N is wedged at the USB level: replug the board.

The board shows up as Classic BT device `callbridge`; pair from the phone. After
a reboot it reconnects HFP to the bonded phone by itself (10 s later, to avoid
colliding with the phone's own re-page).

Console commands (`help`): `con`/`dis` service-level connection, `cona`/`disa`
audio connection, `ac`/`rc`/`d <number>` answer/reject/dial, `lb <0|1>` SCO
loopback, `stats` pipeline counters, `top` per-task CPU, `batch <n>` audio
frames per SPP write.

Opening the serial port resets the board (DTR/RTS), so a serial capture is a
reboot; the HFP link comes back on its own.

Layout: `audio_pipe.c` (SCO ⇄ ADPCM frames ⇄ link, jitter buffer, loopback,
control messages), `spp_link.c` (Classic SPP server, the Android data path),
`ble_gatt.c` (GATT service, iOS/discovery path), `link_policy.c` (keeps the
ACL out of sniff mode via Bluedroid internals; without it ACL data crawls at
~10 packets/s while HFP is up), `adpcm.c`. Protocol in `../docs/protocol.md`.
