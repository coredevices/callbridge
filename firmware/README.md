# firmware

ESP-IDF 5.4, ESP32-WROVER-E, Bluedroid dual mode. Started from
`examples/bluetooth/bluedroid/classic_bt/hfp_hf`.

```bash
. ~/esp/esp-idf/export.sh
idf.py build
idf.py -p /dev/cu.usbserial-11120 flash monitor
```

The board shows up as Classic BT device `callbridge`; pair from the phone.
Console commands (`help`): `con`/`dis` service-level connection, `cona`/`disa`
audio connection, `ac`/`rc`/`d <number>` answer/reject/dial.

Audio path: HCI. With a SCO link up the example loops incoming PCM back to the
phone (milestone 1). BLE GATT service, ADPCM pipeline and stats are next; see
`../docs/protocol.md`.
