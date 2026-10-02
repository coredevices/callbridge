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
| 0x04 | pong: u32 token (echo of 0x15)                         |

app → ESP (write):

| type | body                          |
|------|-------------------------------|
| 0x10 | answer                        |
| 0x11 | hangup                        |
| 0x12 | loopback: u8 (0 off, 1 on)    |
| 0x13 | dial: ASCII number            |
| 0x14 | request stats (ESP replies 0x03) |
| 0x15 | ping: u32 token (ESP replies 0x04 immediately) |
| 0x16 | blast: u8 (0 off, 1 on). Throughput test: ESP streams 244-byte frames with codec 0xFF at max rate on `audio_tx` |

## Bluetooth Classic SPP (ESP32 ⇄ Android app)

Measured on the ESP32-WROVER-E: with an eSCO link active, BLE delivers only
~10 of 50 frames/s (~13 kbps) because BR/EDR and BLE time-share one radio.
So on Android the data path is an RFCOMM/SPP stream instead, which lives in
the same piconet as the HFP link. Firmware runs an SPP server named
`callbridge` (standard SPP UUID `00001101-0000-1000-8000-00805F9B34FB`);
the app discovers the device over BLE (same address) and then opens SPP, after
which the BLE link is not used. iOS (no third-party SPP) uses the GATT path.

Two link-level facts shape this path (measured, not from the datasheet):

- Both Bluedroid stacks put an idle HFP ACL into **sniff mode** (495 ms
  interval); ACL data then moves only at anchor points. Firmware removes sniff
  from its link policy (`link_policy.c`).
- With SCO active the link carries on the order of **25–50 packets/s total**,
  regardless of packet size, so both ends batch 3 audio frames per SPP write
  (+40 ms latency). Full-duplex traffic still exceeds the budget today.

Stream framing, both directions (several records may share one write):

| off | size | field     |
|-----|------|-----------|
| 0   | 1    | channel: 0 audio frame, 1 control message |
| 1   | 2    | payload length, u16 LE (≤ 1024)           |
| 3   | n    | payload, exactly as on the GATT characteristics |

## WebSocket (app ⇄ server)

Endpoint `GET /ws`. Binary messages are audio frames, unchanged. Text messages
are JSON control.

Client sends first:

```json
{"type":"hello","mode":"echo","room":"demo","delayMs":500,"name":"Pixel 9 Pro"}
```

`name` is optional and only labels the client on the latency page.

- `mode`: `echo` (server returns the client's own frames after `delayMs`) or
  `room` (frames go to every other client in `room`).
- Server replies `{"type":"ok"}` or `{"type":"error","message":"..."}`.

Server → client control: `{"type":"peer","count":n}` when room membership
changes.

After hello, the client may send:

- `{"type":"ping","t":<any number>}` → server replies `{"type":"pong","t":<same>}`.
- `{"type":"latency","sco_rt_ms":n|null,"ble_rt_ms":n|null,"ws_rt_ms":n|null}`
  every ~2 s. The server keeps the last 30 min per client and serves them at
  `GET /stats` (JSON) and on the page at `GET /` (per-hop tiles + time series).
- `{"type":"burst","t_server":ms}` arms the server's 1 kHz detector for 3 s;
  `t_server` is the client's estimate (server clock) of when the burst left the
  phone. On detection the server sends
  `{"type":"burst_detected","t_server":ms,"up_ms":n}`.
- `{"type":"inject"}` makes the server send a 60 ms 1 kHz burst (3 ADPCM frames,
  seq from 0xFFF0) into the return stream and reply `{"type":"injected","t_server":ms}`.
- `{"type":"e2e","full_rt_ms":n|null,"down_ms":n|null}` reports the phone-side
  result of an end-to-end test.

`pong` carries `server_t` (server clock, ms) so the client can compute a clock
offset: `offset = server_t - (t_sent + t_recv) / 2`. Frames returned by the server keep the sender's `seq`; the app does
not renumber.

## Timing / buffering

- ESP32 reverse path: jitter buffer target 60 ms (3 frames), max 200 ms; on
  underrun play silence, on overrun drop oldest. Counted in `rx_lost`/`rx_late`.
- App: no buffering, pure relay. Stats are derived from `seq` gaps.
- Expected round trip (phone→ESP→app→server→app→ESP→phone): 150–300 ms.

## Latency

Each hop is measured as a round trip; the page shows them side by side and
their sum. Jitter buffers add on top of these in the real audio path.

| hop | how | measured by |
|-----|-----|-------------|
| SCO phone ⇄ ESP32 | app opens SCO (no call needed), plays a 1 kHz 60 ms burst, firmware loops SCO input back, app detects the burst's return (Goertzel at 1 kHz, 10 ms resolution). Includes Android audio-stack latency. | app, on demand |
| BLE ESP32 ⇄ app | control ping 0x15 / pong 0x04 | app, every 2 s |
| WebSocket app ⇄ server | JSON ping / pong | app, every 2 s |

Full-path one-way estimate = total round trip / 2.

### Measured with real audio

The ping numbers only estimate. The end-to-end test injects a real tone and
detects it at the far end:

1. App switches the ESP32 loopback off (control 0x12) so the only way back is
   through the server, drains its recorder, writes a 60 ms 1 kHz burst to the
   SCO link and sends `burst` with the emit time on the server clock.
2. ESP32 encodes the burst → BLE → app → WS. The server decodes every uplink
   frame, keeps a rolling 1 kHz noise floor and reports **up** (one-way
   phone → server) on the first frame that clears 8× the floor.
3. In echo mode (delay 0) the frames return → app → BLE → ESP32 → SCO; the app
   detects the burst in its microphone stream: **full loop**.
4. **down** = full loop − up. Results go to the server (`e2e`) and the page.

Firmware encoder resets ADPCM state every frame (predictor 0, index 0) so each
frame is self-contained, as the header allows.
