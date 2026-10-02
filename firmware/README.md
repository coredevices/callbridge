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

## Link findings (2026-10-01/02)

Measured with the app's adb command channel (`cmd.sh` in the session notes) and
the `stats`/`top` console commands:

- Sniff mode (phone and our own BTA) collapsed ACL throughput; `link_policy.c`
  removes sniff from the link policy and registers a BTM power-manager client
  that asks for ACTIVE, which stops BTA from retrying a rejected sniff command
  every 50 ms.
- Controller modem sleep is off; SCO data path HCI on both host and controller.
- SPP frames are batched (3 per write, `batch <n>`); the phone sends a 20 ms
  keepalive so credits piggyback.
- Remaining problem: the phone grants ~10 RFCOMM credits then withholds them
  for 20–40 s at a time (`credit_tx=0`, `tx_queue=0`, `xmit_window=9/9`),
  so the uplink alternates between ~50 and ~5–20 frames/s. Phone Wi-Fi on
  makes it worse (coexistence). Not solved.
