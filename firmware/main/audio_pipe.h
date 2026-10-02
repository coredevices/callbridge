#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

/*
 * Audio pipeline between the HFP SCO link and the BLE GATT link. See docs/protocol.md.
 *
 *   SCO in  --> sco_in ring --> [core 1 task] ADPCM encode, 20 ms frames --> BLE audio_tx notify
 *   BLE audio_rx --> ADPCM decode --> sco_out ring (jitter buffer) --> SCO out
 *   loopback: SCO in is copied straight to sco_out (BLE tx still runs).
 */
void audio_pipe_init(void);

/* HFP side (called from Bluedroid's task). */
void audio_pipe_sco_state(bool connected, bool msbc);
void audio_pipe_sco_in(const uint8_t *pcm, uint32_t bytes);
uint32_t audio_pipe_sco_out(uint8_t *pcm, uint32_t bytes);
/* HFP indicator inputs: 0 setup cleared, 1 incoming, 2 dialing, 3 call active, 5 call ended. The advertised
 * state (protocol: 0 idle, 1 incoming, 2 dialing, 3 active, 4 sco_up) is derived from these plus SCO. */
void audio_pipe_call_state(uint8_t state);
void audio_pipe_hfp_disconnected(void);

/* BLE side (called from the GATT server). */
void audio_pipe_rx_frame(const uint8_t *frame, uint16_t len);
void audio_pipe_control(const uint8_t *msg, uint16_t len);
void audio_pipe_ble_state(bool connected);
void audio_pipe_spp_state(bool connected);

/* Console helpers. */
void audio_pipe_set_loopback(bool on);
bool audio_pipe_loopback(void);
void audio_pipe_print_stats(void);
void audio_pipe_set_spp_batch(int frames_per_write);
