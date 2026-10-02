#pragma once
#include <stdint.h>
#include <stdbool.h>

/* Opus on the Bluetooth link: 8 kHz (narrowband, phone-call quality) mono, 20 ms frames, 16 kbps CBR
 * = 40 bytes per frame. 16 kHz encoding took ~60% of a core and starved the BT stack under load.
 * Sized to the measured BLE budget during a call (~44 packets/s, small packets): two frames per
 * packet fit with no loss. Encoder used only by the pipeline task, decoder only by the BT task. */
#define OPUS_LINK_RATE     8000
#define OPUS_LINK_SAMPLES  160      /* 20 ms at 8 kHz */
#define OPUS_LINK_BITRATE  16000
#define OPUS_LINK_MAX      80       /* bytes per encoded frame we accept */

bool opus_link_init(void);
/* returns encoded bytes (40 with CBR) or -1 */
int opus_link_encode(const int16_t *pcm, uint8_t *out, int out_max);
/* returns decoded samples (320) or -1; out must hold OPUS_LINK_SAMPLES */
int opus_link_decode(const uint8_t *in, int len, int16_t *out);
