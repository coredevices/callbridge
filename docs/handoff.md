# Handoff: ESP32 ↔ phone call bridge ("callbridge")

Paste this into a new Claude Code session started in `~/dev` on the machine
that has the ESP32-DevKitC-VE plugged in over USB and the Pixel on adb.

## Goal

Build a demo of: ESP32 (WROVER-E) pairs with a phone over Bluetooth Classic as
a Hands-Free headset, picks up the phone's cellular call audio, encodes it and
streams it over BLE to a companion app, which relays it to a VoIP server on
pebble-cluster and plays the returned audio back the same way in reverse
(server → app → BLE → ESP32 → BT Classic → phone call).

Hardware: Espressif ESP32-DevKitC-VE (ESP32-WROVER-E, 8 MB flash, 8 MB PSRAM,
micro-USB, CP2102N). Original ESP32 is required: it is the only variant with
BT Classic. GPIO16/17 are used by PSRAM — don't connect anything to them.

## Repo layout (create a new repo in ~/dev)

```
callbridge/
  firmware/   ESP-IDF 5.x, C
  app/        Kotlin Multiplatform + Compose Multiplatform; androidApp + iosApp
  server/     VoIP receiver (Go), Dockerfile, k8s manifests for pebble-cluster
  docs/       protocol spec: BLE GATT, audio framing, server wire format
```

Build/test only Android now (Pixel via adb). iOS target must compile but is not
exercised yet.

## Firmware (firmware/)

ESP-IDF 5.x, Bluedroid in **dual mode** (Classic + BLE).

menuconfig:
- Bluetooth → Bluedroid → Classic BT + BLE
- Classic BT → HFP → Hands-Free Unit role, audio data path = **HCI**
  (`CONFIG_BT_HFP_AUDIO_DATA_PATH_HCI`), Wide Band Speech (mSBC) on
- Component config → ESP PSRAM → enable; allow BT/WiFi to allocate from PSRAM

Milestones, in order:
1. Start from `examples/bluetooth/bluedroid/classic_bt/hfp_hf`. Pair with the
   Pixel, answer a call, get PCM via `esp_hf_client_register_data_callback`.
   **Loopback mode**: feed incoming PCM straight back out so the caller hears
   themselves. Proves the audio path.
2. BLE GATT service: `audio_tx` (notify, ESP→phone), `audio_rx`
   (write-without-response, phone→ESP), `control` (call state, codec, stats).
   Negotiate MTU 247. Coexistence example: `examples/bluetooth/bluedroid/coex/`.
3. **Measure BLE throughput while a call is active** (SCO/eSCO steals radio
   time). Pick the codec from that number.
4. Audio pipeline on core 1: HFP PCM (CVSD 8 kHz or mSBC 16 kHz) → IMA ADPCM →
   20 ms frames with a sequence number → BLE. Reverse path with a jitter
   buffer. ADPCM first (~64 kbps @16 kHz, ~32 kbps @8 kHz). Opus later if
   needed; PSRAM makes that feasible.
5. UART debug commands: dial, answer, hangup, loopback on/off, stats.

## App (app/)

- `shared/`: BLE transport (Kable — works on Android + iOS), frame protocol,
  ADPCM encode/decode in Kotlin (for local monitoring), Ktor WebSocket client
  to the server, session state machine.
- `androidApp/`: Compose UI — scan/connect, call state, level meters,
  latency/loss stats. Foreground service so the relay keeps running in the
  background.
- On Android the phone is both the HFP Audio Gateway (to the ESP32) and the
  BLE central; that's fine. iOS background caveat for later: use
  `bluetooth-central` background mode and do NOT start a CallKit/VoIP call or
  take the audio session — the app is a pure data relay.

## Server (server/)

Go, single static binary.
- WebSocket endpoint; app sends ADPCM frames up, server sends frames back.
- Mode 1 **echo**: return audio after a configurable delay (first end-to-end
  test). Mode 2 **room**: two clients in the same room get each other's audio.
- Optional browser page using WebAudio as a software "far end" so one phone
  plus a laptop is enough to test.
- Deploy like eng-dash: image build in CI, manifests under
  `flux/apps/callbridge/` in `coredevices/pebble-cluster`, exposed via the
  Cloudflare tunnel. Cluster access: `ssh -i ~/.ssh/id_exe root@100.94.166.111`
  then `kubectl`.

## Order of work

scaffold all three → firmware HFP loopback → BLE throughput with a live call →
app BLE connect + local monitor → server echo → full loop → room mode /
browser far end.

## Expected pain points

- BLE bandwidth during SCO: expect ~50–100 kbps usable. 8 kHz ADPCM is safe;
  16 kHz ADPCM is borderline.
- Round-trip latency 150–300 ms+; jitter buffers on both ends need tuning.
- RAM with Classic + BLE both up — put BT buffers in PSRAM.
- User can switch call audio back to the handset mid-call; handle it.

## Open decisions to settle with the user at the start

1. Repo name and whether it lives under the coredevices GitHub org.
2. Server modes wanted for the demo (echo / room / browser far end).
3. Go for the server (recommended) vs TypeScript.
4. Is ESP-IDF installed locally? If not, install v5.x via the VS Code
   extension or `install.sh` before milestone 1.
