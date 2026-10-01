# callbridge protocol

Three hops, one audio frame format end to end. The app and server relay audio
frames as opaque bytes; only the ESP32 and the far end (echo/room peer/browser)
encode or decode.

```
phone ⇄ HFP/SCO ⇄ ESP32 ⇄ BLE GATT ⇄ app ⇄ WebSocket ⇄ server
```

## Audio frame (all hops)

Little-endian. One frame = 20 ms of audio. Each frame carries its own ADPCM
predictor state so any frame decodes on its own (loss-tolerant, no inter-frame
dependency).

| off | size | field       | notes                                              |
|-----|------|-------------|----------------------------------------------------|
| 0   | 2    | `seq`       | u16, wraps; per direction, starts at 0 on session |
| 2   | 1    | `codec`     | 0x01 = IMA ADPCM 8 kHz, 0x02 = IMA ADPCM 16 kHz    |
| 3   | 2    | `predictor` | i16, IMA state at start of frame                   |
| 5   | 1    | `index`     | u8 (0..88), IMA step index at start of frame       |
| 6   | n    | `payload`   | 4-bit IMA ADPCM nibbles, low nibble first          |

Payload sizes for 20 ms: 8 kHz → 160 samples → 80 bytes (frame 86 B);
16 kHz → 320 samples → 160 bytes (frame 166 B). Both fit one BLE notification
at MTU 247 (244 B ATT payload).

IMA ADPCM is the standard DVI/IMA algorithm (step table of 89 entries, index
table ±1..±8). Encoder emits the state *before* encoding the frame.

## BLE GATT (ESP32 peripheral ⇄ app central)

Service `CB000001-5F6A-4E2B-9C1D-0C2A7B3E4D50` (advertised, 128-bit).

| characteristic | UUID                                   | props                   | direction |
|----------------|----------------------------------------|-------------------------|-----------|
| `audio_tx`     | `CB000002-5F6A-4E2B-9C1D-0C2A7B3E4D50` | notify                  | ESP → app |
| `audio_rx`     | `CB000003-5F6A-4E2B-9C1D-0C2A7B3E4D50` | write without response  | app → ESP |
| `control`      | `CB000004-5F6A-4E2B-9C1D-0C2A7B3E4D50` | read, write, notify     | both      |

Central requests MTU 247 after connect, then enables notifications on
`audio_tx` and `control`. Advertised name `callbridge`.

### control messages

First byte is the type. ESP → app (notify/read):

| type | body                                                   |
|------|--------------------------------------------------------|
| 0x01 | call state: u8 (0 idle, 1 incoming, 2 dialing, 3 active, 4 sco_up) |
| 0x02 | codec: u8 (same values as frame `codec`)               |
| 0x03 | stats: u32 tx_frames, u32 rx_frames, u32 rx_lost, u32 rx_late, u32 sco_drops |

app → ESP (write):

| type | body                          |
|------|-------------------------------|
| 0x10 | answer                        |
| 0x11 | hangup                        |
| 0x12 | loopback: u8 (0 off, 1 on)    |
| 0x13 | dial: ASCII number            |
| 0x14 | request stats (ESP replies 0x03) |

## WebSocket (app ⇄ server)

Endpoint `GET /ws`. Binary messages are audio frames, unchanged. Text messages
are JSON control.

Client sends first:

```json
{"type":"hello","mode":"echo","room":"demo","delayMs":500}
```

- `mode`: `echo` (server returns the client's own frames after `delayMs`) or
  `room` (frames go to every other client in `room`).
- Server replies `{"type":"ok"}` or `{"type":"error","message":"..."}`.

Server → client control: `{"type":"peer","count":n}` when room membership
changes. Frames returned by the server keep the sender's `seq`; the app does
not renumber.

## Timing / buffering

- ESP32 reverse path: jitter buffer target 60 ms (3 frames), max 200 ms; on
  underrun play silence, on overrun drop oldest. Counted in `rx_lost`/`rx_late`.
- App: no buffering, pure relay. Stats are derived from `seq` gaps.
- Expected round trip (phone→ESP→app→server→app→ESP→phone): 150–300 ms.
