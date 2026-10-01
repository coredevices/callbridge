# callbridge — ESP32 ↔ phone call bridge demo

ESP32-WROVER-E pairs with a phone as a Bluetooth Classic Hands-Free unit, takes
the phone's call audio, encodes it (IMA ADPCM) and streams it over BLE to a
companion app, which relays it over WebSocket to a VoIP server. Return audio
flows the same path in reverse.

```
phone call ⇄ (HFP/SCO) ⇄ ESP32 ⇄ (BLE GATT) ⇄ app ⇄ (WebSocket) ⇄ server
```

| dir         | what                                                    |
|-------------|---------------------------------------------------------|
| `firmware/` | ESP-IDF 5.x, Bluedroid dual mode (HFP HF + BLE GATT)    |
| `app/`      | Kotlin Multiplatform + Compose; Android built, iOS stub |
| `server/`   | Go WebSocket relay: echo mode, room mode                 |
| `docs/`     | protocol spec, original handoff                         |

See `docs/protocol.md` for the wire formats and `docs/handoff.md` for the plan.
