# callbridge — ESP32 ↔ phone call bridge demo

ESP32-WROVER-E pairs with a phone as a Bluetooth Classic Hands-Free unit, takes
the phone's call audio, encodes it (IMA ADPCM) and streams it over BLE to a
companion app, which relays it over WebSocket to a VoIP server. Return audio
flows the same path in reverse.

```
phone call ⇄ (HFP/SCO) ⇄ ESP32 ⇄ (Classic SPP, or BLE GATT) ⇄ app ⇄ (WebSocket) ⇄ server
```

Status (2026-10-01): pairing, HFP/SCO audio, ADPCM pipeline, SPP data link,
echo server and the latency page work end to end on a Pixel 9 Pro. A tone
injected at the phone is detected at the server (uplink 341 ms) and heard back
at the phone (full loop 550 ms) with uplink-only traffic; full-duplex traffic
still loses frames (packet budget on the ESP32's Classic link), which is the
next thing to fix. See `docs/protocol.md` for the measurement method.

| dir         | what                                                    |
|-------------|---------------------------------------------------------|
| `firmware/` | ESP-IDF 5.4, Bluedroid dual mode: HFP HF, SPP server, BLE GATT, ADPCM pipeline |
| `app/`      | Kotlin Multiplatform + Compose; Android built, iOS stub |
| `server/`   | Go WebSocket relay: echo/room modes, tone detector, latency page at `/` |
| `docs/`     | protocol spec, original handoff                         |

See `docs/protocol.md` for the wire formats and `docs/handoff.md` for the plan.
